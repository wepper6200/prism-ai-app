// Prism AI: генерация изображений (stable-diffusion.cpp) в отдельном процессе.
//
// Протокол — строки JSON в UTF-8: команды приходят в stdin, события уходят в stdout.
// Пути передаются через stdin, а не аргументами: на Windows аргументы приходят в кодировке ANSI
// и ломают пути с кириллицей.
//
// Команды:
//   {"cmd":"load","diffusion_model":"…","model":"…","t5xxl":"…","clip_l":"…","clip_g":"…","vae":"…","taesd":"…",
//    "llm":"…","audio_vae":"…","embeddings_connectors":"…",   — LTX: текстовый энкодер (Gemma), звуковой VAE
//    "clip_vision":"…",                                      — Wan 2.1 I2V (Fun InP): энкодер кадра CLIP Vision H
//    "weight_type":"q8_0",                                   — необязательно: сжать веса при загрузке
//    "threads":4,"vae_tiling":false,"mmap":false,"offload_to_cpu":false,"flash_attn":false}
//                                                — offload_to_cpu: веса в оперативной памяти, в видеопамять — по частям
//   {"cmd":"generate","prompt":"…","negative":"","width":1024,"height":1024,"steps":4,"cfg":1.0,
//    "guidance":3.5,"seed":-1,"sampler":"euler","scheduler":"","output":"…/image.png",
//    "init_image":"…/photo.jpg","strength":0.7}   — init_image необязателен: перерисовка фото по описанию,
//                                                  strength — насколько сильно менять (0 — не менять, 1 — рисовать заново)
//   {"cmd":"generate_video","prompt":"…","negative":"…","width":832,"height":480,"frames":49,"fps":24,
//    "steps":20,"cfg":6.0,"flow_shift":3.0,"seed":-1,"sampler":"euler","scheduler":"","output":"…/video.avi",
//    "init_image":"…/photo.jpg"}                — видео (Wan, LTX); init_image необязателен: оживить фото (TI2V)
//                                                  или продолжить ролик от его последнего кадра. flow_shift ≤ 0 —
//                                                  по умолчанию модели. Результат — MJPEG AVI; звук (LTX) — WAV рядом:
//                                                  "<output>.wav", путь в событии done ("audio")
//   {"cmd":"convert","input":"…/encoder.safetensors","output":"…/encoder.q4_k.gguf","type":"q4_k"}
//                                                — сжать файл весов (например, текстовый энкодер LTX) в GGUF;
//                                                  пишется во временный "<output>.part", затем переименовывается
//   {"cmd":"cancel"}   — остановить текущую генерацию
//   {"cmd":"quit"}
// События:
//   {"event":"started"}                          — процесс запущен и ждёт команд
//   {"event":"loaded","device":"…"}              — модель загружена
//   {"event":"progress","step":1,"steps":4}
//   {"event":"converted","output":"…"}          — файл сжат
//   {"event":"done","output":"…","width":…,"height":…,"seed":…}          — для видео ещё "frames" и "fps"
//   {"event":"error","code":"…","message":"…"}  — коды: bad_request, load_failed, out_of_memory,
//                                                  generation_failed, cancelled, write_failed, busy

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <exception>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <iostream>
#include <mutex>
#include <new>
#include <random>
#include <string>
#include <thread>
#include <vector>

#include "ggml-backend.h"
#include "json.hpp"
#include "stable-diffusion.h"

#define STB_IMAGE_WRITE_IMPLEMENTATION
#define STB_IMAGE_WRITE_STATIC
#include "stb_image_write.h"

#define STB_IMAGE_IMPLEMENTATION
#define STB_IMAGE_STATIC
#include "stb_image.h"

#define STB_IMAGE_RESIZE_IMPLEMENTATION
#define STB_IMAGE_RESIZE_STATIC
#include "stb_image_resize.h"

#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif

#ifdef __ANDROID__
#include <android/log.h>
#endif

using json = nlohmann::json;

