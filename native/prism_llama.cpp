// JNI-мост Prism AI ↔ llama.cpp.
//
// Kotlin-сторона: ai.prism.inference.llama.LlamaNative (общая для Android и Desktop).
// Все вызовы одной сессии выполняются из одного рабочего потока; cancel() — из любого.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstddef>
#include <cstring>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "llama.h"
#include "mtmd-helper.h"
#include "mtmd.h"

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace {

// Коды результата generate(); дублируются в LlamaNative.kt.
constexpr jint STATUS_DONE = 0;           // модель завершила ответ
constexpr jint STATUS_MAX_TOKENS = 1;     // достигнут лимит токенов ответа
constexpr jint STATUS_CANCELLED = 2;      // остановлено пользователем
constexpr jint STATUS_CONTEXT_FULL = 3;   // закончилось место в контексте
constexpr jint STATUS_ERROR_TOKENIZE = -1;
constexpr jint STATUS_ERROR_DECODE = -2;
constexpr jint STATUS_ERROR_CALLBACK = -3; // исключение в Kotlin-колбэке
constexpr jint STATUS_ERROR_OOM = -4;
constexpr jint STATUS_ERROR_IMAGE = -5;    // изображение не удалось подготовить или закодировать

// ---------- Журнал: последняя ошибка llama.cpp для понятных сообщений в UI ----------

std::mutex g_log_mutex;
std::string g_last_error;
bool g_last_was_error = false;

void log_callback(ggml_log_level level, const char * text, void * /*user_data*/) {
    if (text == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        if (level == GGML_LOG_LEVEL_ERROR) {
            g_last_error = text;
            g_last_was_error = true;
        } else if (level == GGML_LOG_LEVEL_CONT && g_last_was_error) {
            g_last_error += text;
        } else if (level != GGML_LOG_LEVEL_CONT) {
            g_last_was_error = false;
        }
    }
#ifdef __ANDROID__
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, "PrismLlama", "%s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_print(ANDROID_LOG_WARN, "PrismLlama", "%s", text);
    }
#else
    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) {
        std::fputs(text, stderr);
    }
#endif
}

std::once_flag g_backend_once;
bool g_backend_ready = false;

// ---------- Сессия: контекст + батч + то, что уже лежит в KV-кэше ----------

// Единица промпта: текстовый токен или целое изображение (его нельзя переиспользовать частично).
struct CacheUnit {
    llama_token token = LLAMA_TOKEN_NULL;  // LLAMA_TOKEN_NULL — изображение
    std::string image_id;                  // идентификатор изображения (у текста пустой)
    llama_pos n_pos = 1;                   // сколько позиций занимает
    int32_t n_tokens = 1;                  // сколько ячеек KV-кэша занимает

    bool is_image() const { return token == LLAMA_TOKEN_NULL; }

    bool operator==(const CacheUnit & other) const {
        return token == other.token && image_id == other.image_id && n_pos == other.n_pos && n_tokens == other.n_tokens;
    }
};

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    llama_batch_ext * batch = nullptr;
    std::vector<CacheUnit> cached;  // единицы, состояние которых уже в памяти контекста
    std::atomic<bool> cancel{false};
};

bool abort_callback(void * data) {
    return static_cast<Session *>(data)->cancel.load();
}

Session * as_session(jlong handle) { return reinterpret_cast<Session *>(handle); }
llama_model * as_model(jlong handle) { return reinterpret_cast<llama_model *>(handle); }
mtmd_context * as_projector(jlong handle) { return reinterpret_cast<mtmd_context *>(handle); }

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool add_special) {
    int32_t n = -llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, add_special, true);
    if (n <= 0) return {};
    std::vector<llama_token> tokens(static_cast<size_t>(n));
    int32_t written = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), tokens.data(), n, add_special, true);
    if (written < 0) return {};
    tokens.resize(static_cast<size_t>(written));
    return tokens;
}

std::string to_std_string(JNIEnv * env, jbyteArray bytes) {
    const jsize len = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(len), '\0');
    if (len > 0) env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte *>(&out[0]));
    return out;
}

