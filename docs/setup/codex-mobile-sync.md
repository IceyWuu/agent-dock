# Codex 手机同步与问答：快速配置

1. 在工作电脑安装 [uv](https://docs.astral.sh/uv/getting-started/installation/)。打开 Agents Anywhere，连接 `https://aa.sipowerup.online`，完成一次设备连接。
2. [下载源码 ZIP](https://github.com/IceyWuu/agent-dock/archive/refs/heads/codex/mobile-math-rendering.zip)，解压。在包含 `deploy` 文件夹的源码根目录打开终端。
3. 完全退出 Agents Anywhere，运行以下命令，安装 Connector 2.1.3 或更新版本（首次自动准备 Python 3.12）：
   ```sh
   uv run --python 3.12 --no-project python deploy/update-desktop-connector.py
   ```
4. 重新打开 Agents Anywhere 和 Codex，两者使用同一个系统用户。AA 的 Codex 主目录保持默认；若 Codex 自定义了 `CODEX_HOME`，把 AA 的 Codex 主目录设为同一路径。
5. 手机使用 Android 2.1.7 或更新版本，连接同一服务器，选择该电脑和原对话，打开“接管”。可发消息、实时查看回复、选择选项、输入其他答案及处理审批。
6. 若提示找不到支持 `queue` 的 Codex，在 AA 的 Codex 环境变量中添加 `AA_CODEX_DESKTOP_BIN`，值填这台电脑上支持 `queue` 的 Codex 可执行文件绝对路径，随后完全退出并重新打开 Agents Anywhere。

以后升级：重新下载最新源码，重复步骤 3–4。已内置 Connector 2.1.3 或更新版本的 Desktop 可跳过步骤 2–3。
