#include "prism_archive.h"
#include <bzlib.h>
#include <zlib.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <string>
#include <vector>
#include <algorithm>
namespace fs = std::filesystem;
namespace {
bool safe(const fs::path &p) {
    if (p.empty() || p.is_absolute()) return false;
    for (const auto &part : p) if (part == ".." || part.string().find('\\') != std::string::npos) return false;
    return true;
}
uint32_t le(const unsigned char *p, int n) { uint32_t v = 0; for (int i = 0; i < n; ++i) v |= uint32_t(p[i]) << (8 * i); return v; }
}
extern "C" int prism_unpack_voice(const char *archive, const char *destination) {
    FILE *f = fopen(archive, "rb"); if (!f) return 0;
    int error = BZ_OK; BZFILE *bz = BZ2_bzReadOpen(&error, f, 0, 0, nullptr, 0);
    if (!bz || error != BZ_OK) { fclose(f); return 0; }
    const fs::path target(destination), temp(std::string(destination) + ".unpacking");
    bool ok = false;
    auto read = [&](void *data, size_t size) {
        auto *out = static_cast<unsigned char *>(data);
        while (size) { int n = BZ2_bzRead(&error, bz, out, int(std::min(size, size_t(65536))));
            if (n <= 0 || (error != BZ_OK && error != BZ_STREAM_END)) return false;
            out += n; size -= size_t(n);
        } return true;
    };
    try {
        fs::remove_all(temp); fs::create_directories(temp);
        char header[512]; uint64_t total = 0; int entries = 0;
        while (read(header, sizeof(header))) {
            if (std::all_of(header, header + 512, [](char c) { return c == 0; })) { ok = true; break; }
            if (++entries > 50000) break;
            unsigned checksum = 0;
            for (int i = 0; i < 512; ++i) checksum += (i >= 148 && i < 156) ? 32 : static_cast<unsigned char>(header[i]);
            const std::string stored(header + 148, 8);
            if (checksum != std::strtoul(stored.c_str(), nullptr, 8)) break;
            std::string name(header, strnlen(header, 100));
            // GNU tar uses this area for timestamps, not a POSIX path prefix.
            const std::string prefix = std::memcmp(header + 257, "ustar\0", 6) == 0
                ? std::string(header + 345, strnlen(header + 345, 155)) : std::string();
            if (!prefix.empty()) name = prefix + "/" + name;
            const fs::path relative(name);
            if (!safe(relative)) break;
            const std::string sizeText(header + 124, 12);
            const uint64_t size = std::strtoull(sizeText.c_str(), nullptr, 8);
            total += size;
            if (size > (4ULL << 30) || total > (8ULL << 30)) break;
            const char type = header[156];
            if (type != '0' && type != 0 && type != '5') break; // No links, devices, PAX paths or sparse files.
            const fs::path output = temp / relative;
            if (type == '5') { if (size) break; fs::create_directories(output); continue; }
            fs::create_directories(output.parent_path());
            FILE *out = fopen(output.c_str(), "wb"); if (!out) break;
            uint64_t left = size; char buffer[65536]; bool copied = true;
            while (left) { const size_t n = size_t(std::min(left, uint64_t(sizeof(buffer))));
                if (!read(buffer, n) || fwrite(buffer, 1, n, out) != n) { copied = false; break; } left -= n;
            }
            if (fclose(out) != 0) copied = false;
            if (!copied) break;
            const size_t padding = size_t((512 - size % 512) % 512);
            if (padding && !read(buffer, padding)) break;
        }
        if (ok) { fs::remove_all(target); fs::rename(temp, target); }
        else fs::remove_all(temp);
    } catch (...) { ok = false; std::error_code e; fs::remove_all(temp, e); }
    BZ2_bzReadClose(&error, bz); fclose(f); return ok ? 1 : 0;
}
extern "C" void *prism_docx_xml(const void *data, size_t length, size_t *output_length) {
    if (!data || !output_length || length < 22) return nullptr;
    const auto *bytes = static_cast<const unsigned char *>(data);
    size_t end = length - 22;
    while (le(bytes + end, 4) != 0x06054b50) { if (!end || length - end > 65557) return nullptr; --end; }
    const size_t directory = le(bytes + end + 16, 4); size_t cursor = directory;
    const unsigned count = le(bytes + end + 10, 2);
    for (unsigned i = 0; i < count; ++i) {
        if (cursor > length || length - cursor < 46 || le(bytes + cursor, 4) != 0x02014b50) return nullptr;
        const auto *h = bytes + cursor;
        const size_t names = le(h + 28, 2), extra = le(h + 30, 2), comment = le(h + 32, 2);
        if (46 + names + extra + comment > length - cursor) return nullptr;
        const std::string name(reinterpret_cast<const char *>(h + 46), names);
        cursor += 46 + names + extra + comment;
        if (name != "word/document.xml") continue;
        const size_t compressed = le(h + 20, 4), size = le(h + 24, 4), offset = le(h + 42, 4);
        const unsigned method = le(h + 10, 2);
        if ((le(h + 8, 2) & 1) || !size || size > 32 * 1024 * 1024 || offset > length || length - offset < 30 || le(bytes + offset, 4) != 0x04034b50) return nullptr;
        const size_t start = offset + 30 + le(bytes + offset + 26, 2) + le(bytes + offset + 28, 2);
        if (start > length || compressed > length - start) return nullptr;
        auto *out = static_cast<unsigned char *>(malloc(size)); if (!out) return nullptr;
        bool ok = false;
        if (method == 0 && compressed == size) { memcpy(out, bytes + start, size); ok = true; }
        else if (method == 8) {
            z_stream stream{}; stream.next_in = const_cast<unsigned char *>(bytes + start); stream.avail_in = uInt(compressed);
            stream.next_out = out; stream.avail_out = uInt(size);
            if (inflateInit2(&stream, -MAX_WBITS) == Z_OK) { ok = inflate(&stream, Z_FINISH) == Z_STREAM_END && stream.total_out == size; inflateEnd(&stream); }
        }
        if (!ok || crc32(0, out, uInt(size)) != le(h + 16, 4)) { free(out); return nullptr; }
        *output_length = size; return out;
    }
    return nullptr;
}
