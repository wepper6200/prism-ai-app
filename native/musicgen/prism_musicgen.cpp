// One request per process. stdout carries JSON events, diagnostics go to stderr.
#include "model-store.h"
#include "pipeline-lm.h"
#include "pipeline-synth.h"
#include "yyjson.h"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif

static std::string string_field(yyjson_val * root, const char * name) {
    const char * value = yyjson_get_str(yyjson_obj_get(root, name));
    return value ? value : "";
}

static void stage(const char * name) {
    std::cout << "{\"event\":\"progress\",\"stage\":\"" << name << "\"}" << std::endl;
}

static void write_u32(std::ostream & out, uint32_t value, int bytes = 4) {
    for (int i = 0; i < bytes; ++i) out.put(static_cast<char>((value >> (8 * i)) & 255));
}

static void write_audio(const std::string & path, const AceAudio & audio, int samples) {
    if (!audio.samples || samples <= 0 || audio.n_samples < samples || audio.sample_rate != 48000) {
        throw std::runtime_error("Empty audio");
    }
    std::ofstream out(std::filesystem::u8path(path), std::ios::binary | std::ios::trunc);
    if (!out) throw std::runtime_error("Cannot create output");
    const uint32_t size = static_cast<uint32_t>(samples) * 4;
    out.write("RIFF", 4); write_u32(out, size + 36); out.write("WAVEfmt ", 8);
    write_u32(out, 16); write_u32(out, 1, 2); write_u32(out, 2, 2);
    write_u32(out, 48000); write_u32(out, 192000); write_u32(out, 4, 2); write_u32(out, 16, 2);
    out.write("data", 4); write_u32(out, size);
    float peak = 1.0f;
    for (int i = 0; i < audio.n_samples * 2; ++i) {
        if (!std::isfinite(audio.samples[i])) throw std::runtime_error("Invalid audio");
        // Upstream Windows headers define a function-like max macro.
        peak = (std::max)(peak, std::abs(audio.samples[i]));
    }
    for (int i = 0; i < samples; ++i) {
        for (int channel = 0; channel < 2; ++channel) {
            const float sample = audio.samples[channel * audio.n_samples + i] / peak;
            const auto pcm = static_cast<int16_t>(std::lround(sample * 32767.0f));
            write_u32(out, static_cast<uint16_t>(pcm), 2);
        }
    }
    out.close();
    if (!out) throw std::runtime_error("Cannot write output");
}

