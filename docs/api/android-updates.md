# Android 静态更新清单（schemaVersion 1）

Android 2.1.2 起从已连接服务器的 `GET /updates/android/latest.json` 检查独立发布的 APK，不再把 Server 健康检查版本当作 Android 版本。此路径由部署层托管静态文件，不增加 `/api/v2` 或 Runtime 字段；其他客户端不受影响。没有发布目录的服务器无需启用此功能。

```json
{
  "schemaVersion": 1,
  "applicationId": "com.agentsanywhere.app",
  "versionName": "2.1.2",
  "versionCode": 14,
  "downloadUrl": "/updates/android/agents-anywhere-2.1.2-14.apk",
  "sha256": "<APK 的 64 位小写 SHA-256>",
  "sizeBytes": 49000000
}
```

`versionCode` 必须递增，版本名不能低于已安装版本。下载使用同一服务器 HTTPS 更新目录中的 APK。客户端下载完成后核对长度、SHA-256、包名、版本号和已安装 App 的签名，通过后才交给系统安装；系统最后仍要求用户确认。

App 启动、回到前台及前台每 5 分钟检查一次，也可在设置中手动检查。后台不轮询、不使用系统通知推送服务。忽略版本按服务器和 versionCode 保存；更高版本会再次提示。404 表示未开启更新，其他网络或清单错误可稍后重试。

旧版 App 的 APK 地址为占位地址，且按 Server 版本判断，无法通过新清单补上客户端能力；需先手动安装一次 2.1.2 或更高版本。
