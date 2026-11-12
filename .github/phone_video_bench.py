"""Замер видео для телефона на процессоре ARM (задание CI «Phone video benchmark», коммит со словом
[phone-video-bench]).

Собранный под ARMv8.2 (dotprod, fp16 — как в приложении для Android) prism_imagegen снимает ролик 512×288
тремя вариантами и печатает время загрузки и генерации, среднее время шага и пик памяти процесса:

  A. Wan 2.1 T2V 1.3B — модель телефона сейчас (по описанию);
  B. Wan 2.1 Fun InP 1.3B — продолжение от последнего кадра A (кадр + CLIP Vision H), веса сжимаются до q8_0;
  C. Wan 2.2 TI2V 5B Q4_0 — продолжение от того же кадра (модель компьютера).

Для B и C считается, насколько первый кадр совпадает с кадром, от которого шло продолжение (шов при склейке).
Ролики и кадры — в каталоге out/ (артефакт задания).

Использование: python3 phone_video_bench.py <prism_imagegen> <каталог моделей> <out>
"""

import hashlib
import io
import json
import os
import struct
import subprocess
import sys
import threading
import time
import urllib.request

from PIL import Image, ImageChops, ImageStat

CATALOG = "shared/src/commonMain/composeResources/files/models.json"
COMFY_REPO = "Comfy-Org/Wan_2.1_ComfyUI_repackaged"
WIDTH, HEIGHT, FRAMES = 512, 288, 17
PROMPT = "a red sports car driving along a coastal road at sunset, camera follows the car"
CONTINUE_PROMPT = "the red sports car keeps driving along the coastal road at sunset, camera follows the car"


def log(*args):
    print(*args, flush=True)


def download(url, path, sha256=None):
    if os.path.exists(path):
        return path
    started = time.time()
    digest = hashlib.sha256()
    request = urllib.request.Request(url, headers={"User-Agent": "prism-ci"})
    for attempt in range(5):
        try:
            with urllib.request.urlopen(request, timeout=120) as response, open(path + ".part", "wb") as out:
                while True:
                    chunk = response.read(1 << 22)
                    if not chunk:
                        break
                    digest.update(chunk)
                    out.write(chunk)
            break
        except OSError as error:
            log(f"  download failed ({error}), retry {attempt + 1}")
            digest = hashlib.sha256()
            time.sleep(10 * (attempt + 1))
    else:
        raise RuntimeError(f"cannot download {url}")
    if sha256 and digest.hexdigest() != sha256:
        raise RuntimeError(f"sha256 mismatch for {url}")
    os.replace(path + ".part", path)
    log(f"  {os.path.basename(path)}: {os.path.getsize(path) / 2**30:.2f} GiB in {time.time() - started:.0f} s")
    return path


def hf_tree(repo, folder):
    url = f"https://huggingface.co/api/models/{repo}/tree/main/{folder}"
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "prism-ci"}), timeout=60) as r:
        return json.load(r)


def catalog_files(model_id_prefix, models_dir):
    catalog = json.load(open(CATALOG, encoding="utf-8"))
    model = next(m for m in catalog["models"] if m["id"].startswith(model_id_prefix))
    files = {model.get("mainRole", "diffusion_model"): (model["url"], model["fileName"], model["sha256"])}
    for component in model.get("components", []):
        files[component["role"]] = (component["url"], component["fileName"], component["sha256"])
    paths = {}
    for role, (url, name, sha) in files.items():
        paths[role] = download(url, os.path.join(models_dir, name), sha)
    return model, paths