// Длина самого длинного префикса, который состоит только из завершённых UTF-8 последовательностей.
size_t complete_utf8_prefix(const std::string & s) {
    size_t i = s.size();
    // Ищем начало последней последовательности (не более 3 байт продолжения назад).
    size_t back = 0;
    while (i > 0 && back < 4) {
        const auto c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) != 0x80) {
            size_t need = 1;
            if ((c & 0xE0) == 0xC0) need = 2;
            else if ((c & 0xF0) == 0xE0) need = 3;
            else if ((c & 0xF8) == 0xF0) need = 4;
            return (s.size() - (i - 1) >= need) ? s.size() : i - 1;
        }
        --i;
        ++back;
    }
    return s.size();
}

void fill_batch(llama_batch_ext * batch, const llama_token * tokens, int32_t n, llama_pos pos0, bool logits_last) {
    llama_batch_ext_clear(batch);
    for (int32_t i = 0; i < n; ++i) {
        const int32_t idx = llama_batch_ext_add_token(batch, 0, tokens[i]);
        const llama_pos pos = pos0 + i;
        llama_batch_ext_set_pos(batch, idx, &pos);
    }
    if (logits_last && n > 0) {
        llama_batch_ext_set_output_logits(batch, n - 1, true);
    }
}

struct BitmapDeleter {
    void operator()(mtmd_bitmap * bitmap) const { mtmd_bitmap_free(bitmap); }
};
using BitmapPtr = std::unique_ptr<mtmd_bitmap, BitmapDeleter>;

struct ChunksDeleter {
    void operator()(mtmd_input_chunks * chunks) const { mtmd_input_chunks_free(chunks); }
};

void set_last_error(const char * text) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_last_error = text;
}

// Изображения из Kotlin: пиксели RGB (или null — заглушка для подсчёта токенов), размеры парами (ширина, высота)
// и идентификаторы. Возвращает false, если данные не согласованы.
bool collect_bitmaps(JNIEnv * env, jobjectArray pixels, jintArray sizes, jobjectArray ids, std::vector<BitmapPtr> & out) {
    if (sizes == nullptr) return true;
    const jsize count = env->GetArrayLength(sizes) / 2;
    if (count == 0) return true;
    std::vector<jint> dims(static_cast<size_t>(count) * 2);
    env->GetIntArrayRegion(sizes, 0, count * 2, dims.data());
    if (pixels != nullptr && env->GetArrayLength(pixels) != count) return false;
    if (ids != nullptr && env->GetArrayLength(ids) != count) return false;
    for (jsize i = 0; i < count; ++i) {
        const jint nx = dims[static_cast<size_t>(i) * 2];
        const jint ny = dims[static_cast<size_t>(i) * 2 + 1];
        if (nx <= 0 || ny <= 0) return false;
        BitmapPtr bitmap;
        if (pixels == nullptr) {
            bitmap.reset(mtmd_bitmap_init(static_cast<uint32_t>(nx), static_cast<uint32_t>(ny), nullptr));
        } else {
            auto data = static_cast<jbyteArray>(env->GetObjectArrayElement(pixels, i));
            if (data == nullptr) return false;
            const jsize len = env->GetArrayLength(data);
            if (static_cast<int64_t>(len) != static_cast<int64_t>(nx) * ny * 3) {
                env->DeleteLocalRef(data);
                return false;
            }
            std::vector<unsigned char> rgb(static_cast<size_t>(len));
            env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte *>(rgb.data()));
            env->DeleteLocalRef(data);
            bitmap.reset(mtmd_bitmap_init(static_cast<uint32_t>(nx), static_cast<uint32_t>(ny), rgb.data()));
        }
        if (!bitmap) return false;
        if (ids != nullptr) {
            auto id = static_cast<jstring>(env->GetObjectArrayElement(ids, i));
            if (id != nullptr) {
                const char * chars = env->GetStringUTFChars(id, nullptr);
                mtmd_bitmap_set_id(bitmap.get(), chars);
                env->ReleaseStringUTFChars(id, chars);
                env->DeleteLocalRef(id);
            }
        }
        out.push_back(std::move(bitmap));
    }
    return true;
}

