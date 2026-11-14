"""Downloads the catalog's music files and verifies real native WAV generation."""
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys
import urllib.request
import wave


def checksum(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def download(entry, root):
    path = root / entry["fileName"]
    if path.is_file() and path.stat().st_size == entry["sizeBytes"] and checksum(path) == entry["sha256"]:
        return str(path)
    temporary = path.with_suffix(".part")
    for attempt in range(5):
        try:
            request = urllib.request.Request(entry["url"], headers={"User-Agent": "prism-ai-ci"})
            with urllib.request.urlopen(request, timeout=300) as response, temporary.open("wb") as stream:
                while block := response.read(1024 * 1024):
                    stream.write(block)
            if temporary.stat().st_size != entry["sizeBytes"] or checksum(temporary) != entry["sha256"]:
                raise OSError("Model size or SHA-256 mismatch")
            temporary.replace(path)
            return str(path)
        except OSError:
            if attempt == 4:
                raise


def main():
    binary = Path(sys.argv[1]).resolve()
    root = Path(sys.argv[2]).resolve()
    root.mkdir(parents=True, exist_ok=True)
    catalog_path = Path(__file__).resolve().parent.parent / "shared/src/commonMain/composeResources/files/models.json"
    model = next(model for model in json.loads(catalog_path.read_text(encoding="utf-8"))["models"] if model["id"] == "prism-music-1")
    command = {"model": download(model, root)}
    for component in model["components"]:
        command[component["role"]] = download(component, root)
    output = root / "Проверка музыки.wav"
    command.update(caption="Calm solo piano instrumental, gentle melody", lyrics="[Instrumental]", duration=5, seed=42, output=str(output))
    with (root / "music-worker.log").open("w", encoding="utf-8") as errors:
        result = subprocess.run([str(binary)], input=json.dumps(command, ensure_ascii=False) + "\n",
                                encoding="utf-8", stdout=subprocess.PIPE, stderr=errors, timeout=1800)
    print(result.stdout)
    if result.returncode != 0 or not any(json.loads(line).get("event") == "done" for line in result.stdout.splitlines() if line.startswith("{")):
        raise RuntimeError("Music worker failed, see music-worker.log")
    with wave.open(str(output), "rb") as audio:
        assert (audio.getnchannels(), audio.getsampwidth(), audio.getframerate()) == (2, 2, 48000)
        seconds = audio.getnframes() / audio.getframerate()
        assert 4 <= seconds <= 7, seconds
        samples = audio.readframes(audio.getnframes())
    energy = sum(value[0] ** 2 for value in struct.iter_unpack("<h", samples)) / (len(samples) // 2)
    assert energy > 1, "Silent output"
    print(f"Stereo PCM16 WAV verified, {seconds:.2f} seconds, RMS {energy ** 0.5:.1f}")


if __name__ == "__main__":
    main()
