#include "prism_native.h"
#include <cassert>
#include <cmath>
#include <cstring>
#include <iostream>
#include <string>

struct Output { std::string text; bool stop = false; int prefilled = 0; };
static int token(void * context, const int8_t * data, int size) {
    auto * out = static_cast<Output *>(context);
    out->text.append(reinterpret_cast<const char *>(data), size);
    return out->stop ? 0 : 1;
}
static int prefill(void * context, int done, int total) {
    assert(done >= 0 && done <= total);
    static_cast<Output *>(context)->prefilled = total;
    return 1;
}
static int progress(void *, float value) { assert(value >= 0 && value <= 1); return 1; }
int main(int argc, char ** argv) {
    assert(argc > 1);
    assert(prism_backend_init());
    char * devices = prism_devices();
    assert(devices && std::strlen(devices));
    prism_free(devices);
    auto model = prism_load_model(argv[1], 0, progress, nullptr);
    assert(model);
    auto session = prism_create_session(model, 128, 64, 2);
    assert(session && prism_context_size(session) >= 128);
    const std::string prompt = "Once upon a time";
    const auto * text = reinterpret_cast<const int8_t *>(prompt.data());
    assert(prism_token_count(session, 0, text, static_cast<int>(prompt.size()), nullptr, 0) > 0);
    for (bool stop : {false, true, false}) {
        Output output; output.stop = stop;
        int status = prism_generate(session, 0, text, static_cast<int>(prompt.size()), nullptr, 0,
            16, 0, 0.9f, 40, 0.05f, 1.1f, 42, token, prefill, &output);
        assert(!output.text.empty());
        assert(stop ? status == 2 : status == 0 || status == 1);
        std::cout << "C bridge: " << status << " " << output.text << '\n';
    }
    prism_free_session(session);
    prism_free_model(model);
    assert(!prism_load_model("/missing-prism-model.gguf", 0, nullptr, nullptr));
    char * error = prism_last_error();
    assert(error && std::strlen(error));
    prism_free(error);
    return 0;
}
