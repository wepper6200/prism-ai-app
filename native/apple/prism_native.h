#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef int (*prism_progress_fn)(void *, float);
typedef int (*prism_token_fn)(void *, const int8_t *, int32_t);
typedef int (*prism_prefill_fn)(void *, int32_t, int32_t);
typedef struct {
    const int8_t * pixels;
    int32_t width;
    int32_t height;
    const char * id;
} prism_image;
int prism_backend_init(void);
char * prism_devices(void);
char * prism_last_error(void);
int64_t prism_load_model(const char *, int, prism_progress_fn, void *);
void prism_free_model(int64_t);
char * prism_model_description(int64_t);
int prism_model_context(int64_t);
int64_t prism_create_session(int64_t, int, int, int);
void prism_free_session(int64_t);
int prism_context_size(int64_t);
int prism_token_count(int64_t, int64_t, const int8_t *, int, const int32_t *, int);
void prism_cancel(int64_t);
int64_t prism_load_projector(int64_t, const char *, int, int, int);
void prism_free_projector(int64_t);
int prism_generate(int64_t, int64_t, const int8_t *, int, const prism_image *, int,
                   int, float, float, int, float, float, int,
                   prism_token_fn, prism_prefill_fn, void *);
int64_t prism_create_embedder(int64_t, int, int);
void prism_free_embedder(int64_t);
float * prism_embed(int64_t, const int8_t *, int, int *);
void prism_free(void *);
#ifdef __cplusplus
}
#endif