// Промпт, разбитый на единицы кэша; для изображений хранится чанк libmtmd.
struct Prompt {
    std::vector<CacheUnit> units;
    std::vector<const mtmd_input_chunk *> chunks;  // для каждой единицы; nullptr у текста
    std::unique_ptr<mtmd_input_chunks, ChunksDeleter> owner;
};

// Возвращает 0 или код ошибки STATUS_*.
jint build_prompt(const llama_vocab * vocab, mtmd_context * projector, const std::string & text,
                  const std::vector<BitmapPtr> & bitmaps, Prompt & out) {
    if (bitmaps.empty()) {
        for (llama_token token : tokenize(vocab, text, true)) {
            CacheUnit unit;
            unit.token = token;
            out.units.push_back(unit);
            out.chunks.push_back(nullptr);
        }
        return out.units.empty() ? STATUS_ERROR_TOKENIZE : 0;
    }
    if (projector == nullptr) return STATUS_ERROR_IMAGE;

    out.owner.reset(mtmd_input_chunks_init());
    mtmd_input_text input{text.c_str(), text.size(), true, true};
    std::vector<const mtmd_bitmap *> raw;
    raw.reserve(bitmaps.size());
    for (const auto & bitmap : bitmaps) raw.push_back(bitmap.get());
    if (mtmd_tokenize(projector, out.owner.get(), &input, raw.data(), raw.size()) != 0) return STATUS_ERROR_IMAGE;

    const size_t n_chunks = mtmd_input_chunks_size(out.owner.get());
    for (size_t i = 0; i < n_chunks; ++i) {
        const mtmd_input_chunk * chunk = mtmd_input_chunks_get(out.owner.get(), i);
        if (mtmd_input_chunk_get_type(chunk) == MTMD_INPUT_CHUNK_TYPE_TEXT) {
            size_t n = 0;
            const llama_token * tokens = mtmd_input_chunk_get_tokens_text(chunk, &n);
            for (size_t t = 0; t < n; ++t) {
                CacheUnit unit;
                unit.token = tokens[t];
                out.units.push_back(unit);
                out.chunks.push_back(nullptr);
            }
        } else {
            CacheUnit unit;
            const char * id = mtmd_input_chunk_get_id(chunk);
            unit.image_id = id != nullptr ? id : "";
            unit.n_pos = mtmd_input_chunk_get_n_pos(chunk);
            unit.n_tokens = static_cast<int32_t>(mtmd_input_chunk_get_n_tokens(chunk));
            out.units.push_back(unit);
            out.chunks.push_back(chunk);
        }
    }
    return out.units.empty() ? STATUS_ERROR_TOKENIZE : 0;
}

int64_t cells_of(const std::vector<CacheUnit> & units, size_t from = 0) {
    int64_t total = 0;
    for (size_t i = from; i < units.size(); ++i) total += units[i].n_tokens;
    return total;
}

llama_pos positions_of(const std::vector<CacheUnit> & units, size_t count) {
    llama_pos total = 0;
    for (size_t i = 0; i < count && i < units.size(); ++i) total += units[i].n_pos;
    return total;
}

struct ProgressContext {
    JNIEnv * env;
    jobject listener;
    jmethodID method;
};

bool load_progress(float progress, void * user_data) {
    auto * pc = static_cast<ProgressContext *>(user_data);
    const jboolean keep = pc->env->CallBooleanMethod(pc->listener, pc->method, progress);
    if (pc->env->ExceptionCheck()) return false;
    return keep == JNI_TRUE;
}

}  // namespace

