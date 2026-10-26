# 共享工作区（2.1）

自托管实例可显式设置 `AGENT_SERVER_SHARED_WORKSPACE=true`。默认关闭。
启用后所有访问者共用设备、项目、会话和附件；设备授权凭据仍必需。
这等同于向能够访问服务地址的人开放工作区（含终端和文件）。请在仅两人可访问的网络入口使用。

- `GET /api/v2/auth/config` 新增 `sharedWorkspaceEnabled: boolean`（默认 false）。
- `POST /api/v2/auth/shared-session`：无请求体、无需账号凭据；开启时返回现有 `AuthResponse`，普通 Bearer token 的有效期与原登录一致。关闭时返回 404。共享账号被禁用/删除时返回 403，不回退到其他账号。
- 客户端先读 config；共享模式自动获取共享 session，覆盖旧账号 session；未启用则保留原登录流程。原 OAuth/PKCE、Connector 认证不变。
- 共享用户 ID 持久化在 instance_settings；首次优先选择最早创建的启用管理员，没有用户则创建无已知密码的共享管理员。
- 首次启动共享模式，在单一事务内把所有设备、项目、会话分享归到共享用户，保留 ID、会话、消息、文件、设备凭据。重名项目补 ` (2)` 等后缀；相同设备相同路径的项目合并并保留会话。用户和密码记录不删除。启用前备份；关闭开关不会自动拆回原数据归属。
- Android 2.1 自动连接，Web 和 Desktop 2.1 自动进入；iOS 现有网页登录可通过更新后的 Web 自动完成登录。旧端可能需要断开重连才能切换共享账号。

消息时间继续使用 Timeline 已有 createdAt；Android 在用户/Agent 每条消息下方按手机时区显示 `yyyy-MM-dd HH:mm:ss`。流式更新不改变显示时间；无有效时间的旧消息显示“时间未记录”，不伪造当前时间。
