"""Fetch pinned Apple build dependencies; never executed by the installed app."""
import hashlib, pathlib, plistlib, shutil, sys, tomllib, urllib.request, zipfile
root = pathlib.Path(__file__).resolve().parents[2]
versions = tomllib.loads((root / 'gradle/libs.versions.toml').read_text())['versions']
cache = root / 'native/build/apple-deps'
cache.mkdir(parents=True, exist_ok=True)
sdk, output = sys.argv[1:]
output = pathlib.Path(output)
for name in ('sherpa', 'onnx'):
    key = 'apple-' + name
    directory = cache / name
    if not directory.exists():
        archive = cache / (name + '.zip')
        if not archive.exists() or hashlib.file_digest(archive.open('rb'), 'sha256').hexdigest() != versions[key + '-sha256']:
            urllib.request.urlretrieve(versions[key + '-url'], archive)
        if hashlib.file_digest(archive.open('rb'), 'sha256').hexdigest() != versions[key + '-sha256']:
            raise RuntimeError('Invalid checksum: ' + name)
        with zipfile.ZipFile(archive) as z:
            z.extractall(directory)
    framework = next(directory.rglob('*.xcframework'))
    info = plistlib.loads((framework / 'Info.plist').read_bytes())
    variant = 'simulator' if sdk == 'iphonesimulator' else None
    entry = next(x for x in info['AvailableLibraries'] if x['SupportedPlatform'] == 'ios' and x.get('SupportedPlatformVariant') == variant and 'arm64' in x['SupportedArchitectures'])
    base = framework / entry['LibraryIdentifier']
    library = base / entry['LibraryPath']
    if library.suffix == '.framework':
        library = library / library.stem
    shutil.copyfile(library, output / ('libprism_' + name + '.a'))
    if name == 'sherpa':
        header = next(base.rglob('c-api.h'))
        shutil.copyfile(header, output / 'c-api.h')