extern "C" {

// ---------- Бэкенды ----------

JNIEXPORT jboolean JNICALL
Java_ai_prism_inference_llama_LlamaNative_backendInit(JNIEnv * env, jobject /*thiz*/, jstring backend_dir) {
    std::string dir;
    if (backend_dir != nullptr) {
        const char * chars = env->GetStringUTFChars(backend_dir, nullptr);
        dir = chars;
        env->ReleaseStringUTFChars(backend_dir, chars);
    }
    std::call_once(g_backend_once, [&dir]() {
        llama_log_set(log_callback, nullptr);
        mtmd_helper_log_set(log_callback, nullptr);
        if (dir.empty()) {
            ggml_backend_load_all();
        } else {
            ggml_backend_load_all_from_path(dir.c_str());
        }
        llama_backend_init();
        g_backend_ready = ggml_backend_dev_count() > 0;
    });
    return g_backend_ready ? JNI_TRUE : JNI_FALSE;
}

// Список устройств: «CPU: …», «GPU: …» — для экрана моделей.
JNIEXPORT jobjectArray JNICALL
Java_ai_prism_inference_llama_LlamaNative_devices(JNIEnv * env, jobject /*thiz*/) {
    const size_t count = ggml_backend_dev_count();
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(count), string_class, nullptr);
    for (size_t i = 0; i < count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        const char * kind = "OTHER";
        switch (ggml_backend_dev_type(dev)) {
            case GGML_BACKEND_DEVICE_TYPE_CPU:  kind = "CPU"; break;
            case GGML_BACKEND_DEVICE_TYPE_GPU:  kind = "GPU"; break;
            case GGML_BACKEND_DEVICE_TYPE_IGPU: kind = "IGPU"; break;
            default: break;
        }
        std::string line = std::string(kind) + ":" + ggml_backend_dev_name(dev);
        jstring item = env->NewStringUTF(line.c_str());
        env->SetObjectArrayElement(result, static_cast<jsize>(i), item);
        env->DeleteLocalRef(item);
    }
    return result;
}

