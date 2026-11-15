#pragma once
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif
int prism_unpack_voice(const char *archive, const char *destination);
void *prism_docx_xml(const void *bytes, size_t length, size_t *output_length);
#ifdef __cplusplus
}
#endif
