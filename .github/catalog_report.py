"""Отчёт о файлах моделей на Hugging Face для каталога: размер, SHA-256, лицензия, доступ без авторизации."""
import hashlib
import json
import tarfile
import tempfile
import urllib.parse
import urllib.request

HEADERS = {"User-Agent": "prism-ai-ci"}


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=60) as r:
        return json.load(r)


def search(query, limit=15):
    print(f"\n### search '{query}':")
    try:
        found = get("https://huggingface.co/api/models?search=" + urllib.parse.quote(query)
                    + f"&sort=downloads&direction=-1&limit={limit}")
        for m in found:
            print(f'  {m["id"]}  downloads={m.get("downloads")}')
    except Exception as e:  # noqa: BLE001 — отчёт продолжает работу
        print("  ERROR", e)


def repo(name, suffixes=(".gguf", ".safetensors"), readme=1500):
    print("\n=== " + name)
    try:
        info = get(f"https://huggingface.co/api/models/{name}")
        card = info.get("cardData") or {}
        print("gated:", info.get("gated"), "| license:", card.get("license"), card.get("license_name"),
              card.get("license_link"))
        for f in get(f"https://huggingface.co/api/models/{name}/tree/main?recursive=true"):
            if f.get("type") == "file" and f["path"].endswith(suffixes):
                lfs = f.get("lfs") or {}
                print(f'{f["path"]}\t{lfs.get("size", f.get("size"))}\t{lfs.get("oid")}')
        if readme:
            with urllib.request.urlopen(urllib.request.Request(f"https://huggingface.co/{name}/raw/main/README.md",
                                                               headers=HEADERS), timeout=60) as r:
                print("--- README ---\n" + r.read().decode("utf-8", "replace")[:readme])
    except Exception as e:  # noqa: BLE001
        print("ERROR", e)


def tree(name):
    """Все файлы репозитория: LFS — размер и SHA-256 из метаданных, мелкие — размер и SHA-256 по содержимому."""
    print("\n=== " + name)
    try:
        info = get(f"https://huggingface.co/api/models/{name}")
        card = info.get("cardData") or {}
        print("gated:", info.get("gated"), "| license:", card.get("license"), card.get("license_name"),
              card.get("license_link"))
        files = [f for f in get(f"https://huggingface.co/api/models/{name}/tree/main?recursive=true")
                 if f.get("type") == "file"]
        print("files:", len(files))
        for f in files[:60]:
            lfs = f.get("lfs")
            if lfs:
                print(f'{f["path"]}\t{lfs.get("size")}\t{lfs.get("oid")}')
            elif f["path"].endswith((".txt", ".json", ".md")) and f.get("size", 0) < 2_000_000:
                size, sha = digest(f"https://huggingface.co/{name}/resolve/main/{f['path']}")
                print(f'{f["path"]}\t{size}\t{sha}')
            else:
                print(f'{f["path"]}\t{f.get("size")}\t(git)')
    except Exception as e:  # noqa: BLE001
        print("ERROR", e)


def digest(url, listing=False):
    """Скачивает файл и считает размер и SHA-256; для архива tar.bz2 печатает его содержимое."""
    sha = hashlib.sha256()
    size = 0
    with tempfile.NamedTemporaryFile() as tmp:
        with urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=300) as r:
            while chunk := r.read(1 << 20):
                sha.update(chunk)
                tmp.write(chunk)
                size += len(chunk)
        tmp.flush()
        if listing:
            with tarfile.open(tmp.name, "r:bz2") as tar:
                members = tar.getmembers()
                print(f"  archive entries: {len(members)}, unpacked bytes: {sum(m.size for m in members)}")
                for m in members[:40]:
                    print(f"    {m.name}\t{m.size}")
    return size, sha.hexdigest()


def asset(url, listing=False):
    print("\n=== " + url)
    try:
        size, sha = digest(url, listing)
        print(f"{url.rsplit('/', 1)[-1]}\t{size}\t{sha}")
    except Exception as e:  # noqa: BLE001
        print("ERROR", e)


# Этап «Голос»: распознавание речи (GigaAM v3, Whisper), детектор речи Silero VAD, озвучка Piper (русские голоса).
for q in ["sherpa-onnx giga-am v3", "sherpa-onnx-whisper", "silero vad onnx", "vits-piper-ru_RU"]:
    search(q)
tree("csukuangfj/sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16")
tree("csukuangfj/sherpa-onnx-whisper-small")
tree("csukuangfj/sherpa-onnx-whisper-turbo")
for voice in ["irina", "denis", "dmitri", "ruslan"]:
    tree(f"csukuangfj/vits-piper-ru_RU-{voice}-medium")
GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
asset(f"{GH}/asr-models/silero_vad.onnx")
asset(f"{GH}/tts-models/espeak-ng-data.tar.bz2", listing=True)
asset(f"{GH}/tts-models/vits-piper-ru_RU-irina-medium.tar.bz2", listing=True)
# Библиотеки sherpa-onnx 2.0.1 (Android AAR, Java API, нативные библиотеки Windows/Linux).
for name in ["sherpa-onnx-2.0.1.aar", "sherpa-onnx-jvm-2.0.1.jar", "sherpa-onnx-native-lib-win-x64-2.0.1.jar",
             "sherpa-onnx-native-lib-linux-x64-2.0.1.jar"]:
    asset(f"{GH}/v2.0.1/{name}")