JNIEXPORT jstring JNICALL
Java_ai_prism_inference_llama_LlamaNative_lastError(JNIEnv * env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

// ---------- Модель ----------

JNIEXPORT jlong JNICALL
Java_ai_prism_inference_llama_LlamaNative_loadModel(
        JNIEnv * env, jobject /*thiz*/, jstring path, jint gpu_layers, jobject progress_listener) {
    const char * chars = env->GetStringUTFChars(path, nullptr);
    const std::string model_path = chars;
    env->ReleaseStringUTFChars(path, chars);
    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        g_last_error.clear();
    }
    try {
        llama_model_params params = llama_model_default_params();
        params.n_gpu_layers = gpu_layers;
        ProgressContext pc{env, progress_listener, nullptr};
        if (progress_listener != nullptr) {
            jclass cls = env->GetObjectClass(progress_listener);
            pc.method = env->GetMethodID(cls, "onProgress", "(F)Z");
            env->DeleteLocalRef(cls);
            if (pc.method != nullptr) {
                params.progress_callback = load_progress;
                params.progress_callback_user_data = &pc;
            }
        }
        llama_model * model = llama_model_load_from_file(model_path.c_str(), params);
        if (env->ExceptionCheck()) {
            if (model != nullptr) llama_model_free(model);
            return 0;
        }
        return reinterpret_cast<jlong>(model);
    } catch (const std::bad_alloc &) {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        g_last_error = "out of memory";
        return 0;
    } catch (...) {
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_ai_prism_inference_llama_LlamaNative_freeModel(JNIEnv * /*env*/, jobject /*thiz*/, jlong model) {
    if (model != 0) llama_model_free(as_model(model));
}

JNIEXPORT jstring JNICALL
Java_ai_prism_inference_llama_LlamaNative_modelDescription(JNIEnv * env, jobject /*thiz*/, jlong model) {
    char buf[256] = {0};
    llama_model_desc(as_model(model), buf, sizeof(buf));
    return env->NewStringUTF(buf);
}

JNIEXPORT jint JNICALL
Java_ai_prism_inference_llama_LlamaNative_modelTrainContext(JNIEnv * /*env*/, jobject /*thiz*/, jlong model) {
    return llama_model_n_ctx_train(as_model(model));
}

// ---------- Сессия ----------

JNIEXPORT jlong JNICALL
Java_ai_prism_inference_llama_LlamaNative_createSession(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong model, jint n_ctx, jint n_batch, jint n_threads) {
    try {
        auto * session = new Session();
        session->model = as_model(model);

        llama_context_params params = llama_context_default_params();
        params.n_ctx = static_cast<uint32_t>(n_ctx);
        params.n_batch = static_cast<uint32_t>(n_batch);
        params.n_ubatch = static_cast<uint32_t>(n_batch);
        params.n_threads = n_threads;
        params.n_threads_batch = n_threads;
        params.no_perf = true;

        session->ctx = llama_init_from_model(session->model, params);
        if (session->ctx == nullptr) {
            delete session;
            return 0;
        }
        llama_set_abort_callback(session->ctx, abort_callback, session);
        session->batch = llama_batch_ext_init(session->ctx);
        if (session->batch == nullptr) {
            llama_free(session->ctx);
            delete session;
            return 0;
        }
        return reinterpret_cast<jlong>(session);
    } catch (const std::bad_alloc &) {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        g_last_error = "out of memory";
        return 0;
    } catch (...) {
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_ai_prism_inference_llama_LlamaNative_freeSession(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    Session * session = as_session(handle);
    if (session == nullptr) return;
    if (session->batch != nullptr) llama_batch_ext_free(session->batch);
    if (session->ctx != nullptr) llama_free(session->ctx);
    delete session;
}

JNIEXPORT jint JNICALL
Java_ai_prism_inference_llama_LlamaNative_contextSize(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    return static_cast<jint>(llama_n_ctx(as_session(handle)->ctx));
}

// Сколько ячеек контекста займёт промпт; изображения считаются по размерам, без пикселей.
JNIEXPORT jint JNICALL
Java_ai_prism_inference_llama_LlamaNative_tokenCount(
        JNIEnv * env, jobject /*thiz*/, jlong handle, jlong projector, jbyteArray text, jintArray image_sizes) {
    try {
        Session * session = as_session(handle);
        std::vector<BitmapPtr> bitmaps;
        if (!collect_bitmaps(env, nullptr, image_sizes, nullptr, bitmaps)) return -1;
        Prompt prompt;
        const jint status = build_prompt(llama_model_get_vocab(session->model), as_projector(projector),
                                         to_std_string(env, text), bitmaps, prompt);
        if (status != 0) return -1;
        return static_cast<jint>(cells_of(prompt.units));
    } catch (...) {
        return -1;
    }
}

JNIEXPORT void JNICALL
Java_ai_prism_inference_llama_LlamaNative_cancel(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    Session * session = as_session(handle);
    if (session != nullptr) session->cancel.store(true);
}

// ---------- Проектор изображений (mmproj) ----------

// Возвращает дескриптор проектора или 0 (ошибка — в lastError()).
JNIEXPORT jlong JNICALL
Java_ai_prism_inference_llama_LlamaNative_loadProjector(
        JNIEnv * env, jobject /*thiz*/, jlong model, jstring path, jboolean use_gpu, jint n_threads, jint max_image_tokens) {
    const char * chars = env->GetStringUTFChars(path, nullptr);
    const std::string projector_path = chars;
    env->ReleaseStringUTFChars(path, chars);
    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        g_last_error.clear();
    }
    try {
        mtmd_context_params params = mtmd_context_params_default();
        params.use_gpu = use_gpu == JNI_TRUE;
        params.n_threads = n_threads;
        params.print_timings = false;
        params.image_max_tokens = max_image_tokens > 0 ? max_image_tokens : -1;
        // Прогрев выделяет память под вычисления сразу: нехватка памяти видна при загрузке, а не посреди ответа.
        params.warmup = true;
        mtmd_context * projector = mtmd_init_from_file(projector_path.c_str(), as_model(model), params);
        if (projector == nullptr) return 0;
        if (!mtmd_support_vision(projector)) {
            mtmd_free(projector);
            set_last_error("projector does not support images");
            return 0;
        }
        return reinterpret_cast<jlong>(projector);
    } catch (const std::bad_alloc &) {
        set_last_error("out of memory");
        return 0;
    } catch (...) {
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_ai_prism_inference_llama_LlamaNative_freeProjector(JNIEnv * /*env*/, jobject /*thiz*/, jlong projector) {
    if (projector != 0) mtmd_free(as_projector(projector));
}

// ---------- Генерация ----------

JNIEXPORT jint JNICALL
Java_ai_prism_inference_llama_LlamaNative_generate(
        JNIEnv * env, jobject /*thiz*/, jlong handle, jlong projector_handle, jbyteArray prompt_bytes,
        jobjectArray image_pixels, jintArray image_sizes, jobjectArray image_ids, jint max_tokens,
        jfloat temperature, jfloat top_p, jint top_k, jfloat min_p, jfloat repeat_penalty, jint seed,
        jobject listener) {
    Session * session = as_session(handle);
    mtmd_context * projector = as_projector(projector_handle);
    session->cancel.store(false);

    jclass listener_class = env->GetObjectClass(listener);
    jmethodID on_prefill = env->GetMethodID(listener_class, "onPrefill", "(II)Z");
    jmethodID on_token = env->GetMethodID(listener_class, "onToken", "([B)Z");
    env->DeleteLocalRef(listener_class);
    if (on_prefill == nullptr || on_token == nullptr) return STATUS_ERROR_CALLBACK;

    llama_sampler * sampler = nullptr;
    try {
        const llama_vocab * vocab = llama_model_get_vocab(session->model);
        llama_memory_t memory = llama_get_memory(session->ctx);
        const int64_t n_ctx = static_cast<int64_t>(llama_n_ctx(session->ctx));
        const int32_t n_batch = static_cast<int32_t>(llama_n_batch(session->ctx));

        std::vector<BitmapPtr> bitmaps;
        if (!collect_bitmaps(env, image_pixels, image_sizes, image_ids, bitmaps)) {
            set_last_error("invalid image data");
            return STATUS_ERROR_IMAGE;
        }
        Prompt prompt;
        const jint built = build_prompt(vocab, projector, to_std_string(env, prompt_bytes), bitmaps, prompt);
        if (built != 0) return built;
        const std::vector<CacheUnit> & units = prompt.units;
        // Логиты берутся с последнего токена промпта — он должен быть текстовым.
        if (units.back().is_image()) return STATUS_ERROR_TOKENIZE;
        if (cells_of(units) >= n_ctx) return STATUS_CONTEXT_FULL;

        // Переиспользуем общий префикс с тем, что уже лежит в кэше (история диалога и её изображения).
        size_t prefix = 0;
        while (prefix < session->cached.size() && prefix < units.size() && session->cached[prefix] == units[prefix]) {
            ++prefix;
        }
        if (prefix == units.size()) prefix = units.size() - 1;  // последний токен нужен для логитов
        llama_pos n_past = positions_of(units, prefix);
        if (prefix == 0 || !llama_memory_seq_rm(memory, 0, n_past, -1)) {
            llama_memory_clear(memory, true);
            prefix = 0;
            n_past = 0;
        }
        session->cached.resize(prefix);
        int64_t used = cells_of(session->cached);

        // Ошибка префилла: частично обработанный промпт не переиспользуем.
        auto prefill_failure = [&](int32_t ret) -> jint {
            llama_memory_clear(memory, true);
            session->cached.clear();
            if (ret == 2 || session->cancel.load()) return STATUS_CANCELLED;
            if (ret == 1) return STATUS_CONTEXT_FULL;
            return STATUS_ERROR_DECODE;
        };

        // Префилл: текст порциями по n_batch, изображения — через проектор.
        const int32_t total = static_cast<int32_t>(cells_of(units, prefix));
        int32_t done = 0;
        size_t i = prefix;
        while (i < units.size()) {
            if (session->cancel.load()) return STATUS_CANCELLED;  // кэш согласован с тем, что уже обработано
            if (!units[i].is_image()) {
                size_t end = i;
                std::vector<llama_token> tokens;
                while (end < units.size() && !units[end].is_image() && static_cast<int32_t>(tokens.size()) < n_batch) {
                    tokens.push_back(units[end].token);
                    ++end;
                }
                const int32_t n = static_cast<int32_t>(tokens.size());
                fill_batch(session->batch, tokens.data(), n, n_past, end == units.size());
                const int32_t ret = llama_process(session->ctx, LLAMA_PROCESS_TYPE_DECODE, session->batch);
                if (ret != 0) return prefill_failure(ret);
                session->cached.insert(session->cached.end(), units.begin() + static_cast<std::ptrdiff_t>(i),
                                       units.begin() + static_cast<std::ptrdiff_t>(end));
                n_past += n;
                used += n;
                done += n;
                i = end;
            } else {
                const mtmd_input_chunk * chunk = prompt.chunks[i];
                if (projector == nullptr || chunk == nullptr) return STATUS_ERROR_IMAGE;
                if (mtmd_encode_chunk(projector, chunk) != 0) {
                    set_last_error("failed to encode image");
                    return STATUS_ERROR_IMAGE;
                }
                llama_pos new_past = n_past;
                const int32_t ret = mtmd_helper_decode_image_chunk(
                    projector, session->ctx, chunk, mtmd_get_output_embd(projector), n_past, 0, n_batch, &new_past,
                    nullptr, nullptr);
                if (ret != 0) return prefill_failure(ret < 0 ? -1 : ret);
                session->cached.push_back(units[i]);
                n_past = new_past;
                used += units[i].n_tokens;
                done += units[i].n_tokens;
                ++i;
            }
            const jboolean keep = env->CallBooleanMethod(listener, on_prefill, done, total);
            if (env->ExceptionCheck()) return STATUS_ERROR_CALLBACK;
            if (keep != JNI_TRUE) return STATUS_CANCELLED;
        }

        // Сэмплер.
        sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(sampler, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, repeat_penalty, 0.0f, 0.0f));
        if (temperature <= 0.0f) {
            llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
        } else {
            if (top_k > 0) llama_sampler_chain_add(sampler, llama_sampler_init_top_k(top_k));
            llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, 1));
            llama_sampler_chain_add(sampler, llama_sampler_init_min_p(min_p, 1));
            llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(sampler, llama_sampler_init_dist(seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed)));
        }

        std::string pending;  // байты незавершённого UTF-8 символа
        jint status = STATUS_MAX_TOKENS;
        for (int32_t generated = 0; generated < max_tokens; ++generated) {
            if (session->cancel.load()) { status = STATUS_CANCELLED; break; }

            const llama_token token = llama_sampler_sample(sampler, session->ctx, -1);
            if (llama_vocab_is_eog(vocab, token)) { status = STATUS_DONE; break; }

            char buf[256];
            const int32_t n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, false);
            if (n > 0) {
                pending.append(buf, static_cast<size_t>(n));
                const size_t ready = complete_utf8_prefix(pending);
                if (ready > 0) {
                    jbyteArray piece = env->NewByteArray(static_cast<jsize>(ready));
                    env->SetByteArrayRegion(piece, 0, static_cast<jsize>(ready), reinterpret_cast<const jbyte *>(pending.data()));
                    const jboolean keep = env->CallBooleanMethod(listener, on_token, piece);
                    env->DeleteLocalRef(piece);
                    pending.erase(0, ready);
                    if (env->ExceptionCheck()) { status = STATUS_ERROR_CALLBACK; break; }
                    if (keep != JNI_TRUE) { status = STATUS_CANCELLED; break; }
                }
            }

            if (used + 1 >= n_ctx) { status = STATUS_CONTEXT_FULL; break; }
            fill_batch(session->batch, &token, 1, n_past, true);
            const int32_t ret = llama_process(session->ctx, LLAMA_PROCESS_TYPE_DECODE, session->batch);
            if (ret != 0) {
                if (ret == 2 || session->cancel.load()) { status = STATUS_CANCELLED; break; }
                status = ret == 1 ? STATUS_CONTEXT_FULL : STATUS_ERROR_DECODE;
                break;
            }
            CacheUnit unit;
            unit.token = token;
            session->cached.push_back(unit);
            ++n_past;
            ++used;
        }

        llama_sampler_free(sampler);
        return status;
    } catch (const std::bad_alloc &) {
        if (sampler != nullptr) llama_sampler_free(sampler);
        set_last_error("out of memory");
        return STATUS_ERROR_OOM;
    } catch (...) {
        if (sampler != nullptr) llama_sampler_free(sampler);
        return STATUS_ERROR_DECODE;
    }
}

}  // extern "C"
