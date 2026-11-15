#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct { const float *samples; int count; int sample_rate; void *owner; } prism_audio;
void *prism_asr_create(const char *engine, const char *model, const char *decoder, const char *tokens, const char *language, int threads);
char *prism_asr_decode(void *, const float *, int, int);
void prism_asr_destroy(void *);
void *prism_tts_create(const char *duration, const char *encoder, const char *vector, const char *vocoder, const char *json, const char *index, const char *voice, int threads);
prism_audio prism_tts_generate(void *, const char *, int, float, int, const char *extra);
void prism_tts_destroy(void *);
void prism_audio_destroy(prism_audio);
void *prism_vad_create(const char *, float);
int prism_vad_accept(void *, const float *, int);
int prism_vad_speaking(void *);
prism_audio prism_vad_pop(void *);
int prism_vad_flush(void *);
void prism_vad_destroy(void *);
#ifdef __cplusplus
}
#endif
