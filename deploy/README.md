# agent-dock 自托管部署（腾讯云轻量 2C4G · 公网 IP 直连）

这套文件把 [agent-dock](https://github.com/IceyWuu/agent-dock)（基于 [Agents Anywhere](https://github.com/anywhere-labs/Agents-Anywhere) v2.0.3）的服务端部署到**你自己的服务器**上，用 `http://公网IP:18080` 访问，不绑域名、不走备案。

**先理解一件事：服务器只负责“控制面”。** 真正的 Agent（Codex / Claude Code / DSH）跑在 Connector 所在的工作设备上，也就是你的 Mac。服务器保存账号、会话、Timeline 和附件，转发指令。

```
Mac / 手机 / 浏览器  ──HTTP──▶  你的轻量服务器（Postgres + Redis + Server + Web）
                                        │
                                        └──Connector RPC──▶  Mac 上的 Connector ──▶ Agent / 工作区 / 终端
```

所以 2 核 4G 完全够：Postgres 512MB + Redis 256MB + Python 服务，常驻内存大约 1.2–1.8 GB（估算值，以 `docker stats` 实测为准）。

**版本固定：代码在 `v2.0.3` tag 上。** 官网/ModelScope 能下载的桌面端、Android、iOS 安装包都是 2.0.3，服务端用同一个版本最稳。仓库 `main` 已经是 2.1.1，但 2.1.x 的安装包还没发布；等客户端更新后，再按第 7 节把服务端一起升到对应 tag，不要只升服务端。

---

## 0. 准备清单

| 项目 | 说明 |
| --- | --- |
| 服务器 | 腾讯云轻量 2C4G5M，Ubuntu 24.04（你的镜像是 `Ubuntu24.04-Docker29`，Docker 已装好） |
| 访问方式 | 公网 IP + `root` 账号（SSH 密钥或密码） |
| 端口 | **TCP 18080**（可改），在轻量服务器控制台 → 防火墙里放行 |
| Mac | 安装桌面客户端（后面第 6 步给链接） |
| 时间 | 首次构建约 15–30 分钟（取决于拉取镜像和 npm 包的速度） |

> 端口为什么是 18080：大陆服务器用域名访问 80/443 必须先 ICP 备案；用 IP + 非标端口不涉及备案。你只给两个人用，够用。

---

## 1. 把代码传到服务器

在 **Mac 上**、仓库目录里执行（把 `1.2.3.4` 换成你的公网 IP）：

```bash
cd /path/to/agent-dock
tar czf /tmp/aa-deploy.tar.gz .
scp /tmp/aa-deploy.tar.gz root@1.2.3.4:/root/
ssh root@1.2.3.4 'mkdir -p /opt/agents-anywhere && tar xzf /root/aa-deploy.tar.gz -C /opt/agents-anywhere'
```

包里有 `.git`（当前固定在 `v2.0.3`），以后可以用 `bash upgrade.sh v2.x.y` 切到新版本。如果不带 `.git`，升级时重新上传覆盖即可，脚本会自动跳过 pull。

> 也可以在服务器上直接 `git clone --depth 1 https://github.com/anywhere-labs/Agents-Anywhere.git /opt/agents-anywhere`，但国内网络经常很慢，打包上传更稳。

---

## 2. 服务器初始化（一次）

```bash
ssh root@1.2.3.4
cd /opt/agents-anywhere
bash deploy/server-setup.sh
```

它做四件事：

1. 补 `curl / git / openssl` 等工具；
2. 内存 < 8G 且没有 swap 时，建 4G swap（**Next.js 构建时防止 OOM 的关键**）；
3. 把腾讯云内网镜像源写进 `/etc/docker/daemon.json`（只在能连通时才写），拉镜像会快很多；
4. 预拉 `node/python/postgres/redis` 基础镜像。

然后去 **腾讯云控制台 → 轻量应用服务器 → 你的实例 → 防火墙 → 添加规则**：

- 应用类型：自定义
- 协议：TCP
- 端口：`18080`
- 来源：`0.0.0.0/0`（只自己用可以填你家/公司的出口 IP，更安全）

---

## 3. 构建并启动

```bash
cd /opt/agents-anywhere
bash deploy/install.sh
```

脚本会：

1. 生成 `deploy/.env`（数据库密码、签名密钥都用 `openssl rand` 随机生成，权限 600）；
2. `docker compose build` 构建镜像（Node 22 构建 Web 静态文件 + Python 3.12 服务端），首次 15–30 分钟；
3. 启动 PostgreSQL 17、Redis 8、一次性迁移任务、Server；
4. 等 `/api/v2/health/ready` 通过，打印首次初始化用的 **setup token**。

成功时输出类似：

```
==> 服务已就绪：http://1.2.3.4:18080
────────────────────────────────────────────
 服务地址：http://1.2.3.4:18080
 首次初始化（有效期 1 小时）：
   setup token: Xk3...
 打开上面的地址 → 粘贴 token → 创建管理员账号
```

如果构建中断（`exit 137` 或 `Killed`），多半是内存不够：确认 `swapon --show` 有 4G swap，再重跑一次即可（Docker 会复用已完成的层）。

---

## 4. 创建第一个管理员

1. 浏览器打开 `http://1.2.3.4:18080`，会直接进入首次初始化页面；
2. token 在服务启动时已经打印到日志，取最新的一条：

   ```bash
   cd /opt/agents-anywhere/deploy
   docker compose logs server-next | grep -o 'setup-token: .*' | tail -1
   ```

3. 在首次初始化表单里填：setup token、昵称、邮箱、密码（建议 12 位以上，用密码管理器生成）。

创建成功后：

- 这个账号就是管理员；
- **注册会自动关闭**（防止别人抢先注册）；
- token 立即作废，不能复用。

> token 有效期 1 小时（`.env` 里 `AGENT_SERVER_SETUP_TOKEN_TTL=3600`）。过期了不用重启：刷新页面会在日志里生成新 token，再取一次即可。

---

## 5. 加第二个人（共两个账号）

管理员登录后，在 **设置 → 服务** 页打开 **「开放注册」**，让对方自己注册；用完可以再关掉。

或者不开放注册，直接在 **团队** 页由管理员添加用户。

两个人都在同一实例里，各自管理自己的设备、项目和会话。

---

## 6. Mac 客户端接入

1. 下载桌面客户端（2.0.3，Universal，Apple Silicon / Intel 都能用）：
   [Agents Anywhere-2.0.3-universal.dmg](https://modelscope.cn/models/t4wefan/deepseek-harness-desktop/resolve/master/Agents%20Anywhere-2.0.3-universal.dmg)
2. 打开 App，在登录页展开「连接自托管服务」，填：

   ```
   http://1.2.3.4:18080
   ```

   只填到端口，**不要**加 `/api/v2`。
3. 用第 4 步创建的管理员账号登录。
4. 按引导完成本机设备配对（桌面客户端自带本机 Connector），等待设备显示「在线」。
5. 选择 Runtime（Codex / Claude Code / DSH），确认模型与账号可用，选一个项目工作目录，新建会话，发一条消息试试。

之后用手机、平板或浏览器打开同一个地址、登录同一账号，就能看到这台 Mac 上的会话、文件和终端。

> 手机端注意：Android 允许明文 HTTP；**iOS/iPadOS 的系统 ATS 只放行私有网段的 HTTP**，公网 IP 的 `http://` 会被拦。要让 iPhone 用，需要 HTTPS（见第 9 节）。只用 Mac + 浏览器的话不受影响。

---

## 7. 日常运维

全部在 `/opt/agents-anywhere` 下执行：

```bash
cd /opt/agents-anywhere/deploy

docker compose ps                     # 容器状态（server 应为 healthy）
docker compose logs -f server-next    # 实时日志
docker compose restart server-next    # 只重启服务端
docker compose stop                   # 全部停止
docker compose up -d                  # 全部启动（开机自启，一般不用手动）

bash backup.sh                        # 备份数据库 + 附件到 backups/
bash upgrade.sh                       # 备份 → 重建 → 重启 → 健康检查（保持当前版本）
bash upgrade.sh v2.0.4                # 切到指定 tag 再重建（推荐做法）
```

**备份**：`backup.sh` 用 `pg_dump` 导出数据库、把附件卷打成 tar，保留最近 7 份。建议再定期 `scp` 回本地一份——服务器挂了数据就没了。

**恢复**（换机器或误删时）：

```bash
docker compose up -d postgres-next
gunzip -c backups/db-XXXX.sql.gz | docker compose exec -T postgres-next psql -U agents_anywhere -d agents_anywhere
docker run --rm -v agents-anywhere_agents-anywhere-files-next:/data -v "$PWD/backups":/backup alpine tar xzf /backup/files-XXXX.tar.gz -C /data
```

**升级**：先确认新版客户端已发布，再执行 `bash upgrade.sh v2.x.y`（tag 名以 GitHub Releases 为准）。脚本会先备份，然后切 tag、重建镜像、重启；迁移任务会在新 Server 启动前自动跑数据库迁移。升级前读一遍 [升级说明](https://github.com/anywhere-labs/Agents-Anywhere/blob/v2.0.3/docs/upgrading.md)，并保留旧镜像以便回滚。

> 注意：服务端 **不要单独升到比客户端更高的版本**。跨 MINOR（例如 2.0.x → 2.1.x）时，桌面端和手机端必须一起升级。

### 同步上游版本（本仓库是 fork）

本仓库 `origin` 指向 `IceyWuu/agent-dock`，`upstream` 指向上游 `anywhere-labs/Agents-Anywhere`。上游发布新 tag 后：

```bash
cd /opt/agents-anywhere
git fetch upstream --tags        # 取上游新 tag
git push origin --tags           # 同步到自己的仓库，服务器才能按 tag 拉取
bash deploy/upgrade.sh v2.x.y    # 切版本并重建
```

合并上游代码改动（不只是 tag）：

```bash
git fetch upstream
git merge upstream/main          # 解决冲突后提交
```


---

## 8. 安全（重要，别跳过）

这套方案是 **明文 HTTP**：登录密码、会话 token 在网络上是明文传输的。两个人自用可以接受，但至少做两件事：

1. **强密码**：管理员密码用密码管理器生成 16 位以上；
2. **收敛入口**：腾讯云防火墙的来源 IP 尽量填你俩常用的出口 IP；做不到全开也可以，但知道有被扫的风险。

再往上一步（不改端口、不绑域名也能加密）：

| 方案 | 效果 | 代价 |
| --- | --- | --- |
| [Tailscale](https://tailscale.com/) + `tailscale serve` | 只有你自己的设备能访问，自动有受信任证书（`*.ts.net`） | 每台设备装 Tailscale |
| [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/) | 公网 HTTPS，服务器不暴露入站端口 | 需要一个域名托管在 Cloudflare；国内延迟一般 |
| 域名 + Caddy（8443 端口） | 标准 HTTPS，客户端不用装东西 | 要买域名；大陆服务器用非标端口是常见做法，但注意合规风险 |

---

## 9. 可选：给 iPhone 用，或者就是要 HTTPS

**有域名**（推荐给 iOS 用户）：加一个 Caddy 容器，把 `https://aa.example.com:8443` 反代到 `server-next:8000`。Caddy 会自动申请 Let's Encrypt 证书。然后在 `.env` 里改成：

```env
AGENTS_ANYWHERE_WEB_PORT=18080          # 可以保留，只要防火墙不开放这个端口
AGENT_SERVER_PUBLIC_ORIGIN=https://aa.example.com:8443
AGENT_SERVER_CORS_ORIGINS=https://aa.example.com:8443
```

`deploy/Caddyfile` 示例：

```
aa.example.com:8443 {
    reverse_proxy server-next:8000
}
```

启动：`docker run -d --name aa-caddy --network agents-anywhere_default -p 8443:8443 -v $PWD/Caddyfile:/etc/caddy/Caddyfile -v caddy-data:/data caddy:2`。记得在腾讯云防火墙放行 `8443`，并把域名 A 记录指向服务器 IP。

**没有域名**：用 Cloudflare Tunnel 或 Tailscale（见上表），它们都能给出合法的 HTTPS 地址，不需要备案。

> 换地址后要同步改 `.env` 里的 `AGENT_SERVER_PUBLIC_ORIGIN` 和 `AGENT_SERVER_CORS_ORIGINS`，然后 `docker compose up -d server-next`。

---

## 10. 常见问题

**构建卡在 `httpx-s3-client` / GitHub（GnuTLS recv error、failed to fetch commit）**
- 这个依赖在上游 `server/pyproject.toml` 里是 git 源（`github.com/t4wefan/httpx-s3-client`），国内服务器直连 GitHub 经常被 TLS 中断。本项目已经把它 vendored 到 `server/vendor/httpx-s3-client`，构建时改成本地路径安装，不再访问 GitHub。
- 如果升级后报这个错，说明 vendor 目录不在或已过期：在能访问 GitHub 的机器上 `git clone --depth 1 https://github.com/t4wefan/httpx-s3-client.git`，把内容覆盖到 `server/vendor/httpx-s3-client/`（删掉 `.git`），再重新 build。

**`docker compose build` 卡住或失败**
- 先看是不是拉基础镜像超时：`docker pull node:22-bookworm-slim` 手动试；不通就检查 `/etc/docker/daemon.json` 的 `registry-mirrors`，或重跑 `server-setup.sh`。
- `exit 137` / `Killed`：内存不足。确认 swap 生效（`swapon --show`），或临时把 swap 加到 6–8G 再构建。
- 想看详细过程：`docker compose build --progress=plain`。

**浏览器打不开 `http://IP:18080`**
- 服务器本机验证：`curl -i http://127.0.0.1:18080/api/v2/health` 应返回 JSON `{"status":"ok"}`；
- 本机通、外面不通 → 99% 是腾讯云防火墙没放行；
- 返回的是 HTML 而不是 JSON → 地址写错了（比如少了端口，或反代指到了静态页）。

**首次页面没有 setup token / 提示 token 无效**
- token 过期了。刷新页面，然后重新 `cd deploy && docker compose logs server-next | grep setup-token | tail -1`；
- 注意日志里会有多条历史 token，取**最后一条**。

**桌面端提示「该地址未返回正常的 Agents Anywhere 服务」**
- 确认填的是 `http://IP:18080`，结尾没有 `/`、没有 `/api/v2`；
- 在 Mac 上 `curl -i http://IP:18080/api/v2/health` 验证；
- 确认服务端 `docker compose ps` 里 server 是 `healthy`。

**设备在线但 Runtime 不可用**
- 这是 Mac 侧的问题，不是服务器：确认 Codex / Claude Code 已安装并登录，看桌面端日志目录 `~/Library/Application Support/Agents Anywhere/logs`。

**改了端口**
- 改 `.env` 里的 `AGENTS_ANYWHERE_WEB_PORT`、`AGENT_SERVER_PUBLIC_ORIGIN`、`AGENT_SERVER_CORS_ORIGINS` 三处，`docker compose up -d`，并在防火墙放行新端口。

---

## 11. 文件说明

| 文件 | 作用 |
| --- | --- |
| `deploy/Dockerfile` | Server 镜像（等同仓库 `docker/Dockerfile`，多了 corepack 走 npm 镜像的两行） |
| `deploy/docker-compose.yml` | 四个服务 + 数据卷 + 健康检查 + 日志轮转 |
| `deploy/install.sh` | 首次部署：生成 `.env` → 构建 → 启动 → 等就绪 → 打印 token |
| `deploy/server-setup.sh` | 服务器初始化：swap、Docker 镜像加速、内核参数 |
| `deploy/backup.sh` | 数据库 + 附件备份，保留 7 份 |
| `deploy/upgrade.sh` | 备份 → 切 tag/拉代码 → 重建 → 重启 → 健康检查 |
| `deploy/.env` | 本机密钥与端口配置（**不要提交、不要外发**），由 `install.sh` 生成 |
| `deploy/.env.example` | `.env` 的字段说明 |
| `server/vendor/httpx-s3-client/` | 上游 git 依赖的本地副本，构建时走它，避免访问 GitHub |

数据都在 Docker 命名卷里：

- `agents-anywhere_agents-anywhere-pg-next`：PostgreSQL
- `agents-anywhere_agents-anywhere-redis-next`：Redis（含尚未刷入数据库的 Timeline，别随便删）
- `agents-anywhere_agents-anywhere-files-next`：上传的附件

## 两人共用、免账号登录（2.1）

备份后在 `deploy/.env` 设置 `AGENT_SERVER_SHARED_WORKSPACE=true`，更新源码并重建 Server/Web 镜像。首次启用会把现有设备、项目和会话汇入同一工作区，消息和附件保留，重名项目加序号；旧账号不会删除。此模式允许所有能访问服务地址的人使用设备、文件和终端，所以入口应只向两人开放。关闭开关不会自动拆回旧账号数据归属。

Android 2.1 首次填服务地址点连接即可进入，以后自动连接；Web 刷新即可，iOS/桌面端通过新版 Web 登录页自动完成。老客户端如仍停在原账号，可断开后重新连接。Android 首页“所有设备”和设备名称可切换聊天范围，筛选会在本机记住。每条发送和收到的消息显示手机本地日期、时间；历史消息使用原始时间，流式更新不会刷新时间。

协议及兼容范围见 [共享工作区](../docs/api/shared-workspace.md)。

### Android App 更新分发

Android 2.1.2 起可通过服务器静态更新清单发布同签名 APK，手机打开 App 即可收到更新提示。首次仍需手动安装一次支持更新清单的版本。配置和后续发布见 [Android 更新分发](ANDROID-UPDATES.md)，不需要重启聊天服务或改动数据库。
