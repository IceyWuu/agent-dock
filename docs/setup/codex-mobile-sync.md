# Codex 手机同步配置

1. 若 Desktop 已内置 Connector 2.1.3 或更新版本，从步骤 3 开始；否则在工作电脑安装 Python 3.12 和 uv，并更新本仓库代码。
2. 退出 Agent Anywhere，在仓库根目录运行：
   ```sh
   uv run --python 3.12 --no-project python deploy/update-desktop-connector.py
   ```
3. 重新打开 Agent Anywhere 和 Codex，使用同一个系统用户。AA 的 Codex 主目录设为该电脑的 `CODEX_HOME`；未自定义时使用 `~/.codex`。
4. 若发送时提示找不到支持 `queue` 的 Codex，在 AA 的 Codex 环境变量中添加 `AA_CODEX_DESKTOP_BIN`，值填本机支持此命令的 Codex 可执行文件绝对路径，然后重启连接程序。
5. 手机上选择该设备和原对话，打开“接管”，发送消息。

6. 需要在手机回答电脑端的选项、问答或审批时，工作电脑使用 Connector 2.1.3 或更新版本；打开原对话的“接管”后提交。已安装 Android 2.1.7 的手机无需重装。