namespace {

std::mutex g_out_mutex;
std::mutex g_error_mutex;
std::string g_last_error;

void emit(const json & event) {
    const std::string line = event.dump(-1, ' ', false, json::error_handler_t::replace);
    std::lock_guard<std::mutex> lock(g_out_mutex);
    std::fwrite(line.data(), 1, line.size(), stdout);
    std::fputc('\n', stdout);
    std::fflush(stdout);
}

void emit_error(const std::string & code, const std::string & message) {
    emit({{"event", "error"}, {"code", code}, {"message", message}});
}

std::string last_error() {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    return g_last_error;
}

void clear_last_error() {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    g_last_error.clear();
}

bool looks_like_oom(const std::string & text) {
    return text.find("out of memory") != std::string::npos || text.find("failed to allocate") != std::string::npos ||
           text.find("OutOfDeviceMemory") != std::string::npos || text.find("ErrorOutOfHostMemory") != std::string::npos;
}

void log_callback(sd_log_level_t level, const char * text, void * /*data*/) {
    if (text == nullptr) return;
    if (level == SD_LOG_ERROR) {
        std::lock_guard<std::mutex> lock(g_error_mutex);
        g_last_error = text;
    }
    if (level == SD_LOG_ERROR || level == SD_LOG_WARN) {
#ifdef __ANDROID__
        __android_log_print(level == SD_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, "PrismImageGen", "%s", text);
#endif
        std::fputs(text, stderr);
        std::fflush(stderr);
    }
}

// Тот же обработчик получает и ход декодирования по частям (тайлы VAE): пропускаем только шаги семплирования —
// с заданным числом шагов и до последнего из них.
std::atomic<int> g_expected_steps{0};
std::atomic<bool> g_sampling_done{false};

void progress_callback(int step, int steps, float /*time*/, void * /*data*/) {
    if (steps <= 0 || steps != g_expected_steps.load() || g_sampling_done.load()) return;
    emit({{"event", "progress"}, {"step", step}, {"steps", steps}});
    if (step >= steps) g_sampling_done.store(true);
}

std::string device_name() {
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        const auto type = ggml_backend_dev_type(dev);
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) {
            return ggml_backend_dev_description(dev);
        }
    }
    return "CPU";
}

std::string str(const json & request, const char * key) {
    auto it = request.find(key);
    return it != request.end() && it->is_string() ? it->get<std::string>() : std::string();
}

const char * c_str_or_null(const std::string & value) { return value.empty() ? nullptr : value.c_str(); }

struct Paths {
    std::string model, diffusion_model, t5xxl, clip_l, clip_g, vae, taesd, llm, audio_vae, embeddings_connectors, clip_vision;
};


bool write_png(const std::string & path, const sd_image_t & image) {
    int length = 0;
    unsigned char * png = stbi_write_png_to_mem(image.data, static_cast<int>(image.width * image.channel),
                                                static_cast<int>(image.width), static_cast<int>(image.height),
                                                static_cast<int>(image.channel), &length, nullptr);
    if (png == nullptr) return false;
    bool ok = false;
    {
        const std::filesystem::path target = std::filesystem::u8path(path);
        const std::filesystem::path temp = std::filesystem::u8path(path + ".tmp");
        std::ofstream out(temp, std::ios::binary | std::ios::trunc);
        out.write(reinterpret_cast<const char *>(png), length);
        out.close();
        if (out) {
            std::error_code error;
            std::filesystem::rename(temp, target, error);
            ok = !error;
            if (!ok) std::filesystem::remove(temp, error);
        }
    }
    STBIW_FREE(png);
    return ok;
}

void put_u32(std::vector<unsigned char> & out, uint32_t value) {
    for (int i = 0; i < 4; ++i) out.push_back(static_cast<unsigned char>((value >> (8 * i)) & 0xFF));
}

void put_u16(std::vector<unsigned char> & out, uint16_t value) {
    out.push_back(static_cast<unsigned char>(value & 0xFF));
    out.push_back(static_cast<unsigned char>((value >> 8) & 0xFF));
}

