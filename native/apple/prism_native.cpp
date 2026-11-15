#define PRISM_PORTABLE_BRIDGE
#include "../prism_llama.cpp"
#include <cstdlib>

#define N(name) Java_ai_prism_inference_llama_LlamaNative_##name
static char * copy_string(const std::string & s) {
    auto * p = static_cast<char *>(std::malloc(s.size() + 1));
    if (p) std::memcpy(p, s.c_str(), s.size() + 1);
    return p;
}
static jbyteArray bytes(JNIEnv & env, const int8_t * p, int n) {
    auto * a = env.NewByteArray(n);
    if (n) std::memcpy(a->data.data(), p, n);
    return a;
}
extern "C" {
int prism_backend_init() { JNIEnv e; return N(backendInit)(&e, nullptr, nullptr); }
char * prism_last_error() { JNIEnv e; return copy_string(N(lastError)(&e, nullptr)->text); }
char * prism_devices() {
    JNIEnv e;
    auto * a = N(devices)(&e, nullptr);
    std::string result;
    for (auto * v : a->data) { if (!result.empty()) result += '\n'; result += static_cast<CallString *>(v)->text; }
    return copy_string(result);
}
int64_t prism_load_model(const char * path, int gpu, prism_progress_fn cb, void * user) {
    try { JNIEnv e; CallString p(path); CallListener l; l.progress = cb; l.user = user; return N(loadModel)(&e, nullptr, &p, gpu, &l); }
    catch (...) { return 0; }
}
void prism_free_model(int64_t h) { N(freeModel)(nullptr, nullptr, h); }
char * prism_model_description(int64_t h) { JNIEnv e; return copy_string(N(modelDescription)(&e, nullptr, h)->text); }
int prism_model_context(int64_t h) { return N(modelTrainContext)(nullptr, nullptr, h); }
int64_t prism_create_session(int64_t h, int ctx, int batch, int threads) { return N(createSession)(nullptr, nullptr, h, ctx, batch, threads); }
void prism_free_session(int64_t h) { N(freeSession)(nullptr, nullptr, h); }
int prism_context_size(int64_t h) { return N(contextSize)(nullptr, nullptr, h); }
int prism_token_count(int64_t h, int64_t projector, const int8_t * text, int n, const int32_t * sizes, int count) {
    try { JNIEnv e; CallArray<jint> a(count); if (count) std::memcpy(a.data.data(), sizes, count * sizeof(jint));
        return N(tokenCount)(&e, nullptr, h, projector, bytes(e, text, n), count ? &a : nullptr); }
    catch (...) { return -1; }
}
void prism_cancel(int64_t h) { N(cancel)(nullptr, nullptr, h); }
int64_t prism_load_projector(int64_t h, const char * path, int gpu, int threads, int max_tokens) {
    try { JNIEnv e; CallString p(path); return N(loadProjector)(&e, nullptr, h, &p, gpu != 0, threads, max_tokens); }
    catch (...) { return 0; }
}
void prism_free_projector(int64_t h) { N(freeProjector)(nullptr, nullptr, h); }
int prism_generate(int64_t h, int64_t projector, const int8_t * text, int n, const prism_image * images, int count,
                   int max_tokens, float temperature, float top_p, int top_k, float min_p, float repeat_penalty, int seed,
                   prism_token_fn token, prism_prefill_fn prefill, void * user) {
    try {
        JNIEnv e; CallListener l; l.token = token; l.prefill = prefill; l.user = user;
        CallArray<jint> sizes(count * 2); CallArray<jobject> pixels(count), ids(count);
        for (int i = 0; i < count; ++i) {
            const auto & image = images[i];
            if (image.width <= 0 || image.height <= 0 || image.width > 4096 || image.height > 4096 || !image.pixels) return -5;
            sizes.data[2 * i] = image.width; sizes.data[2 * i + 1] = image.height;
            pixels.data[i] = bytes(e, image.pixels, image.width * image.height * 3);
            ids.data[i] = e.NewStringUTF(image.id);
        }
        return N(generate)(&e, nullptr, h, projector, bytes(e, text, n), count ? &pixels : nullptr,
            count ? &sizes : nullptr, count ? &ids : nullptr, max_tokens, temperature, top_p, top_k, min_p, repeat_penalty, seed, &l);
    } catch (const std::bad_alloc &) { return -4; } catch (...) { return -3; }
}
int64_t prism_create_embedder(int64_t h, int max_tokens, int threads) { return N(createEmbedder)(nullptr, nullptr, h, max_tokens, threads); }
void prism_free_embedder(int64_t h) { N(freeEmbedder)(nullptr, nullptr, h); }
float * prism_embed(int64_t h, const int8_t * text, int n, int * size) {
    *size = 0;
    try { JNIEnv e; auto * result = N(embed)(&e, nullptr, h, bytes(e, text, n)); if (!result) return nullptr;
        auto * p = static_cast<float *>(std::malloc(result->data.size() * sizeof(float)));
        if (p) { std::memcpy(p, result->data.data(), result->data.size() * sizeof(float)); *size = result->size(); } return p;
    } catch (...) { return nullptr; }
}
void prism_free(void * p) { std::free(p); }
}
