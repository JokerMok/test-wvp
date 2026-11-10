# WVP 测试项目源码来源

- 应用版本 2.7.4，正式环境源码基线为 `03a809928588a4ecb4a5b7403e221a370eca913b`。
- 初始仓库纳入 2026-11-05 UDP 修订，来源归档 SHA-256 为 `e517cd6f0899b6b37d5e3bd43cc84869d7eb610fa1acf61b269897b4b06f446d`。
- 2026-11-10 同步 11 月 9 日完整正式更新，包含收流超时 BYE 会话清理、网页播放地址、INVITE 拒绝日志汇总、24 小时登录及 60 分钟 RTP 超时。完整交付包 SHA-256 为 `1f8f4ae46cc8df8b05764820bdcaeac16535e29d30c53254d0c5c0d4c464d8c4`，说明见 [正式更新记录](docs/production-update-20261109.md)。
- 本轮更新的 10 个 Java 文件、登录 Cookie 和 Nginx 模板与正式交付包逐字节相同。YAML 和媒体配置只合入对应参数，保留仓库已有环境示例。腾讯云初始化配置同步登录 1440 分钟和 RTP 超时 3600 秒。
- 腾讯云专用的 HTTPS 媒体地址、Git 版本读取、配置接口脱敏及登录映射继续保留，分别位于 `ZLMMediaNodeServerService`、`GitUtil`、`ServerController`、`UserController`。Caddy 入口、构建和版本切换脚本保持原有实现。
- `docker/` 保留原项目的部署示例。按需复制 `.env.example` 到本地 `.env` 并配置环境变量。腾讯云测试环境以 `PROJECT.md` 及 `deploy/tencent/` 为准。
- 正式环境归档中的运行配置、凭证、数据库、Redis 文件、录像及备份留在原工作区。测试环境使用独立配置、凭证和空库。