void put_fourcc(std::vector<unsigned char> & out, const char * code) { out.insert(out.end(), code, code + 4); }

void set_u32(std::vector<unsigned char> & out, size_t pos, uint32_t value) {
    for (int i = 0; i < 4; ++i) out[pos + i] = static_cast<unsigned char>((value >> (8 * i)) & 0xFF);
}

void append_bytes(void * context, void * data, int size) {
    auto * out = static_cast<std::vector<unsigned char> *>(context);
    const auto * bytes = static_cast<const unsigned char *>(data);
    out->insert(out->end(), bytes, bytes + size);
}

// Звук модели (LTX) в WAV: 16-битный PCM, каналы чередуются (как и в sd_audio_t).
bool write_wav(const std::string & path, const sd_audio_t & audio) {
    if (audio.data == nullptr || audio.sample_count == 0 || audio.channels == 0 || audio.sample_rate == 0) return false;
    const uint32_t channels = audio.channels;
    const uint64_t samples = audio.sample_count;
    std::vector<unsigned char> wav;
    put_fourcc(wav, "RIFF");
    put_u32(wav, static_cast<uint32_t>(36 + samples * channels * 2));
    put_fourcc(wav, "WAVE");
    put_fourcc(wav, "fmt ");
    put_u32(wav, 16);
    put_u16(wav, 1);
    put_u16(wav, static_cast<uint16_t>(channels));
    put_u32(wav, audio.sample_rate);
    put_u32(wav, audio.sample_rate * channels * 2);
    put_u16(wav, static_cast<uint16_t>(channels * 2));
    put_u16(wav, 16);
    put_fourcc(wav, "data");
    put_u32(wav, static_cast<uint32_t>(samples * channels * 2));
    for (uint64_t i = 0; i < samples * channels; ++i) {
        float v = audio.data[i];
        if (v > 1.0f) v = 1.0f;
        if (v < -1.0f) v = -1.0f;
        put_u16(wav, static_cast<uint16_t>(static_cast<int16_t>(v * 32767.0f)));
    }
    const std::filesystem::path target = std::filesystem::u8path(path);
    const std::filesystem::path temp = std::filesystem::u8path(path + ".tmp");
    std::ofstream out(temp, std::ios::binary | std::ios::trunc);
    out.write(reinterpret_cast<const char *>(wav.data()), static_cast<std::streamsize>(wav.size()));
    out.close();
    if (!out) return false;
    std::error_code error;
    std::filesystem::rename(temp, target, error);
    if (error) std::filesystem::remove(temp, error);
    return !error;
}

