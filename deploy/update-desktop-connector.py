#!/usr/bin/env python3
"""Install this checkout's Connector into an existing Desktop's writable copies."""

from __future__ import annotations

import argparse
import datetime as dt
import os
import shutil
import sys
import tempfile
import tomllib
from pathlib import Path


def desktop_data(platform: str = sys.platform) -> Path:
    if platform == "darwin":
        return Path.home() / "Library/Application Support/Agents Anywhere"
    if platform == "win32":
        return (
            Path(os.environ.get("APPDATA", str(Path.home() / "AppData/Roaming")))
            / "Agents Anywhere"
        )
    return (
        Path(os.environ.get("XDG_CONFIG_HOME", str(Path.home() / ".config")))
        / "Agents Anywhere"
    )


def install(source: Path, data: Path) -> int:
    manifest = tomllib.loads((source / "pyproject.toml").read_text())
    if (
        manifest.get("project", {}).get("name") != "anywhere-cli"
        or not (source / "connector/cli.py").is_file()
    ):
        raise ValueError("Expected an Agents Anywhere Connector source directory")
    root = data / "connector/connector-source"
    targets = (
        [
            p
            for p in root.iterdir()
            if p.is_dir()
            and not p.is_symlink()
            and (p / "connector/cli.py").is_file()
            and (p / "pyproject.toml").is_file()
        ]
        if root.is_dir()
        else []
    )
    if not targets:
        raise ValueError(
            "Open Agents Anywhere Desktop and connect the device once before updating"
        )
    backup = (
        data
        / "connector/source-backups"
        / dt.datetime.now(dt.UTC).strftime("%Y%m%dT%H%M%S%fZ")
    )
    for target in targets:
        with tempfile.TemporaryDirectory(
            prefix="connector-update-", dir=root
        ) as temporary:
            staged = Path(temporary) / target.name
            shutil.copytree(
                target,
                staged,
                symlinks=True,
                ignore=shutil.ignore_patterns("__pycache__", "*.pyc"),
            )
            shutil.rmtree(staged / "connector")
            shutil.copytree(
                source / "connector",
                staged / "connector",
                ignore=shutil.ignore_patterns(
                    "__pycache__", "*.pyc", "_reference", "_deprecated"
                ),
            )
            for name in ("pyproject.toml", "README.md"):
                shutil.copy2(source / name, staged / name)
            # Do not modify the signed app, credentials, sessions or environment.
            backup.mkdir(parents=True, exist_ok=True, mode=0o700)
            saved = backup / target.name
            target.rename(saved)
            try:
                staged.rename(target)
            except BaseException:
                saved.rename(target)
                raise
    print(
        f"Installed Connector {manifest['project']['version']} in {len(targets)} Desktop source copies. Restart the Connector."
    )
    return len(targets)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source", type=Path, default=Path(__file__).resolve().parents[1] / "connector"
    )
    parser.add_argument("--data-dir", type=Path, default=desktop_data())
    args = parser.parse_args()
    install(args.source.resolve(), args.data_dir.expanduser().resolve())