class Worker:
    """prism_imagegen по протоколу JSON-строк; пик памяти — VmHWM процесса."""

    def __init__(self, binary, log_path):
        self.log_file = open(log_path, "w", encoding="utf-8")
        self.process = subprocess.Popen([binary], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=self.log_file, text=True, encoding="utf-8", bufsize=1)
        self.peak_kb = 0
        self.step_times = []
        self._watch = True
        threading.Thread(target=self._watch_memory, daemon=True).start()
        self.expect("started")

    def _watch_memory(self):
        while self._watch and self.process.poll() is None:
            try:
                with open(f"/proc/{self.process.pid}/status") as status:
                    for line in status:
                        if line.startswith("VmHWM:"):
                            self.peak_kb = max(self.peak_kb, int(line.split()[1]))
            except OSError:
                pass
            time.sleep(0.5)

    def send(self, request):
        self.process.stdin.write(json.dumps(request, ensure_ascii=False) + "\n")
        self.process.stdin.flush()

    def expect(self, wanted):
        last_step = time.time()
        while True:
            line = self.process.stdout.readline()
            if not line:
                raise RuntimeError(f"prism_imagegen exited (code {self.process.wait()})")
            event = json.loads(line)
            kind = event.get("event")
            if kind == "progress":
                now = time.time()
                self.step_times.append(now - last_step)
                last_step = now
                log(f"    step {event['step']}/{event['steps']} ({self.step_times[-1]:.1f} s)")
            elif kind == "error":
                raise RuntimeError(f"{event.get('code')}: {event.get('message')}")
            elif kind == wanted:
                return event

    def close(self):
        self._watch = False
        try:
            self.send({"cmd": "quit"})
            self.process.wait(timeout=60)
        except (OSError, subprocess.TimeoutExpired):
            self.process.kill()
        self.log_file.close()


def avi_frames(path):
    """JPEG-кадры MJPEG AVI (чанки 00dc)."""
    data = open(path, "rb").read()
    frames, pos = [], 0
    while True:
        pos = data.find(b"00dc", pos)
        if pos < 0:
            return frames
        size = struct.unpack("<I", data[pos + 4:pos + 8])[0]
        chunk = data[pos + 8:pos + 8 + size]
        if chunk[:2] == b"\xff\xd8":
            frames.append(chunk)
        pos += 8 + size


def difference(jpeg_a, image_b):
    a = Image.open(io.BytesIO(jpeg_a)).convert("RGB")
    b = image_b.convert("RGB").resize(a.size)
    return sum(ImageStat.Stat(ImageChops.difference(a, b)).mean) / 3


def run(name, binary, out, load, generate):
    log(f"== {name}")
    result = {"name": name}
    worker = Worker(binary, os.path.join(out, name.split()[0] + ".log"))
    try:
        started = time.time()
        worker.send({"cmd": "load", "threads": os.cpu_count(), **load})
        worker.expect("loaded")
        result["load_s"] = time.time() - started
        worker.step_times.clear()
        started = time.time()
        worker.send({"cmd": "generate_video", "width": WIDTH, "height": HEIGHT, "frames": FRAMES, "seed": 42,
                     "sampler": "euler", **generate})
        done = worker.expect("done")
        result["generate_s"] = time.time() - started
        result["step_s"] = sum(worker.step_times) / max(1, len(worker.step_times))
        result["frames"] = done.get("frames")
        result["fps"] = done.get("fps")
        result["output"] = done["output"]
    except Exception as error:  # noqa: BLE001 — замер продолжается с другими вариантами
        result["error"] = str(error)
        log(f"   failed: {error}")
    finally:
        worker.close()
        result["peak_gib"] = worker.peak_kb / 2**20
    log(f"   {result}")
    return result


