from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[2] / "deploy/update-desktop-connector.py"
spec = importlib.util.spec_from_file_location("desktop_connector_update", SCRIPT)
updater = importlib.util.module_from_spec(spec)
spec.loader.exec_module(updater)


def test_desktop_update_preserves_config_and_keeps_recoverable_source_backup(tmp_path):
    source, data = tmp_path / "source", tmp_path / "desktop"
    (source / "connector").mkdir(parents=True)
    (source / "pyproject.toml").write_text(
        '[project]\nname="anywhere-cli"\nversion="2.1.2"\n'
    )
    (source / "README.md").write_text("fixture")
    (source / "connector/cli.py").write_text("new")
    (source / "connector/desktop_bridge.py").write_text("new bridge")
    target = data / "connector/connector-source/hash"
    (target / "connector").mkdir(parents=True)
    (target / "connector/cli.py").write_text("old")
    (target / "pyproject.toml").write_text("old manifest")
    (target / "uv.lock").write_text("local dependency state")
    config = data / "connector/connector.json"
    config.write_text("private config fixture")
    assert updater.install(source, data) == 1
    assert (target / "connector/cli.py").read_text() == "new"
    assert (target / "connector/desktop_bridge.py").is_file()
    assert (target / "uv.lock").read_text() == "local dependency state"
    assert config.read_text() == "private config fixture"
    backups = list((data / "connector/source-backups").glob("*/hash/connector/cli.py"))
    assert len(backups) == 1 and backups[0].read_text() == "old"


def test_wrong_source_is_rejected_before_modifying_desktop(tmp_path):
    (tmp_path / "pyproject.toml").write_text('[project]\nname="other"\n')
    with pytest.raises(ValueError, match="Expected"):
        updater.install(tmp_path, tmp_path / "desktop")
    assert not (tmp_path / "desktop").exists()


def test_desktop_data_path_uses_current_user_and_platform_environment(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("APPDATA", str(tmp_path / "roaming"))
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "config"))
    assert updater.desktop_data("win32") == tmp_path / "roaming/Agents Anywhere"
    assert updater.desktop_data("linux") == tmp_path / "config/Agents Anywhere"
    assert (
        updater.desktop_data("darwin")
        == Path.home() / "Library/Application Support/Agents Anywhere"
    )