// Видео без звука в MJPEG AVI: кадры — JPEG, индекс idx1. Такой файл открывают плееры Windows и Android,
// а приложение показывает его по кадрам.
bool write_mjpeg_avi(const std::string & path, const sd_image_t * frames, int count, int fps) {
    if (count <= 0 || fps <= 0) return false;
    const uint32_t width = frames[0].width;
    const uint32_t height = frames[0].height;
    std::vector<std::vector<unsigned char>> jpegs(static_cast<size_t>(count));
    for (int i = 0; i < count; ++i) {
        const sd_image_t & frame = frames[i];
        if (frame.data == nullptr || frame.width != width || frame.height != height) return false;
        if (!stbi_write_jpg_to_func(append_bytes, &jpegs[static_cast<size_t>(i)], static_cast<int>(frame.width),
                                    static_cast<int>(frame.height), static_cast<int>(frame.channel), frame.data, 90)) {
            return false;
        }
        if (jpegs[static_cast<size_t>(i)].size() % 2 == 1) jpegs[static_cast<size_t>(i)].push_back(0);  // чанки AVI выровнены по 2 байта
    }

    std::vector<unsigned char> avi;
    put_fourcc(avi, "RIFF");
    const size_t riff_size = avi.size();
    put_u32(avi, 0);
    put_fourcc(avi, "AVI ");

    put_fourcc(avi, "LIST");
    put_u32(avi, 4 + 8 + 56 + 8 + 4 + 8 + 56 + 8 + 40);
    put_fourcc(avi, "hdrl");
    put_fourcc(avi, "avih");
    put_u32(avi, 56);
    put_u32(avi, 1000000u / static_cast<uint32_t>(fps));  // микросекунд на кадр
    put_u32(avi, 0);
    put_u32(avi, 0);
    put_u32(avi, 0x10);  // AVIF_HASINDEX
    put_u32(avi, static_cast<uint32_t>(count));
    put_u32(avi, 0);
    put_u32(avi, 1);
    put_u32(avi, width * height * 3);
    put_u32(avi, width);
    put_u32(avi, height);
    for (int i = 0; i < 4; ++i) put_u32(avi, 0);

    put_fourcc(avi, "LIST");
    put_u32(avi, 4 + 8 + 56 + 8 + 40);
    put_fourcc(avi, "strl");
    put_fourcc(avi, "strh");
    put_u32(avi, 56);
    put_fourcc(avi, "vids");
    put_fourcc(avi, "MJPG");
    put_u32(avi, 0);
    put_u16(avi, 0);
    put_u16(avi, 0);
    put_u32(avi, 0);
    put_u32(avi, 1);                               // scale
    put_u32(avi, static_cast<uint32_t>(fps));      // rate
    put_u32(avi, 0);
    put_u32(avi, static_cast<uint32_t>(count));    // length
    put_u32(avi, width * height * 3);
    put_u32(avi, 0xFFFFFFFFu);
    put_u32(avi, 0);
    put_u16(avi, 0);
    put_u16(avi, 0);
    put_u16(avi, static_cast<uint16_t>(width));
    put_u16(avi, static_cast<uint16_t>(height));
    put_fourcc(avi, "strf");
    put_u32(avi, 40);
    put_u32(avi, 40);
    put_u32(avi, width);
    put_u32(avi, height);
    put_u16(avi, 1);
    put_u16(avi, 24);
    put_fourcc(avi, "MJPG");
    put_u32(avi, width * height * 3);
    for (int i = 0; i < 4; ++i) put_u32(avi, 0);

    put_fourcc(avi, "LIST");
    const size_t movi_size = avi.size();
    put_u32(avi, 0);
    const size_t movi_start = avi.size();
    put_fourcc(avi, "movi");
    std::vector<uint32_t> offsets;
    for (const auto & jpeg : jpegs) {
        offsets.push_back(static_cast<uint32_t>(avi.size() - movi_start));
        put_fourcc(avi, "00dc");
        put_u32(avi, static_cast<uint32_t>(jpeg.size()));
        avi.insert(avi.end(), jpeg.begin(), jpeg.end());
    }
    set_u32(avi, movi_size, static_cast<uint32_t>(avi.size() - movi_start));

    put_fourcc(avi, "idx1");
    put_u32(avi, static_cast<uint32_t>(16 * jpegs.size()));
    for (size_t i = 0; i < jpegs.size(); ++i) {
        put_fourcc(avi, "00dc");
        put_u32(avi, 0x10);  // AVIIF_KEYFRAME
        put_u32(avi, offsets[i]);
        put_u32(avi, static_cast<uint32_t>(jpegs[i].size()));
    }
    set_u32(avi, riff_size, static_cast<uint32_t>(avi.size() - 8));

    const std::filesystem::path target = std::filesystem::u8path(path);
    const std::filesystem::path temp = std::filesystem::u8path(path + ".tmp");
    std::ofstream out(temp, std::ios::binary | std::ios::trunc);
    out.write(reinterpret_cast<const char *>(avi.data()), static_cast<std::streamsize>(avi.size()));
    out.close();
    if (!out) return false;
    std::error_code error;
    std::filesystem::rename(temp, target, error);
    if (error) std::filesystem::remove(temp, error);
    return !error;
}

