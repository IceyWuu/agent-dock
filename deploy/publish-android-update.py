#!/usr/bin/env python3
"""Publish a verified Android release to the Caddy update directory, manifest last."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_release(source: Path) -> tuple[dict, Path]:
    manifest = json.loads((source / "latest.json").read_text())
    if manifest.get("schemaVersion") != 1 or manifest.get("applicationId") != "com.agentsanywhere.app":
        raise ValueError("Unsupported release manifest or application")
    name, code = manifest["versionName"], manifest["versionCode"]
    if not isinstance(name, str) or not re.fullmatch(r"[0-9]+(?:\.[0-9]+)+(?:-[a-zA-Z0-9.-]+)?", name):
        raise ValueError("Invalid version name")
    if type(code) is not int or code < 1:
        raise ValueError("Invalid version code")
    apk = source / f"agents-anywhere-{name}-{code}.apk"
    if manifest["downloadUrl"] != f"/updates/android/{apk.name}":
        raise ValueError("Unexpected APK download path")
    if not apk.is_file() or apk.stat().st_size != manifest["sizeBytes"]:
        raise ValueError("APK missing or truncated")
    if file_sha256(apk) != manifest["sha256"]:
        raise ValueError("APK checksum does not match")
    return manifest, apk


def publish(source: Path, store: Path, container: str) -> None:
    manifest, apk = validate_release(source)
    store.mkdir(parents=True, exist_ok=True)
    current = store / "latest.json"
    if current.exists():
        previous = json.loads(current.read_text())
        if previous["versionCode"] > manifest["versionCode"]:
            raise ValueError("Refusing to publish an older build")
        if previous["versionCode"] == manifest["versionCode"] and previous != manifest:
            raise ValueError("A different release already uses this version code")
    stored_apk = store / apk.name
    if stored_apk.exists() and file_sha256(stored_apk) != manifest["sha256"]:
        raise ValueError("Immutable APK filename is already in use")
    if not stored_apk.exists():
        with tempfile.NamedTemporaryFile(dir=store, delete=False) as staged:
            temporary = Path(staged.name)
            with apk.open("rb") as stream:
                shutil.copyfileobj(stream, staged)
        os.chmod(temporary, 0o644)
        temporary.replace(stored_apk)
    manifest_file = store / "latest.json.pending"
    manifest_file.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    index = store / "index.html"
    index.write_text(f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Android 更新</title><body style="font-family:system-ui;max-width:36rem;margin:3rem auto;padding:1rem;line-height:1.8"><h1>Agents Anywhere Android {manifest['versionName']}</h1><p>下载后覆盖安装，已有聊天与设置会保留。首次安装此版后，今后打开 App 即可收到新版提示。</p><p><a href="{apk.name}">下载 Android 安装包</a>（{manifest['sizeBytes']/1024/1024:.1f} MB）</p><p>Android 8.0 及以上。安装时请按系统提示确认。</p></body></html>''')
    def docker(*args: str) -> None:
        subprocess.run(["docker", *args], check=True)
    directory = "/data/android-updates"
    docker("exec", container, "mkdir", "-p", directory)
    docker("cp", str(stored_apk), f"{container}:{directory}/{apk.name}.pending")
    docker("exec", container, "mv", f"{directory}/{apk.name}.pending", f"{directory}/{apk.name}")
    docker("cp", str(index), f"{container}:{directory}/index.html")
    docker("cp", str(manifest_file), f"{container}:{directory}/latest.json.pending")
    docker("exec", container, "mv", f"{directory}/latest.json.pending", f"{directory}/latest.json")
    manifest_file.replace(current)
    print(f"Published Android {manifest['versionName']} ({manifest['versionCode']})")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Directory containing latest.json and the versioned APK")
    parser.add_argument("--store", type=Path, default=Path("/opt/agents-anywhere/deploy/android-updates"))
    parser.add_argument("--container", default="aa-caddy")
    args = parser.parse_args()
    publish(args.source, args.store, args.container)
