// Prism AI: генерация изображений (stable-diffusion.cpp) в отдельном процессе.
//
// Протокол — строки JSON в UTF-8: команды приходят в stdin, события уходят в stdout.
// Пути передаются через stdin, а не аргументами: на Windows аргументы приходят в кодировке ANSI
// и ломают пути с кириллицей.
//
// Команды:
//   {"cmd":"load","diffusion_model":"…","model":"…","t5xxl":"…","clip_l":"…","clip_g":"…","vae":"…","taesd":"…",
//    "threads":4,"vae_tiling":false,"mmap":false}
//   {"cmd":"generate","prompt":"…","negative":"","width":1024,"height":1024,"steps":4,"cfg":1.0,
//    "guidance":3.5,"seed":-1,"sampler":"euler","scheduler":"","output":"…/image.png"}
//   {"cmd":"cancel"}   — остановить текущую генерацию
//   {"cmd":"quit"}
// События:
//   {"event":"started"}                          — процесс запущен и ждёт команд
//   {"event":"loaded","device":"…"}              — модель загружена
//   {"event":"progress","step":1,"steps":4}
//   {"event":"done","output":"…","width":…,"height":…,"seed":…}
//   {"event":"error","code":"…","message":"…"}  — коды: bad_request, load_failed, out_of_memory,
//                                                  generation_failed, cancelled, write_failed, busy

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <exception>
#include <filesystem>
#include <fstream>
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
    std::string model, diffusion_model, t5xxl, clip_l, clip_g, vae, taesd;
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
                       str(request, "clip_g"), str(request, "vae"), str(request, "taesd")};
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
        params.enable_mmap = request.value("mmap", false);
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

    void generate(const json & request) {
        if (ctx_ == nullptr) return emit_error("bad_request", "model is not loaded");
        if (busy_.exchange(true)) return emit_error("busy", "generation in progress");
        join();
        thread_ = std::thread([this, request]() {
            run(request);
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

    bool cancel_requested() const {
        const std::string error = last_error();
        return error.find("cancel") != std::string::npos || cancelled_.load();
    }

    sd_ctx_t * ctx_ = nullptr;
    Paths paths_;
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
        } else if (cmd == "generate") {
            worker.mark_cancelled(false);
            worker.generate(request);
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
