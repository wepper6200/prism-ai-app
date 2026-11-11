"""Скачивает модели речи для тестов CI по каталогу (адреса и SHA-256 — из models.json) и печатает
переменные окружения PRISM_TEST_* в формате GITHUB_ENV. Уже скачанные файлы с верной суммой не качаются.

python3 .github/download_speech_models.py <папка> [--only vad]
"""
import hashlib
import json
import os
import sys
import urllib.request

CATALOG = os.path.join(os.path.dirname(__file__), "..", "shared", "src", "commonMain", "composeResources", "files", "models.json")


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch(url, size, sha, path):
    if os.path.isfile(path) and os.path.getsize(path) == size and sha256(path) == sha:
        return path
    os.makedirs(os.path.dirname(path), exist_ok=True)
    for attempt in range(5):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "prism-ai-ci"})
            with urllib.request.urlopen(request, timeout=300) as response, open(path + ".part", "wb") as out:
                while chunk := response.read(1 << 20):
                    out.write(chunk)
            break
        except OSError as e:
            print(f"retry {attempt + 1}: {url}: {e}", file=sys.stderr)
    actual = sha256(path + ".part")
    if actual != sha:
        sys.exit(f"SHA-256 mismatch for {url}: {actual} != {sha}")
    os.replace(path + ".part", path)
    return path


def files(model):
    """Основной файл и компоненты модели: (назначение, адрес, размер, sha256)."""
    yield model.get("mainRole", "model"), model["url"], model["sizeBytes"], model["sha256"]
    for c in model.get("components", []):
        yield c["role"], c["url"], c["sizeBytes"], c["sha256"]


def main():
    root = os.path.abspath(sys.argv[1])
    only_vad = "--only" in sys.argv and sys.argv[sys.argv.index("--only") + 1] == "vad"
    models = {m["id"]: m for m in json.load(open(CATALOG, encoding="utf-8"))["models"]}
    env = {}
    gigaam = models["gigaam-v3-ctc-punct-ru-int8"]
    for role, url, size, sha in files(gigaam):
        if role == "vad":
            env["PRISM_TEST_VAD"] = fetch(url, size, sha, os.path.join(root, "silero_vad.onnx"))
        elif not only_vad:
            fetch(url, size, sha, os.path.join(root, "gigaam", url.rsplit("/", 1)[1]))
    if not only_vad:
        env["PRISM_TEST_GIGAAM_DIR"] = os.path.join(root, "gigaam")
        for role, url, size, sha in files(models["whisper-small-int8"]):
            if role != "vad":
                fetch(url, size, sha, os.path.join(root, "whisper", url.rsplit("/", 1)[1]))
        env["PRISM_TEST_WHISPER_DIR"] = os.path.join(root, "whisper")
        voice = models["piper-ru-dmitri-medium"]
        env["PRISM_TEST_PIPER_ARCHIVE"] = fetch(voice["url"], voice["sizeBytes"], voice["sha256"], os.path.join(root, voice["fileName"]))
    for name, value in env.items():
        print(f"{name}={value}")


main()
