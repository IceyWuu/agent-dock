# 自托管 Android 更新

Android 2.1.2 起支持打开 App 自动提示、下载并唤起系统确认安装。初次仍需安装一次支持更新清单的 App；安卓需要用户确认，不能静默强装。协议见 [Android 更新清单](../docs/api/android-updates.md)。此方案不修改数据库，不需为了发布 APK 改动 Server 版本。

## 一次性配置 Caddy

在现有站点块里把单独的更新目录和应用反代分别放入 handle。更新文件保存在 Caddy 已持久化的 `/data/android-updates`，仅公开这个子目录，不能把整个 `/data` 暴露出去。

```caddyfile
handle_path /updates/android/* {
    root * /data/android-updates
    header Cache-Control "no-store"
    file_server
}
handle {
    reverse_proxy server-next:8000
}
```

先备份 Caddyfile，用 `caddy validate` 验证后再 `caddy reload`，保留原站点域名、端口、TLS 和其他配置。

## 发布新版

使用与已安装 App 相同的签名，递增 `android/app/build.gradle.kts` 的 versionCode 和 versionName 后构建、验证。把版本化 APK 与包含真实散列和大小的 `latest.json` 放在同一个发布目录，例如 `agents-anywhere-2.1.2-14.apk`。上传到服务器暂存目录，再在服务器运行：

```bash
sudo python3 /opt/agents-anywhere/deploy/publish-android-update.py /tmp/android-release
```

脚本会校验文件长度和 SHA-256，保留每个版本的 APK，拒绝回退或复用版本号替换不同文件。先发布 APK、最后原子切换清单，防止用户下载未传完的文件。服务器需 Python 3.9+、Docker 以及现有 `aa-caddy` 容器。可用 `--container`、`--store` 覆盖默认位置。

发布后核对公网 `latest.json` 和 APK 的实际响应、散列。用户首次下载入口为 `https://你的域名/updates/android/`。清单和 APK 放在公开路径，不包含登录凭据；不要让 CDN 缓存旧清单。

## 验证

在隔离的 Android 模拟器上安装较低版本的同签名 App，连接已发布清单，检查更新弹窗、下载进度、完整性检查和系统安装确认。生产用户的设备由用户自己确认安装。应用源码保留之前的公式、共享工作区、设备筛选和消息时间功能。
