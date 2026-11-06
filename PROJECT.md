# 测试 WVP

## 当前部署

- 2026-11-06 在 `tencent-main` 完成部署和公网接入。应用版本为 `2.7.4`，运行提交为 `d1da553757764c5a5326c5eab3dd5a824de02977`，已核对运行 API、发布清单与 GitHub 提交。[私有仓库](https://github.com/JokerMok/test-wvp)
- 源码以正式环境归档为基线，纳入 2026-11-05 的 UDP 修订。测试环境使用独立空库和新生成的凭证。来源及原归档校验值见 `SOURCE.md`。
- 发布包 SHA-256 为 `5a8b9af78919c95b497455fd8fb2517a3d8e50e3ca4dfa4ccf17f38fbb1ef9da`，原件留在 Mac 项目的 `releases/` 下。服务器以 `current/RELEASE.json` 标识版本，`current/SHA256SUMS` 覆盖 JAR、数据库初始化 SQL 和正式静态文件。JAR SHA-256 为 `27db37756e127dd431e6e5ff40472e46cc1c8c96cea010a248a775a555dd1cb3`。
- 项目目录为 `/srv/apps/test-wvp`。`test-wvp{,-db,-cache,-media}.service` 均已启动并启用开机自启。WVP 使用宿主 Java 21，MySQL 8.4.11 与 Redis 8.0.5 为独立实例。ZLMediaKit 使用官方 `linux/amd64` 镜像 `sha256:f0022a9ebe3085f2710a5a9312975564f22a8c8449be88217b55ce7e3cf9539b` 的校验后运行文件，来源为腾讯云镜像端点。

| 组件 | 服务器地址 |
| --- | --- |
| Caddy 内部入口 | `127.0.0.1:18083` |
| WVP HTTP | `127.0.0.1:18978` |
| SIP | 公网 `175.178.236.56:5060` TCP/UDP，主机监听 `10.1.0.12:5060` |
| MySQL 与 Redis | `127.0.0.1:18979`，`127.0.0.1:18977` |
| ZLMediaKit | `127.0.0.1:18980` HTTP，`18983` RTMP，`18984` RTSP，`18985` RTP。国标 UDP 收流范围 `30000–30500`，配对 RTCP 最多到 `30501` |

## 访问

打开 [公网测试入口](https://wvp.shizibandu.icu/)，先通过 Caddy 入口认证，再登录 WVP。两组凭证存放于服务器 `/srv/apps/test-wvp/shared/config/access.env`，仅 root 可读。

用户在腾讯云控制台添加了 `wvp A 175.178.236.56`（默认线路，TTL 600 秒），并确认主域名的腾讯云备案接入正常。两台 DNSPod 权威服务器返回一致。Mac 实测 HTTP 308 跳转到 HTTPS，Let's Encrypt 证书受信任，有效期至 2027-02-04。

SSH 隧道可用于内部访问。建立隧道后打开 [内部测试入口](http://127.0.0.1:28083/)。

```sh
ssh -N -L 127.0.0.1:28083:127.0.0.1:18083 tencent-main
```

切换网页入口用 `sudo /srv/apps/test-wvp/deploy/enable-entry.sh public`，切回仅内部访问用 `sudo /srv/apps/test-wvp/deploy/enable-entry.sh internal`。启用前的内部配置备份为 `/srv/ops/rollback/caddy/test-wvp.FREfV1VY`。WVP HTTP、数据库和媒体管理端口均绑定本机回环地址。

国标域和端口沿用线上包 `docker/.env` 的 `SIP_Domain` 和 `SIP_Port`，注册参数取自 `docker/wvp/wvp/application-docker.yml`。测试编号按用户指定配置，生效配置保存在服务器 `shared/config/application.yml`。页面隐藏已设置的测试 SIP 密码。

腾讯云将公网 IP `175.178.236.56` 映射到网卡 `eth0` 的 `10.1.0.12`。SIP 监听该网卡的 TCP/UDP 5060。主机 UFW 和腾讯云轻量实例防火墙均已允许两种协议的 IPv4 入站。服务通过 `/etc/systemd/system/test-wvp.service.d/10-sip-public.conf` 接收公网 IPv4，并让 SIP 信令使用公网地址。

回退 SIP 公网接入时，先删除本次新增的云防火墙规则，再执行以下主机命令。
如果同时启用了下文的级联收流，先回退收流配置。

```sh
sudo ufw delete allow in on eth0 from 0.0.0.0/0 to 10.1.0.12 port 5060 proto tcp
sudo ufw delete allow in on eth0 from 0.0.0.0/0 to 10.1.0.12 port 5060 proto udp
sudo install -o root -g test-wvp -m 0640 /srv/apps/test-wvp/shared/backups/sip-public-20261106T023849Z/application.yml /srv/apps/test-wvp/shared/config/application.yml
sudo rm /etc/systemd/system/test-wvp.service.d/10-sip-public.conf
sudo systemctl daemon-reload
sudo systemctl restart test-wvp.service
```

## 正式平台级联收流

测试服支持 UDP 和 TCP 被动收流。点播时向正式平台通告 `175.178.236.56`，从 `30000–30039` 分配媒体端口，UDP 的 RTP 和 RTCP 使用一对相邻端口。主机 UFW 仅允许来源 `47.106.174.114/32` 访问这段 TCP、UDP 端口。腾讯云实例防火墙需允许同一来源及端口范围。

在 `shared/config/application.yml` 设置 `media.sdp-ip` 为公网 IP、`media.rtp.port-range` 为 `30000,30039`、`media.http-port` 为 `18981`。在 `shared/config/media/config.ini` 的 `[rtp_proxy]` 设置 `port_range=30000-30039`。将仓库的 `deploy/tencent/internal.caddy` 同步到服务器项目的 `deploy/internal.caddy`。Caddy 的本机入口 `127.0.0.1:18981` 将媒体 API 代理到 `127.0.0.1:18980`，为创建媒体端口的请求指定网卡地址 `10.1.0.12`。ZLMediaKit 在该地址直接接收 RTP 和发送 RTCP。把 `deploy/tencent/systemd/test-wvp-media.service.d/10-cascade-rtp.conf` 安装到同名的 `/etc/systemd/system/` 子目录，允许该媒体服务与正式平台通信。旧 TCP 转发 socket 已停用，因为媒体服务会在同一网卡上直接监听。

```bash
sudo ufw allow in on eth0 from 47.106.174.114 to 10.1.0.12 port 30000:30039 proto tcp comment 'test-wvp cascade RTP TCP'
sudo ufw allow in on eth0 from 47.106.174.114 to 10.1.0.12 port 30000:30039 proto udp comment 'test-wvp cascade RTP UDP'
sudo systemctl daemon-reload
sudo /srv/apps/test-wvp/deploy/enable-entry.sh public
sudo systemctl restart test-wvp-media.service test-wvp.service
```

如需切回此前的 TCP 转发配置，先将设备的收流模式设为 TCP 被动，再在服务器执行以下命令。媒体进程退出后才能重新启用占用同一端口的 TCP 转发。

```bash
app=/srv/apps/test-wvp
backup=$app/shared/backups/rtp-udp-20261106T044127Z
sudo systemctl stop test-wvp.service test-wvp-media.service
sudo cp -a "$backup/application.yml" "$app/shared/config/application.yml"
sudo cp -a "$backup/internal.caddy" "$app/deploy/internal.caddy"
sudo unlink /etc/systemd/system/test-wvp-media.service.d/10-cascade-rtp.conf
sudo ufw delete allow in on eth0 from 47.106.174.114 to 10.1.0.12 port 30000:30039 proto udp
sudo systemctl daemon-reload
sudo systemctl enable --now test-wvp-rtp@{30000..30038..2}.socket
sudo "$app/deploy/enable-entry.sh" public
sudo systemctl start test-wvp-media.service test-wvp.service
```

如需完全关闭级联媒体入口，再停用这些 TCP 转发 socket，并删除主机与云防火墙中该来源的媒体端口规则。

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

- `shared/config` 保存应用、MySQL、Redis、媒体、入口认证与 JWK，`shared/data` 保存 MySQL、Redis 和服务运行数据，`shared/uploads` 保存快照和录像。WVP 同时写入 journald 和 `shared/data/wvp/logs`。历史文件最多保留 7 天，滚动文件总量上限 32 MB。媒体日志在 `shared/data/media/logs`。
- `/etc/server-ops/backup.d/test-wvp.sh` 已接入现有 `server-config-backup.timer`。钩子使用 MySQL `mysqldump --single-transaction`、Redis RDB 快照以及上传目录归档。此次单独执行钩子，通过校验及隔离 MySQL 恢复，恢复库含 22 张 InnoDB 表与 1 个测试账号。验证备份位于 `shared/backups/verify-20261106.ZsyR5mRH/test-wvp`。定时任务下次运行会纳入项目钩子。

运维中心的历史日志由 `deploy/tencent/logback.xml` 的 `RollingFile` 提供，实时日志使用同一配置的 `WebLog` 推送。Caddy 只代理日志列表、合法文件名的读取和 `/channel/log` WebSocket 握手。公网仍要求 Caddy 入口认证和 WVP 登录。2026-11-06 从 journald 补入 1,313 条既有 WVP 记录。已验证公网历史列表、文件预览与下载、实时日志帧，以及未登录的拒绝响应。

日志配置回退文件在 `/srv/apps/test-wvp/shared/backups/log-module-20261106T042838Z/`。将其中的 `logback.xml` 和 `internal.caddy` 恢复到服务器项目的 `deploy/`，用 `deploy/enable-entry.sh public` 重载当前公网入口，再重启 `test-wvp.service`。已生成的历史文件保留在数据目录。

## 定向验证

服务器和内部 Caddy 入口已验证登录、版本 API、国标设备列表、媒体节点在线、敏感管理路由拦截。Chrome 经 SSH 隧道完成表单登录并显示空设备表，WVP API 保持同源，Caddy 的内容安全策略拦截了播放器统计请求。快速离开仪表盘时，未返回的图表请求可触发 `setData` 异常，设备页和查询 API 仍正常。

公网 Chrome 已通过入口认证和 WVP 登录，登录 API 请求保持同源。公网 API 实测返回当前版本，国标设备列表为空，媒体节点在线。

从 Mac 向公网 IP 的 TCP 和 UDP 5060 发送合成 SIP REGISTER，两种协议均返回预期国标域的 401 Digest 挑战。ZLMediaKit 在先前的内部检查中分配了位于规定范围的本机 RTP/RTCP UDP 配对端口。真实摄像头、天影云级联和长时播放待实测。

正式平台已注册并同步 3 个通道。原点播收到 SIP 200 OK，测试服通告的收流地址为 `127.0.0.1`。2026-11-06 用户添加腾讯云 TCP 规则后，复测通道尾号 `0001`。正式平台连接测试服公网动态端口，WVP 点播 API 成功，ZLMediaKit 报告 H.265 视频轨道就绪，测试域名 HTTPS 返回有效 FLV 数据。

同日 12:43，在媒体服务直接监听网卡后，同一通道用 UDP 和 TCP 被动分别点播，均收到 H.265，公网 HTTPS-FLV 均通过。UDP 抓包见来自正式平台的 321 个 RTP 包和测试服发出的 1 个 RTCP RR。复测后恢复设备为 UDP。

点播修复前的 SIP 抓包另见正式平台在测试服回复心跳 200 OK 后发送注销，20 秒后重新注册。需要正式平台日志确认触发原因。验收范围为一路短时点播。
