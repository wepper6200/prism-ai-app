#include "prism_speech.h"
#include "c-api.h"
#include <cstdlib>
#include <cstring>
#include <memory>
extern "C" {
void *prism_asr_create(const char *engine, const char *model, const char *decoder, const char *tokens, const char *language, int threads) try {
    SherpaOnnxOfflineRecognizerConfig c{};
    c.feat_config.sample_rate = 16000; c.feat_config.feature_dim = 80;
    c.model_config.num_threads = threads; c.model_config.provider = "cpu";
    c.model_config.tokens = tokens; c.decoding_method = "greedy_search";
    if (std::strcmp(engine, "whisper") == 0) {
        c.model_config.whisper.encoder = model; c.model_config.whisper.decoder = decoder;
        c.model_config.whisper.language = language; c.model_config.whisper.task = "transcribe";
    } else { c.model_config.nemo_ctc.model = model; }
    return const_cast<SherpaOnnxOfflineRecognizer *>(SherpaOnnxCreateOfflineRecognizer(&c));
} catch (...) { return nullptr; }
char *prism_asr_decode(void *p, const float *samples, int n, int rate) try {
    const auto *r = static_cast<SherpaOnnxOfflineRecognizer *>(p);
    const auto stream = std::unique_ptr<const SherpaOnnxOfflineStream, decltype(&SherpaOnnxDestroyOfflineStream)>(SherpaOnnxCreateOfflineStream(r), SherpaOnnxDestroyOfflineStream);
    const auto *s = stream.get();
    if (!s) return nullptr;
    SherpaOnnxAcceptWaveformOffline(s, rate, samples, n);
    SherpaOnnxDecodeOfflineStream(r, s);
    const auto snapshot = std::unique_ptr<const SherpaOnnxOfflineRecognizerResult, decltype(&SherpaOnnxDestroyOfflineRecognizerResult)>(SherpaOnnxGetOfflineStreamResult(s), SherpaOnnxDestroyOfflineRecognizerResult);
    const auto *result = snapshot.get();
    char *out = result && result->text ? strdup(result->text) : nullptr;
    return out;
} catch (...) { return nullptr; }
void prism_asr_destroy(void *p) { if (p) SherpaOnnxDestroyOfflineRecognizer(static_cast<SherpaOnnxOfflineRecognizer *>(p)); }
void *prism_tts_create(const char *duration, const char *encoder, const char *vector, const char *vocoder, const char *json, const char *index, const char *voice, int threads) try {
    SherpaOnnxOfflineTtsConfig c{};
    c.model.num_threads = threads; c.model.provider = "cpu"; c.max_num_sentences = 1;
    c.model.supertonic = {duration, encoder, vector, vocoder, json, index, voice};
    return const_cast<SherpaOnnxOfflineTts *>(SherpaOnnxCreateOfflineTts(&c));
} catch (...) { return nullptr; }
prism_audio prism_tts_generate(void *p, const char *text, int speaker, float speed, int steps, const char *extra) try {
    SherpaOnnxGenerationConfig c{}; c.sid = speaker; c.speed = speed; c.num_steps = steps;
    c.silence_scale = 0.2f; c.extra = extra;
    const auto *a = SherpaOnnxOfflineTtsGenerateWithConfig(static_cast<SherpaOnnxOfflineTts *>(p), text, &c, nullptr, nullptr);
    if (!a) return {nullptr, 0, 0, nullptr};
    return {a->samples, a->n, a->sample_rate, const_cast<SherpaOnnxGeneratedAudio *>(a)};
} catch (...) { return {nullptr, 0, 0, nullptr}; }
void prism_tts_destroy(void *p) { if (p) SherpaOnnxDestroyOfflineTts(static_cast<SherpaOnnxOfflineTts *>(p)); }
void prism_audio_destroy(prism_audio a) { if (a.owner) SherpaOnnxDestroyOfflineTtsGeneratedAudio(static_cast<SherpaOnnxGeneratedAudio *>(a.owner)); }
void *prism_vad_create(const char *path, float silence) try {
    SherpaOnnxVadModelConfig c{}; c.sample_rate = 16000; c.num_threads = 1; c.provider = "cpu";
    c.silero_vad.model = path; c.silero_vad.threshold = 0.5f;
    c.silero_vad.min_silence_duration = silence; c.silero_vad.min_speech_duration = 0.25f;
    c.silero_vad.max_speech_duration = 20; c.silero_vad.window_size = 512;
    return const_cast<SherpaOnnxVoiceActivityDetector *>(SherpaOnnxCreateVoiceActivityDetector(&c, 30));
} catch (...) { return nullptr; }
int prism_vad_accept(void *p, const float *s, int n) try { SherpaOnnxVoiceActivityDetectorAcceptWaveform(static_cast<SherpaOnnxVoiceActivityDetector *>(p), s, n); return 1; } catch (...) { return 0; }
int prism_vad_speaking(void *p) try { return SherpaOnnxVoiceActivityDetectorDetected(static_cast<SherpaOnnxVoiceActivityDetector *>(p)); } catch (...) { return -1; }
prism_audio prism_vad_pop(void *p) try {
    auto *v = static_cast<SherpaOnnxVoiceActivityDetector *>(p);
    if (SherpaOnnxVoiceActivityDetectorEmpty(v)) return {nullptr, 0, 16000, nullptr};
    const auto *s = SherpaOnnxVoiceActivityDetectorFront(v);
    float *copy = static_cast<float *>(malloc(sizeof(float) * s->n));
    if (copy) memcpy(copy, s->samples, sizeof(float) * s->n);
    const int n = s->n;
    SherpaOnnxDestroySpeechSegment(s); SherpaOnnxVoiceActivityDetectorPop(v);
    return {copy, copy ? n : 0, 16000, nullptr};
} catch (...) { return {nullptr, -1, 16000, nullptr}; }
int prism_vad_flush(void *p) try { SherpaOnnxVoiceActivityDetectorFlush(static_cast<SherpaOnnxVoiceActivityDetector *>(p)); return 1; } catch (...) { return 0; }
void prism_vad_destroy(void *p) { if (p) SherpaOnnxDestroyVoiceActivityDetector(static_cast<SherpaOnnxVoiceActivityDetector *>(p)); }
}
