# WVP 测试项目源码来源

- 应用版本 2.7.4，正式环境源码基线为 `03a809928588a4ecb4a5b7403e221a370eca913b`。
- 本仓库保留正式归档中的 InviteRequestProcessor、SipUtils 两项源码差异，并纳入 2026-11-05 UDP 交付包的四个 Java 文件及三处 Compose/UDP 配置改动。四个 Java 文件和 Compose 文件按交付包校验值核对。两份示例 YAML 仅合入 UDP 多端口开关。
- 原始修订归档 SHA-256 为 `e517cd6f0899b6b37d5e3bd43cc84869d7eb610fa1acf61b269897b4b06f446d`。
- `docker/` 保留原项目的部署示例。按需复制 `.env.example` 到本地 `.env` 并配置环境变量。腾讯云测试环境以 `PROJECT.md` 及 `deploy/tencent/` 为准。
- 正式环境归档中的运行配置、凭证、数据库、Redis 文件、录像及备份留在原工作区。测试环境使用独立配置、凭证和空库。