int main() {
#ifdef _WIN32
    _setmode(_fileno(stdin), _O_BINARY);
    _setmode(_fileno(stdout), _O_BINARY);
    // Third-party file readers use narrow paths. The UTF-8 process manifest
    // supplied by CMake enables Unicode paths on Windows 10 1903+.
#endif
    try {
        std::string line;
        if (!std::getline(std::cin, line)) return 1;
        std::unique_ptr<yyjson_doc, decltype(&yyjson_doc_free)> doc(
            yyjson_read(line.data(), line.size(), 0), yyjson_doc_free);
        if (!doc) throw std::runtime_error("Invalid request");
        auto * root = yyjson_doc_get_root(doc.get());
        const auto lm_path = string_field(root, "lm");
        const auto text_path = string_field(root, "text_encoder");
        const auto dit_path = string_field(root, "model");
        const auto vae_path = string_field(root, "vae");
        const auto output = string_field(root, "output");
        AceRequest request;
        request_init(&request);
        if (!request_parse_json(&request, line.c_str()) || request.caption.empty() || output.empty() ||
            lm_path.empty() || text_path.empty() || dit_path.empty() || vae_path.empty() ||
            !std::isfinite(request.duration) || request.duration < 10 || request.duration > 600) {
            throw std::runtime_error("Invalid request parameters");
        }
        request.lm_batch_size = 1;
        request.synth_batch_size = 1;
        request.task_type = "text2music";
        request.use_cot_caption = false;
        request_resolve_seed(&request);
        request_resolve_lm_seed(&request);
        std::unique_ptr<ModelStore, decltype(&store_free)> store(store_create(EVICT_STRICT), store_free);
        if (!store) throw std::bad_alloc();
        stage("composing");
        AceLmParams lm_params;
        ace_lm_default_params(&lm_params);
        lm_params.model_path = lm_path.c_str();
        // A ten-minute plan alone contains about 3000 acoustic tokens.
        lm_params.max_seq = request.lyrics.size() > 20000 ? 16384 :
            (request.duration > 120 || request.lyrics.size() > 8000 ? 8192 : 4096);
        lm_params.max_batch = 1;
        std::unique_ptr<AceLm, decltype(&ace_lm_free)> lm(ace_lm_load(store.get(), &lm_params), ace_lm_free);
        if (!lm) throw std::runtime_error("Cannot load composition model");
        AceRequest enriched;
        // Preserve the prepared lyrics and generate acoustic codes as well as metadata.
        const int mode = LM_MODE_GENERATE;
        if (ace_lm_generate(lm.get(), &request, 1, &enriched, nullptr, nullptr, nullptr, nullptr, mode) != 0) {
            throw std::runtime_error("Composition failed");
        }
        lm.reset();
        enriched.duration = request.duration;
        enriched.caption = request.caption;
        if (!request.lyrics.empty()) enriched.lyrics = request.lyrics;
        enriched.vocal_language = request.vocal_language;
        // Keep short plans: the context builder pads them to the requested duration.
        const int required_codes = static_cast<int>(std::lround(request.duration * 5.0f));
        const int code_count = enriched.audio_codes.empty() ? 0 :
            1 + static_cast<int>(std::count(enriched.audio_codes.begin(), enriched.audio_codes.end(), ','));
        std::cerr << "[Music] Acoustic codes: " << code_count << "/" << required_codes << std::endl;
        if (code_count == 0) throw std::runtime_error("Composition produced no acoustic plan");
        if (code_count > required_codes) {
            size_t cut = 0;
            for (int i = 0; i < required_codes; ++i) cut = enriched.audio_codes.find(',', cut) + 1;
            enriched.audio_codes.resize(cut - 1);
        }
        enriched.inference_steps = 8;
        stage("synthesizing");
        AceSynthParams synth_params;
        ace_synth_default_params(&synth_params);
        synth_params.text_encoder_path = text_path.c_str();
        synth_params.dit_path = dit_path.c_str();
        synth_params.vae_path = vae_path.c_str();
        std::unique_ptr<AceSynth, decltype(&ace_synth_free)> synth(
            ace_synth_load(store.get(), &synth_params), ace_synth_free);
        if (!synth) throw std::runtime_error("Cannot load synthesis model");
        std::unique_ptr<AceSynthJob, decltype(&ace_synth_job_free)> job(
            ace_synth_job_run_dit(synth.get(), &enriched, nullptr, 0, nullptr, 0, nullptr, 0, nullptr, 0, 1),
            ace_synth_job_free);
        if (!job) throw std::runtime_error("Synthesis failed");
        stage("decoding");
        AceAudio audio{};
        if (ace_synth_job_run_vae(synth.get(), job.get(), &audio) != 0) {
            ace_audio_free(&audio);
            throw std::runtime_error("Audio decoding failed");
        }
        // DiT rounds its latent length up to a patch boundary. Remove that padding.
        try { write_audio(output, audio, static_cast<int>(std::lround(request.duration * 48000.0f))); }
        catch (...) { ace_audio_free(&audio); throw; }
        ace_audio_free(&audio);
        std::cout << "{\"event\":\"done\",\"seed\":" << request.seed << "}" << std::endl;
        return 0;
    } catch (const std::bad_alloc &) {
        std::cout << "{\"event\":\"error\",\"code\":\"memory\"}" << std::endl;
    } catch (const std::exception & error) {
        std::cerr << error.what() << std::endl;
        std::cout << "{\"event\":\"error\",\"code\":\"failed\"}" << std::endl;
    }
    return 1;
}
