"""Отчёт о файлах моделей на Hugging Face для каталога: размер, SHA-256, лицензия, доступ без авторизации."""
import json
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


# Этап «Файлы»: модель эмбеддингов (семейство Gemma).
for q in ["embeddinggemma gguf", "embeddinggemma"]:
    search(q)
repo("ggml-org/embeddinggemma-300M-GGUF")
repo("ggml-org/embeddinggemma-300M-qat-q4_0-GGUF", readme=0)

# Этап «Видео»: Wan 2.2 TI2V 5B (Windows) и Wan 2.1 1.3B (мощные телефоны), энкодер umt5-xxl и VAE.
for q in ["Wan2.2-TI2V-5B gguf", "Wan2.1-T2V-1.3B gguf", "umt5-xxl gguf", "Wan2.2 vae", "Wan_2.1_ComfyUI_repackaged"]:
    search(q)
repo("QuantStack/Wan2.2-TI2V-5B-GGUF")
repo("city96/Wan2.1-T2V-1.3B-gguf")
repo("samuelchristlie/Wan2.1-T2V-1.3B-GGUF", readme=0)
repo("city96/umt5-xxl-encoder-gguf")
repo("Comfy-Org/Wan_2.2_ComfyUI_Repackaged", suffixes=(".safetensors",), readme=0)
repo("Comfy-Org/Wan_2.1_ComfyUI_repackaged", suffixes=(".safetensors",), readme=0)
repo("Wan-AI/Wan2.2-TI2V-5B", readme=2500)
