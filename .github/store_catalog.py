"""Каталог Prism Store для релиза: store/apps.json + версии из gradle/libs.versions.toml + файлы из dist.

Использование: python3 .github/store_catalog.py <dist> <тег релиза> > dist/prism-store-catalog.json
"""
import hashlib
import json
import os
import re
import sys

REPOSITORY = "wepper6200/prism-ai-app"


def versions():
    text = open("gradle/libs.versions.toml", encoding="utf-8").read()
    return dict(re.findall(r'^([\w-]+) = "([^"]*)"', text, re.MULTILINE))


def describe(dist, tag, name):
    path = os.path.join(dist, name)
    if not os.path.isfile(path):
        sys.exit(f"Нет файла {path} для каталога Prism Store")
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return {
        "fileName": name,
        "url": f"https://github.com/{REPOSITORY}/releases/download/{tag}/{name}",
        "sizeBytes": os.path.getsize(path),
        "sha256": digest.hexdigest(),
    }


def main():
    dist, tag = sys.argv[1], sys.argv[2]
    known = versions()
    apps = []
    for app in json.load(open("store/apps.json", encoding="utf-8"))["apps"]:
        version = known[app["versionKey"]]
        entry = {k: app[k] for k in ("id", "name", "description")}
        entry["version"] = version
        if "android" in app:
            entry["android"] = {
                "packageName": app["android"]["packageName"],
                "file": describe(dist, tag, app["android"]["file"].format(version=version)),
            }
        if "windows" in app:
            entry["windows"] = {
                "folder": app["windows"]["folder"],
                "exe": app["windows"]["exe"],
                "file": describe(dist, tag, app["windows"]["file"].format(version=version)),
            }
        apps.append(entry)
    print(json.dumps({"version": 1, "apps": apps}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