// Исходное фото для перерисовки: читается по UTF-8 пути и приводится к размеру генерации (RGB).
bool load_init_image(const std::string & path, int width, int height, std::vector<unsigned char> & out) {
    std::ifstream in(std::filesystem::u8path(path), std::ios::binary);
    if (!in) return false;
    const std::vector<unsigned char> bytes((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    int w = 0;
    int h = 0;
    int channels = 0;
    unsigned char * pixels = stbi_load_from_memory(bytes.data(), static_cast<int>(bytes.size()), &w, &h, &channels, 3);
    if (pixels == nullptr) return false;
    out.resize(static_cast<size_t>(width) * static_cast<size_t>(height) * 3);
    const int ok = stbir_resize_uint8(pixels, w, h, 0, out.data(), width, height, 0, 3);
    stbi_image_free(pixels);
    return ok != 0;
}

class Worker {
public:
    ~Worker() {
        join();
        if (ctx_ != nullptr) free_sd_ctx(ctx_);
    }

    void load(const json & request) {
        if (busy_.load()) return emit_error("busy", "generation in progress");
        if (ctx_ != nullptr) {
            free_sd_ctx(ctx_);
            ctx_ = nullptr;
        }
        paths_ = Paths{str(request, "model"), str(request, "diffusion_model"), str(request, "t5xxl"), str(request, "clip_l"),
                       str(request, "clip_g"), str(request, "vae"), str(request, "taesd"), str(request, "llm"),
                       str(request, "audio_vae"), str(request, "embeddings_connectors"), str(request, "clip_vision")};
        if (paths_.model.empty() && paths_.diffusion_model.empty()) {
            return emit_error("bad_request", "no model path");
        }

        sd_ctx_params_t params;
        sd_ctx_params_init(&params);
        params.model_path = c_str_or_null(paths_.model);
        params.diffusion_model_path = c_str_or_null(paths_.diffusion_model);
        params.t5xxl_path = c_str_or_null(paths_.t5xxl);
        params.clip_l_path = c_str_or_null(paths_.clip_l);
        params.clip_g_path = c_str_or_null(paths_.clip_g);
        params.vae_path = c_str_or_null(paths_.vae);
        params.taesd_path = c_str_or_null(paths_.taesd);
        params.llm_path = c_str_or_null(paths_.llm);
        params.audio_vae_path = c_str_or_null(paths_.audio_vae);
        params.embeddings_connectors_path = c_str_or_null(paths_.embeddings_connectors);
        params.clip_vision_path = c_str_or_null(paths_.clip_vision);
        // Веса в формате bf16/f16 можно сжать при загрузке ("q8_0", "q4_0"…): меньше памяти на телефоне.
        const std::string weight_type = str(request, "weight_type");
        if (!weight_type.empty()) {
            const sd_type_t type = str_to_sd_type(weight_type.c_str());
            if (type == SD_TYPE_COUNT) return emit_error("bad_request", "unknown weight type: " + weight_type);
            params.wtype = type;
        }
        params.enable_mmap = request.value("mmap", false);
        // Веса — в оперативной памяти, в видеопамять попадают по частям: видео Wan не помещается в 8–12 ГБ целиком.
        params_backend_ = request.value("offload_to_cpu", false) ? "*=cpu" : "";
        params.params_backend = c_str_or_null(params_backend_);
        if (request.value("flash_attn", false)) {
            params.flash_attn = true;
            params.diffusion_flash_attn = true;
        }
        const int threads = request.value("threads", 0);
        if (threads > 0) params.n_threads = threads;
        vae_tiling_ = request.value("vae_tiling", false);

        clear_last_error();
        try {
            ctx_ = new_sd_ctx(&params);
        } catch (const std::bad_alloc &) {
            ctx_ = nullptr;
            return emit_error("out_of_memory", "out of memory while loading the model");
        } catch (const std::exception & e) {
            ctx_ = nullptr;
            return emit_error("load_failed", e.what());
        }
        if (ctx_ == nullptr) {
            const std::string error = last_error();
            return emit_error(looks_like_oom(error) ? "out_of_memory" : "load_failed", error);
        }
        sd_set_progress_callback(progress_callback, nullptr);
        emit({{"event", "loaded"}, {"device", device_name()}});
    }

    void convert_weights(const json & request) {
        if (busy_.load()) return emit_error("busy", "generation in progress");
        const std::string input = str(request, "input");
        const std::string output = str(request, "output");
        const std::string type_name = str(request, "type");
        if (input.empty() || output.empty()) return emit_error("bad_request", "no input or output path");
        const sd_type_t type = str_to_sd_type(type_name.c_str());
        if (type == SD_TYPE_COUNT) return emit_error("bad_request", "unknown weight type: " + type_name);
        // Сжатие идёт по одному тензору — модель целиком в память не загружается, но загруженная занимает место.
        if (ctx_ != nullptr) {
            free_sd_ctx(ctx_);
            ctx_ = nullptr;
        }
        const std::string temp = output + ".part";
        std::error_code error;
        std::filesystem::remove(std::filesystem::u8path(temp), error);
        clear_last_error();
        bool ok = false;
        try {
            ok = convert(input.c_str(), nullptr, temp.c_str(), type, nullptr, false);
        } catch (const std::bad_alloc &) {
            std::filesystem::remove(std::filesystem::u8path(temp), error);
            return emit_error("out_of_memory", "out of memory while converting");
        } catch (const std::exception & e) {
            std::filesystem::remove(std::filesystem::u8path(temp), error);
            return emit_error("convert_failed", e.what());
        }
        if (!ok) {
            std::filesystem::remove(std::filesystem::u8path(temp), error);
            const std::string message = last_error();
            return emit_error(looks_like_oom(message) ? "out_of_memory" : "convert_failed",
                              message.empty() ? "cannot convert " + input : message);
        }
        std::filesystem::remove(std::filesystem::u8path(output), error);
        std::filesystem::rename(std::filesystem::u8path(temp), std::filesystem::u8path(output), error);
        if (error) {
            std::filesystem::remove(std::filesystem::u8path(temp), error);
            return emit_error("write_failed", "cannot write " + output);
        }
        emit({{"event", "converted"}, {"output", output}});
    }

    void generate(const json & request, bool video) {
        if (ctx_ == nullptr) return emit_error("bad_request", "model is not loaded");
        if (busy_.exchange(true)) return emit_error("busy", "generation in progress");
        join();
        thread_ = std::thread([this, request, video]() {
            if (video) {
                run_video(request);
            } else {
                run(request);
            }
            busy_.store(false);
        });
    }

    void cancel() {
        if (ctx_ != nullptr && busy_.load()) sd_cancel_generation(ctx_, SD_CANCEL_ALL);
    }

    void join() {
        if (thread_.joinable()) thread_.join();
    }

private:
    void run(const json & request) {
        const std::string prompt = str(request, "prompt");
        const std::string negative = str(request, "negative");
        const std::string output = str(request, "output");
        if (output.empty()) return emit_error("bad_request", "no output path");

        sd_img_gen_params_t params;
        sd_img_gen_params_init(&params);
        params.prompt = prompt.c_str();
        params.negative_prompt = negative.c_str();
        params.width = request.value("width", 1024);
        params.height = request.value("height", 1024);
        params.seed = request.value("seed", static_cast<int64_t>(-1));
        if (params.seed < 0) params.seed = static_cast<int64_t>(std::random_device{}() & 0x7fffffff);
        params.sample_params.sample_steps = request.value("steps", 4);
        params.sample_params.guidance.txt_cfg = request.value("cfg", 1.0f);
        params.sample_params.guidance.distilled_guidance = request.value("guidance", 3.5f);
        const std::string sampler = str(request, "sampler");
        const std::string scheduler = str(request, "scheduler");
        params.sample_params.sample_method =
            sampler.empty() ? sd_get_default_sample_method(ctx_) : str_to_sample_method(sampler.c_str());
        if (params.sample_params.sample_method == SAMPLE_METHOD_COUNT) params.sample_params.sample_method = sd_get_default_sample_method(ctx_);
        params.sample_params.scheduler = scheduler.empty()
                                             ? sd_get_default_scheduler(ctx_, params.sample_params.sample_method)
                                             : str_to_scheduler(scheduler.c_str());
        if (params.sample_params.scheduler == SCHEDULER_COUNT) {
            params.sample_params.scheduler = sd_get_default_scheduler(ctx_, params.sample_params.sample_method);
        }
        params.vae_tiling_params.enabled = vae_tiling_;

        std::vector<unsigned char> init_pixels;
        const std::string init_path = str(request, "init_image");
        if (!init_path.empty()) {
            if (!load_init_image(init_path, params.width, params.height, init_pixels)) {
                return emit_error("bad_request", "cannot read init image");
            }
            params.init_image = sd_image_t{static_cast<uint32_t>(params.width), static_cast<uint32_t>(params.height), 3,
                                           init_pixels.data()};
            params.strength = request.value("strength", 0.7f);
        }

        g_expected_steps.store(params.sample_params.sample_steps);
        g_sampling_done.store(false);
        sd_cancel_generation(ctx_, SD_CANCEL_RESET);
        clear_last_error();
        sd_image_generation_result_t * result = nullptr;
        bool ok = false;
        try {
            ok = generate_image(ctx_, &params, &result);
        } catch (const std::bad_alloc &) {
            return emit_error("out_of_memory", "out of memory during generation");
        } catch (const std::exception & e) {
            return emit_error("generation_failed", e.what());
        }
        if (!ok || result == nullptr || result->count < 1 || result->items[0].image.data == nullptr) {
            if (result != nullptr) free_sd_image_generation_result(result);
            if (cancel_requested()) return emit_error("cancelled", "cancelled");
            const std::string error = last_error();
            return emit_error(looks_like_oom(error) ? "out_of_memory" : "generation_failed", error);
        }
        const sd_image_t image = result->items[0].image;
        const bool written = write_png(output, image);
        const uint32_t width = image.width;
        const uint32_t height = image.height;
        free_sd_image_generation_result(result);
        if (!written) return emit_error("write_failed", "cannot write " + output);
        emit({{"event", "done"}, {"output", output}, {"width", width}, {"height", height}, {"seed", params.seed}});
    }

    void run_video(const json & request) {
        const std::string prompt = str(request, "prompt");
        const std::string negative = str(request, "negative");
        const std::string output = str(request, "output");
        if (output.empty()) return emit_error("bad_request", "no output path");

        sd_vid_gen_params_t params;
        sd_vid_gen_params_init(&params);
        params.prompt = prompt.c_str();
        params.negative_prompt = negative.c_str();
        params.width = request.value("width", 832);
        params.height = request.value("height", 480);
        params.video_frames = request.value("frames", 33);
        params.fps = request.value("fps", 16);
        params.seed = request.value("seed", static_cast<int64_t>(-1));
        if (params.seed < 0) params.seed = static_cast<int64_t>(std::random_device{}() & 0x7fffffff);
        params.sample_params.sample_steps = request.value("steps", 20);
        params.sample_params.guidance.txt_cfg = request.value("cfg", 6.0f);
        // flow_shift ≤ 0 — значение по умолчанию модели (у LTX оно своё).
        const float flow_shift = request.value("flow_shift", 0.0f);
        if (flow_shift > 0.0f) params.sample_params.flow_shift = flow_shift;
        const std::string sampler = str(request, "sampler");
        const std::string scheduler = str(request, "scheduler");
        params.sample_params.sample_method =
            sampler.empty() ? sd_get_default_sample_method(ctx_) : str_to_sample_method(sampler.c_str());
        if (params.sample_params.sample_method == SAMPLE_METHOD_COUNT) params.sample_params.sample_method = sd_get_default_sample_method(ctx_);
        params.sample_params.scheduler = scheduler.empty()
                                             ? sd_get_default_scheduler(ctx_, params.sample_params.sample_method)
                                             : str_to_scheduler(scheduler.c_str());
        if (params.sample_params.scheduler == SCHEDULER_COUNT) {
            params.sample_params.scheduler = sd_get_default_scheduler(ctx_, params.sample_params.sample_method);
        }
        params.vae_tiling_params.enabled = vae_tiling_;

        std::vector<unsigned char> init_pixels;
        const std::string init_path = str(request, "init_image");
        if (!init_path.empty()) {
            if (!load_init_image(init_path, params.width, params.height, init_pixels)) {
                return emit_error("bad_request", "cannot read init image");
            }
            params.init_image = sd_image_t{static_cast<uint32_t>(params.width), static_cast<uint32_t>(params.height), 3,
                                           init_pixels.data()};
        }

        g_expected_steps.store(params.sample_params.sample_steps);
        g_sampling_done.store(false);
        sd_cancel_generation(ctx_, SD_CANCEL_RESET);
        clear_last_error();
        sd_video_generation_result_t * result = nullptr;
        bool ok = false;
        try {
            ok = generate_video(ctx_, &params, &result);
        } catch (const std::bad_alloc &) {
            return emit_error("out_of_memory", "out of memory during generation");
        } catch (const std::exception & e) {
            return emit_error("generation_failed", e.what());
        }
        if (!ok || result == nullptr || result->frame_count < 1 || result->frames == nullptr) {
            if (result != nullptr) free_sd_video_generation_result(result);
            if (cancel_requested()) return emit_error("cancelled", "cancelled");
            const std::string error = last_error();
            return emit_error(looks_like_oom(error) ? "out_of_memory" : "generation_failed", error);
        }
        const int fps = result->fps > 0 ? result->fps : params.fps;
        const int frames = result->frame_count;
        const uint32_t width = result->frames[0].width;
        const uint32_t height = result->frames[0].height;
        const bool written = write_mjpeg_avi(output, result->frames, frames, fps);
        const std::string audio_path = output + ".wav";
        const bool has_audio = result->audio != nullptr && write_wav(audio_path, *result->audio);
        free_sd_video_generation_result(result);
        if (!written) return emit_error("write_failed", "cannot write " + output);
        json done = {{"event", "done"}, {"output", output}, {"width", width}, {"height", height}, {"seed", params.seed},
                     {"frames", frames}, {"fps", fps}};
        if (has_audio) done["audio"] = audio_path;
        emit(done);
    }

    bool cancel_requested() const {
        const std::string error = last_error();
        return error.find("cancel") != std::string::npos || cancelled_.load();
    }

    sd_ctx_t * ctx_ = nullptr;
    Paths paths_;
    std::string params_backend_;
    bool vae_tiling_ = false;
    std::atomic<bool> busy_{false};
    std::atomic<bool> cancelled_{false};
    std::thread thread_;

public:
    void mark_cancelled(bool value) { cancelled_.store(value); }
};

}  // namespace

int main() {
#ifdef _WIN32
    _setmode(_fileno(stdin), _O_BINARY);
    _setmode(_fileno(stdout), _O_BINARY);
#endif
    sd_set_log_callback(log_callback, nullptr);
    Worker worker;
    emit({{"event", "started"}});

    std::string line;
    while (std::getline(std::cin, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line.empty()) continue;
        json request;
        try {
            request = json::parse(line);
        } catch (const std::exception & e) {
            emit_error("bad_request", e.what());
            continue;
        }
        const std::string cmd = str(request, "cmd");
        if (cmd == "load") {
            worker.load(request);
        } else if (cmd == "generate" || cmd == "generate_video") {
            worker.mark_cancelled(false);
            worker.generate(request, cmd == "generate_video");
        } else if (cmd == "convert") {
            worker.convert_weights(request);
        } else if (cmd == "cancel") {
            worker.mark_cancelled(true);
            worker.cancel();
        } else if (cmd == "quit") {
            break;
        } else {
            emit_error("bad_request", "unknown command: " + cmd);
        }
    }
    // stdin закрыт или пришла команда quit: дожидаемся генерации (если идёт, она будет отменена) и выходим.
    worker.cancel();
    worker.join();
    return 0;
}
