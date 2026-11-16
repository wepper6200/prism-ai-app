"""Exercise the real packaged converter, using a tiny BF16 tensor and the app's type names."""
import argparse
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile


def gguf(path):
    data = path.read_bytes()
    position = 0

    def read(fmt):
        nonlocal position
        size = struct.calcsize("<" + fmt)
        value = struct.unpack_from("<" + fmt, data, position)[0]
        position += size
        return value

    def string():
        nonlocal position
        size = read("Q")
        value = data[position:position + size].decode("utf-8")
        position += size
        return value

    def value(kind):
        formats = {0: "B", 1: "b", 2: "H", 3: "h", 4: "I", 5: "i", 6: "f", 7: "?", 10: "Q", 11: "q", 12: "d"}
        if kind == 8:
            return string()
        if kind == 9:
            item_type, count = read("I"), read("Q")
            return [value(item_type) for _ in range(count)]
        return read(formats[kind])

    assert data[:4] == b"GGUF", path
    position = 4
    assert read("I") in (2, 3)
    tensor_count, metadata_count = read("Q"), read("Q")
    assert tensor_count == 1
    metadata = {}
    for _ in range(metadata_count):
        key = string()
        metadata[key] = value(read("I"))
    name = string()
    shape = [read("Q") for _ in range(read("I"))]
    kind, offset = read("I"), read("Q")
    alignment = metadata.get("general.alignment", 32)
    start = (position + alignment - 1) // alignment * alignment + offset
    return name, shape, kind, data[start:]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", required=True, type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    enum = (repo / "shared/src/commonMain/kotlin/ai/prism/domain/model/TextEncoderMode.kt").read_text(encoding="utf-8")
    modes = {mode: re.search(r'\b' + mode + r'\("([^"]+)"\)', enum).group(1) for mode in ("Q4", "Q8")}
    with tempfile.TemporaryDirectory(prefix="prism-encoder-") as folder:
        folder = Path(folder)
        original = folder / "tiny-bf16.safetensors"
        header = json.dumps({"test.weight": {"dtype": "BF16", "shape": [2, 256], "data_offsets": [0, 1024]}}).encode()
        header += b" " * (-len(header) % 8)
        original.write_bytes(struct.pack("<Q", len(header)) + header + struct.pack("<512H", *([0x3F80] * 512)))
        commands = []
        for mode, kind in modes.items():
            target = folder / (mode + ".gguf")
            decoded = folder / (mode + "-f32.gguf")
            commands += [
                {"cmd": "convert", "input": str(original), "output": str(target), "type": kind},
                {"cmd": "convert", "input": str(target), "output": str(decoded), "type": "f32"},
            ]
        commands.append({"cmd": "quit"})
        result = subprocess.run([str(args.binary.resolve())], input="\n".join(map(json.dumps, commands)) + "\n",
                                text=True, encoding="utf-8", capture_output=True, timeout=90,
                                creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0)
        assert result.returncode == 0, (result.returncode, result.stdout, result.stderr)
        events = [json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")]
        assert not [event for event in events if event.get("event") == "error"], events
        assert sum(event.get("event") == "converted" for event in events) == 4, events
        for mode, expected_type in (("Q4", 12), ("Q8", 8)):
            name, shape, kind, _ = gguf(folder / (mode + ".gguf"))
            assert (name, shape, kind) == ("test.weight", [256, 2], expected_type), (name, shape, kind)
            assert (folder / (mode + ".gguf")).stat().st_size < original.stat().st_size
            _, _, decoded_type, decoded = gguf(folder / (mode + "-f32.gguf"))
            assert decoded_type == 0
            assert all(abs(number - 1) < 0.05 for number in struct.unpack("<512f", decoded[:2048]))
    print("OK: actual BF16 -> Q4_K/Q8_0 -> F32, native type names, GGUF tensors and restored values")


if __name__ == "__main__":
    main()