def main():
    binary, models, out = sys.argv[1:4]
    os.makedirs(models, exist_ok=True)
    os.makedirs(out, exist_ok=True)
    results = []

    log("Downloading Wan 2.1 T2V 1.3B (catalog)")
    wan21, files21 = catalog_files("wan2.1-t2v-1.3b", models)
    video = wan21["video"]
    negative = video.get("negativePrompt", "")
    common21 = {"t5xxl": files21["t5xxl"], "vae": files21["vae"], "mmap": True, "vae_tiling": True}

    a = run("A Wan2.1-T2V-1.3B", binary, out,
            {"diffusion_model": files21["diffusion_model"], "flash_attn": True, **common21},
            {"prompt": PROMPT, "negative": negative, "steps": video["steps"], "cfg": video["cfg"],
             "flow_shift": video["flowShift"], "fps": video["fps"], "output": os.path.join(out, "A.avi")})
    results.append(a)

    last_frame = None
    if "output" in a:
        frames = avi_frames(a["output"])
        last_frame = os.path.join(out, "A-last.jpg")
        open(last_frame, "wb").write(frames[-1])
    if last_frame is None:
        last_frame = os.path.join(out, "start.png")
        Image.new("RGB", (WIDTH, HEIGHT), (200, 60, 40)).save(last_frame)

    log("Looking for Wan 2.1 Fun InP 1.3B and CLIP Vision H")
    try:
        models_tree = hf_tree(COMFY_REPO, "split_files/diffusion_models")
        fun = next(f for f in models_tree
                   if "fun" in f["path"].lower() and "inp" in f["path"].lower() and "1.3b" in f["path"].lower())
        log(f"  {fun['path']} ({fun['size'] / 2**30:.2f} GiB)")
        clip_tree = hf_tree(COMFY_REPO, "split_files/clip_vision")
        clip = next(f for f in clip_tree if "clip_vision_h" in f["path"])
        fun_path = download(f"https://huggingface.co/{COMFY_REPO}/resolve/main/{fun['path']}",
                            os.path.join(models, os.path.basename(fun["path"])))
        clip_path = download(f"https://huggingface.co/{COMFY_REPO}/resolve/main/{clip['path']}",
                             os.path.join(models, os.path.basename(clip["path"])))
        b = run("B Wan2.1-Fun-InP-1.3B", binary, out,
                {"diffusion_model": fun_path, "clip_vision": clip_path, "weight_type": "q8_0",
                 "flash_attn": True, **common21},
                {"prompt": CONTINUE_PROMPT, "negative": negative, "steps": video["steps"], "cfg": video["cfg"],
                 "flow_shift": video["flowShift"], "fps": video["fps"], "init_image": last_frame,
                 "output": os.path.join(out, "B.avi")})
        for path in (fun_path, clip_path):
            os.remove(path)
    except Exception as error:  # noqa: BLE001
        b = {"name": "B Wan2.1-Fun-InP-1.3B", "error": str(error)}
        log(f"   failed: {error}")
    results.append(b)
    for path in files21.values():
        if os.path.exists(path):
            os.remove(path)

    log("Downloading Wan 2.2 TI2V 5B (catalog)")
    try:
        wan22, files22 = catalog_files("wan2.2-ti2v-5b", models)
        video22 = wan22["video"]
        c = run("C Wan2.2-TI2V-5B", binary, out,
                {"diffusion_model": files22["diffusion_model"], "t5xxl": files22["t5xxl"], "vae": files22["vae"],
                 "mmap": True, "vae_tiling": True},
                {"prompt": CONTINUE_PROMPT, "negative": video22.get("negativePrompt", ""), "steps": video22["steps"],
                 "cfg": video22["cfg"], "flow_shift": video22["flowShift"], "fps": video22["fps"],
                 "init_image": last_frame, "output": os.path.join(out, "C.avi")})
    except Exception as error:  # noqa: BLE001
        c = {"name": "C Wan2.2-TI2V-5B", "error": str(error)}
        log(f"   failed: {error}")
    results.append(c)

    start_image = Image.open(last_frame)
    for result in results:
        if "output" not in result:
            continue
        frames = avi_frames(result["output"])
        tag = result["name"].split()[0]
        for index in (0, len(frames) // 2, len(frames) - 1):
            open(os.path.join(out, f"{tag}-frame{index:02d}.jpg"), "wb").write(frames[index])
        if result is not a:
            result["seam"] = difference(frames[0], start_image)

    lines = [
        f"## Видео на ARM ({os.cpu_count()} ядер), {WIDTH}×{HEIGHT}, {FRAMES} кадров",
        "",
        "| Вариант | Загрузка, с | Генерация, с | Шаг, с | Пик памяти, ГиБ | Шов с кадром* | Ошибка |",
        "|---|---|---|---|---|---|---|",
    ]
    for r in results:
        lines.append("| {} | {} | {} | {} | {} | {} | {} |".format(
            r["name"],
            f"{r['load_s']:.0f}" if "load_s" in r else "—",
            f"{r['generate_s']:.0f}" if "generate_s" in r else "—",
            f"{r['step_s']:.1f}" if "step_s" in r else "—",
            f"{r['peak_gib']:.2f}" if r.get("peak_gib") else "—",
            f"{r['seam']:.1f}" if "seam" in r else "—",
            r.get("error", "")[:200].replace("|", "/"),
        ))
    lines += ["", "*Средняя разница первого кадра и кадра, от которого шло продолжение (0–255; меньше — незаметнее шов)."]
    summary = "\n".join(lines)
    log(summary)
    json.dump(results, open(os.path.join(out, "results.json"), "w"), ensure_ascii=False, indent=2)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as f:
            f.write(summary + "\n")


if __name__ == "__main__":
    main()
