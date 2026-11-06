# 测试 WVP

## 当前部署

- 2026-11-06 在 `tencent-main` 完成部署和公网接入。应用版本为 `2.7.4`，运行提交为 `d1da553757764c5a5326c5eab3dd5a824de02977`，已核对运行 API、发布清单与 GitHub 提交。[私有仓库](https://github.com/JokerMok/test-wvp)
- 源码以正式环境归档为基线，纳入 2026-11-05 的 UDP 修订。测试环境使用独立空库和新生成的凭证。来源及原归档校验值见 `SOURCE.md`。
- 发布包 SHA-256 为 `5a8b9af78919c95b497455fd8fb2517a3d8e50e3ca4dfa4ccf17f38fbb1ef9da`，原件留在 Mac 项目的 `releases/` 下。服务器以 `current/RELEASE.json` 标识版本，`current/SHA256SUMS` 覆盖 JAR、数据库初始化 SQL 和正式静态文件。JAR SHA-256 为 `27db37756e127dd431e6e5ff40472e46cc1c8c96cea010a248a775a555dd1cb3`。
- 项目目录为 `/srv/apps/test-wvp`。`test-wvp{,-db,-cache,-media}.service` 均已启动并启用开机自启。WVP 使用宿主 Java 21，MySQL 8.4.11 与 Redis 8.0.5 为独立实例。ZLMediaKit 使用官方 `linux/amd64` 镜像 `sha256:f0022a9ebe3085f2710a5a9312975564f22a8c8449be88217b55ce7e3cf9539b` 的校验后运行文件，来源为腾讯云镜像端点。

| 组件 | 服务器地址 |
| --- | --- |
| Caddy 内部入口 | `127.0.0.1:18083` |
| WVP HTTP 与 SIP | `127.0.0.1:18978`，`127.0.0.1:18982` TCP/UDP |
| MySQL 与 Redis | `127.0.0.1:18979`，`127.0.0.1:18977` |
| ZLMediaKit | `127.0.0.1:18980` HTTP，`18983` RTMP，`18984` RTSP，`18985` RTP。国标 UDP 收流范围 `30000–30500`，配对 RTCP 最多到 `30501` |

## 访问

打开 [公网测试入口](https://wvp.shizibandu.icu/)，先通过 Caddy 入口认证，再登录 WVP。两组凭证存放于服务器 `/srv/apps/test-wvp/shared/config/access.env`，仅 root 可读。

用户在腾讯云控制台添加了 `wvp A 175.178.236.56`（默认线路，TTL 600 秒），并确认主域名的腾讯云备案接入正常。两台 DNSPod 权威服务器返回一致。Mac 实测 HTTP 308 跳转到 HTTPS，Let's Encrypt 证书受信任，有效期至 2027-02-04。

SSH 隧道可用于内部访问。建立隧道后打开 [内部测试入口](http://127.0.0.1:28083/)。

```sh
ssh -N -L 127.0.0.1:28083:127.0.0.1:18083 tencent-main
```

切换公网入口用 `sudo /srv/apps/test-wvp/deploy/enable-entry.sh public`，切回仅内部访问用 `sudo /srv/apps/test-wvp/deploy/enable-entry.sh internal`。本次启用前的内部配置备份为 `/srv/ops/rollback/caddy/test-wvp.FREfV1VY`。设备 SIP、RTP 和管理端口均限本机访问。

## 更新与回退

Mac 使用 Git 2.17 以上、JDK 21、Maven 3.9、Node.js 和 Python 3.11 以上。本次使用 Maven 3.9.9、Node.js 25.9.0 和 npm 11.12.1。进入本仓库后，构建脚本从 GitHub `main` 可达的指定提交建包，并使用前端锁文件。

```sh
git fetch origin main
sha=$(git rev-parse origin/main)
package="/tmp/test-wvp-$sha.tar.gz"
WVP_GIT=/usr/bin/git deploy/tencent/build.sh "$sha" "$package"
ssh tencent-main "sudo /srv/apps/test-wvp/deploy/manage.sh install $sha" < "$package"
```

更新前确认新发布解压后仍有至少 2 GiB 可用空间，供现有备份任务运行。本轮为满足门槛清理了可重建的 APT 下载包和索引缓存，下次安装依赖前运行 `apt-get update`。脚本校验发布文件和数据库初始化脚本，更新前备份，健康检查通过后记录 `previous`。发布失败会恢复已有可用版本。若初始化 SQL 或其他迁移行为发生变化，先单独评估迁移和数据恢复。

在服务器用 `sudo /srv/apps/test-wvp/deploy/manage.sh status` 查看状态。首次部署暂无 `previous`。以后通过 `sudo /srv/apps/test-wvp/deploy/manage.sh rollback` 切回上一份通过验证且兼容当前数据库的应用发布。数据库和文件恢复按备份单独处理。

## 数据和备份

- `shared/config` 保存应用、MySQL、Redis、媒体、入口认证与 JWK，`shared/data` 保存 MySQL、Redis 和服务运行数据，`shared/uploads` 保存快照和录像。Java 日志由 journald 管理，媒体日志在 `shared/data/media/logs`。
- `/etc/server-ops/backup.d/test-wvp.sh` 已接入现有 `server-config-backup.timer`。钩子使用 MySQL `mysqldump --single-transaction`、Redis RDB 快照以及上传目录归档。此次单独执行钩子，通过校验及隔离 MySQL 恢复，恢复库含 22 张 InnoDB 表与 1 个测试账号。验证备份位于 `shared/backups/verify-20261106.ZsyR5mRH/test-wvp`。定时任务下次运行会纳入项目钩子。

## 定向验证

服务器和内部 Caddy 入口已验证登录、版本 API、国标设备列表、媒体节点在线、敏感管理路由拦截。Chrome 经 SSH 隧道完成表单登录并显示空设备表，WVP API 保持同源，Caddy 的内容安全策略拦截了播放器统计请求。快速离开仪表盘时，未返回的图表请求可触发 `setData` 异常，设备页和查询 API 仍正常。

公网 Chrome 已通过入口认证和 WVP 登录，登录 API 请求保持同源。公网 API 实测返回当前版本，国标设备列表为空，媒体节点在线。

合成 SIP REGISTER 收到 401 Digest 挑战，ZLMediaKit 分配了位于规定范围的本机 RTP/RTCP UDP 配对端口。真实摄像头、天影云级联和长时播放待实测。
