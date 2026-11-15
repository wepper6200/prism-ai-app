import json, os, pathlib, subprocess, time, urllib.request, hashlib
root = pathlib.Path(__file__).resolve().parents[1]
def run(*args): return subprocess.check_output(args, text=True).strip()
devices = json.loads(run('xcrun', 'simctl', 'list', 'devices', 'available', '--json'))['devices']
device = next(d for group in devices.values() for d in group if d['name'].startswith('iPhone'))
udid = device['udid']
if device['state'] != 'Booted': run('xcrun', 'simctl', 'boot', udid)
run('xcrun', 'simctl', 'bootstatus', udid, '-b')
app = root / 'iosApp/DerivedData/Build/Products/Debug-iphonesimulator/PrismAI.app'
run('xcrun', 'simctl', 'install', udid, str(app))
data = pathlib.Path(run('xcrun', 'simctl', 'get_app_container', udid, 'ai.prism.app', 'data'))
folder = data / 'Library/Application Support/PrismAI'; folder.mkdir(parents=True, exist_ok=True)
fixture = folder / 'tiny.gguf'
urllib.request.urlretrieve('https://huggingface.co/ggml-org/models/resolve/main/tinyllamas/stories260K.gguf', fixture)
assert hashlib.sha256(fixture.read_bytes()).hexdigest() == '270cba1bd5109f42d03350f60406024560464db173c0e387d91f0426d3bd256d'
run('xcrun', 'simctl', 'launch', udid, 'ai.prism.app', '--prism-smoke')
report = folder / 'smoke-result.txt'
for _ in range(90):
    if report.exists(): break
    time.sleep(2)
assert report.exists(), 'iOS application did not complete real inference smoke'
result = report.read_text(); print(result); assert result.startswith('OK:')
output = root / 'build/ios-smoke'; output.mkdir(parents=True, exist_ok=True)
(output / 'inference.txt').write_text(result)
run('xcrun', 'simctl', 'io', udid, 'screenshot', str(output / 'iphone.png'))
run('xcrun', 'simctl', 'terminate', udid, 'ai.prism.app')
run('xcrun', 'simctl', 'launch', udid, 'ai.prism.app')
time.sleep(5)
run('xcrun', 'simctl', 'io', udid, 'screenshot', str(output / 'iphone-relaunch.png'))
