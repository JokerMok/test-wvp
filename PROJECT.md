# 测试 WVP

## 自动续播与日志修复（2026-11-10，腾讯云已部署）

腾讯云前后端已更新到 `06489f6634aa5375d32bc98cc2f3816f6a279a61`，包含点播请求释放、实时日志和后台自动续播。默认60秒，人工停止暂停，任务初始全部关闭。新增表已增量创建，现有配置、账号、JWK、两台UDP设备和5条通道保留；登录24小时、RTP超时3600秒已实测回读。公网登录、实时新日志、任务API及版本一致性验证通过，未制造实际摄像头断流；正式环境服务器未更新。发布包、回退及验证范围见 [腾讯云部署记录](docs/deployment/20261110/部署与验证.md)。

## 源码更新（2026-11-10）

仓库已合入 11 月 9 日正式修订：UDP 收流超时后的原会话 BYE 清理、Docker 网页播放地址修复、INVITE 拒绝日志明细与汇总、登录 1440 分钟及 Cookie 1 天、ZLM RTP 超时 3600 秒。来源及验证范围见 [正式更新记录](docs/production-update-20261109.md)。腾讯云 HTTPS 媒体出口、登录、日志和入口配置继续沿用。

本次代码已于11月10日部署到腾讯云，当前版本见上节。其他环境发布时在现有 `shared/config/application.yml` 合并 `user-settings.login-timeout: 1440`，在 `shared/config/media/config.ini` 的 `[rtp_proxy]` 段合并 `timeoutSec=3600`，按既有流程更新服务后重新登录。该超时用于连续收不到 RTP 时的回收，首次点播等待和无人观看回收分别生效。

`bootstrap.sh` 已设置相同的首次初始化参数；已初始化的现场继续使用现有配置文件、数据和 JWK。正式 Docker 环境的 Nginx 修订通过模板生效；腾讯云继续使用现有 Caddy HTTPS 入口。

## 当前部署

- 2026-11-10 更新 `tencent-main`，前后端均为 WVP 2.7.4 / `06489f6634aa5375d32bc98cc2f3816f6a279a61`。版本以 `current/RELEASE.json` 为准。[源码仓库](https://github.com/JokerMok/test-wvp)
- 源码以正式环境归档为基线，纳入 2026-11-05 的 UDP 修订。测试环境使用独立数据库和凭证。来源及原归档校验值见 `SOURCE.md`。
- 当前完整发布包 SHA-256 为 `ae4f0f217da54d7837c05b936261fe8ac7318a9ebf40966c225409016799dc1f`，在 Mac 的 `releases/test-wvp-06489f6634aa5375d32bc98cc2f3816f6a279a61.tar.gz`；服务器 `current/SHA256SUMS` 覆盖 JAR、初始化与增量SQL和网页。`previous` 保留前端 `75f8991` / 后端 `d1da553` 的上一运行版本。
- 项目目录为 `/srv/apps/test-wvp`。`test-wvp{,-db,-cache,-media}.service` 均已启动并启用开机自启。WVP 使用宿主 Java 21，MySQL 8.4.11 与 Redis 8.0.5 为独立实例。ZLMediaKit 使用官方 `linux/amd64` 镜像 `sha256:f0022a9ebe3085f2710a5a9312975564f22a8c8449be88217b55ce7e3cf9539b` 的校验后运行文件，来源为腾讯云镜像端点。

| 组件 | 服务器地址 |
| --- | --- |
| Caddy 内部入口 | `127.0.0.1:18083` |
| WVP HTTP | `127.0.0.1:18978` |
| SIP | 公网 `175.178.236.56:5060` TCP/UDP，主机监听 `10.1.0.12:5060` |
| MySQL 与 Redis | `127.0.0.1:18979`，`127.0.0.1:18977` |
| ZLMediaKit | `127.0.0.1:18980` HTTP，`18983` RTMP，`18984` RTSP，`18985` RTP。正式平台级联媒体端口 `30000–30039` |

## 访问

打开公网测试入口后，直接使用 WVP 账号登录。测试账号的凭证存放在服务器 `/srv/apps/test-wvp/shared/config/access.env`，仅 root 可读。[公网测试入口](https://wvp.shizibandu.icu/)

用户在腾讯云控制台添加了 `wvp A 175.178.236.56`（默认线路，TTL 600 秒），并确认主域名的腾讯云备案接入正常。两台 DNSPod 权威服务器返回一致。Mac 实测 HTTP 308 跳转到 HTTPS，Let's Encrypt 证书受信任，有效期至 2027-02-04。

SSH 隧道可用于内部访问。建立隧道后打开 [内部测试入口](http://127.0.0.1:28083/)。

```sh
ssh -N -L 127.0.0.1:28083:127.0.0.1:18083 tencent-main
```

切换网页入口用 `sudo /srv/apps/test-wvp/deploy/enable-entry.sh public`，切回仅内部访问用 `sudo /srv/apps/test-wvp/deploy/enable-entry.sh internal`。启用前的内部配置备份为 `/srv/ops/rollback/caddy/test-wvp.FREfV1VY`。WVP HTTP、数据库和媒体管理端口均绑定本机回环地址。

国标域和端口沿用线上包 `docker/.env` 的 `SIP_Domain` 和 `SIP_Port`。测试编号按用户指定配置，SIP 注册密码保存在服务器 `shared/config/application.yml` 的 `sip.password`。登录后到「设备接入 → 国标设备 → 接入信息」查看当前值。更改密码后，需在发起级联的正式平台同步该条配置的「SIP认证密码」。

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

测试服支持 UDP 和 TCP 被动收流。点播时通告 `175.178.236.56`，从 `30000–30039` 分配媒体端口，UDP 的 RTP 和 RTCP 使用一对相邻端口。主机 UFW 允许正式平台 `47.106.174.114/32` 访问这段 TCP、UDP 端口。腾讯云实例防火墙需覆盖同一来源及端口范围。摄像机直连规则见下节。

在 `shared/config/application.yml` 设置 `media.sdp-ip` 为公网 IP、`media.rtp.port-range` 为 `30000,30039`、`media.http-port` 为 `18981`。在 `shared/config/media/config.ini` 的 `[rtp_proxy]` 设置 `port_range=30000-30039`。将仓库的 `deploy/tencent/internal.caddy` 同步到服务器项目的 `deploy/internal.caddy`。Caddy 的本机入口 `127.0.0.1:18981` 将媒体 API 代理到 `127.0.0.1:18980`，为创建媒体端口的请求指定网卡地址 `10.1.0.12`。ZLMediaKit 在该地址直接接收 RTP 和发送 RTCP。把 `deploy/tencent/systemd/test-wvp-media.service.d/10-cascade-rtp.conf` 安装到同名的 `/etc/systemd/system/` 子目录，允许该媒体服务与正式平台通信。旧 TCP 转发 socket 已停用，因为媒体服务会在同一网卡上直接监听。

```bash
sudo ufw allow in on eth0 from 47.106.174.114 to 10.1.0.12 port 30000:30039 proto tcp comment 'test-wvp cascade RTP TCP'
sudo ufw allow in on eth0 from 47.106.174.114 to 10.1.0.12 port 30000:30039 proto udp comment 'test-wvp cascade RTP UDP'
sudo systemctl daemon-reload
sudo /srv/apps/test-wvp/deploy/enable-entry.sh public
sudo systemctl restart test-wvp-media.service test-wvp.service
```

如需切回此前的 TCP 转发配置，先撤回下节的摄像机直连规则，再将设备的收流模式设为 TCP 被动并执行以下命令。媒体进程退出后才能重新启用占用同一端口的 TCP 转发。

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

## 摄像机 UDP 直连

尾号 `0004` 的摄像机使用公网源 `14.26.144.35` 向测试服发送 RTP。2026-11-06 在腾讯云实例防火墙放行该来源后，为主机 UFW 增加来源 `14.26.144.35/32` 的 UDP `30000–30039` 入站规则，同时更新 `test-wvp-media.service` 的来源白名单。

```sh
sudo ufw allow in on eth0 from 14.26.144.35/32 to 10.1.0.12 port 30000:30039 proto udp comment 'test-wvp camera 0004 RTP UDP'
printf '[Service]\nIPAddressAllow=14.26.144.35/32\n' | sudo tee /etc/systemd/system/test-wvp-media.service.d/20-camera-0004-rtp.conf >/dev/null
sudo systemctl daemon-reload
sudo systemctl restart test-wvp-media.service test-wvp.service
```

回退时删除上述 UFW 规则和 `20-camera-0004-rtp.conf`，重载 systemd 后重启这两个服务。变更前的规则记录在 `shared/backups/camera-0004-rtp-20261106T063729Z/`。腾讯云实例防火墙的摄像机来源规则在控制台独立维护。

实测测试站点直接点播时收到来自该 IP 的 RTP，ZLMediaKit 识别出 H.265 视频。Mac 通过测试域名拉取 HTTPS-FLV，识别到 `2560×1440` 的视频轨道。此轮验证覆盖摄像机到测试服的 UDP 直连和测试域名的视频出口。

同日 14:46，用户从正式 WVP 点播测试服共享的通道 `0004`，确认看到画面。按本次 SIP 协商的 SSRC 统计，测试服在 UDP 30008 收到 3310 个摄像机 RTP 包，并从 UDP 30502 向正式平台 UDP 30128 发送了 2899 个 RTP 包。正式平台返回 4 个 RTCP 接收报告，ZLMediaKit 识别到 H.265 2560×1440、15 FPS。验收覆盖这一路约 48 秒的级联媒体抓包。

## 正式 WVP 持续播放对照

2026-11-06 14:54 起，将腾讯云测试 WVP 作为下级，正式 WVP 作为上级。正式 WVP 在 14:55:05 记录尾号 `0004`「流无人观看」并关闭媒体，测试服当时仍向正式端发送 RTP。15:09:34 的另一轮媒体注销与正式环境运维重启重合，独立 HTTP-FLV 解码在 2 分 18 秒结束。重启后数次重试中，正式 WVP 的 `ts` 无人观看回调与新流注册几乎同时发生，摄像机 `0004` 和天影云 `1114` 均有相同记录。

向正式平台发送 RTP 使用本机 UDP `30502–30550`，配对的反馈端口为 `30503–30551`。此前正式端的 RTCP 包到达网卡后被 UFW 拦截。测试服已增加以下规则，复测时主机放行计数增加。

```sh
sudo ufw allow in on eth0 from 47.106.174.114/32 to 10.1.0.12 port 30503:30551 proto udp comment 'test-wvp formal RTCP feedback'
```

回退用 `sudo ufw delete allow in on eth0 from 47.106.174.114/32 to 10.1.0.12 port 30503:30551 proto udp`。变更前的规则记录在 `shared/backups/cascade-rtcp-20261106T070311Z/`。

正式环境运维结束后，15:16:32 重新点播 `0004`，用户在近 7 分钟时确认预览仍在播放。独立 FFmpeg 客户端通过同一条正式端 HTTP-FLV 连接解码 480 秒，于 15:25:28 结束验证。腾讯云网卡在 15:17:20 至 15:26:17 捕获两段 RTP，其中摄像机入流 45,822 包、向正式端出流 41,877 包。正式端持续返回 106 个 RTCP 接收报告。15:20:15 至 15:20:30，正式端报告累计丢包增至 941，随后恢复，校验连接持续解码。15:25:32 摄像机续注册成功，两段 RTP 和正式端接收报告继续。验收覆盖该摄像机经腾讯云测试服向正式 WVP 的 UDP 级联。天影云实际链路待单独长测。

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

在服务器用 `sudo /srv/apps/test-wvp/deploy/manage.sh status` 查看状态，用 `sudo /srv/apps/test-wvp/deploy/manage.sh rollback` 切回 `previous` 所指的已验证版本，当前为 `d1da553757764c5a5326c5eab3dd5a824de02977`。数据库和文件恢复按备份单独处理。

如需同时撤回此次 SIP 密码及入口变更，先确认 `previous` 仍指向上述版本，再执行以下命令恢复配置及发布。

```bash
app=/srv/apps/test-wvp
backup=$app/shared/backups/sip-web-access-20261106T055141Z
sudo install -o root -g test-wvp -m 0640 "$backup/application.yml" "$app/shared/config/application.yml"
sudo install -o root -g root -m 0644 "$backup/internal.caddy" "$backup/public.caddy" "$app/deploy/"
sudo "$app/deploy/enable-entry.sh" public
sudo "$app/deploy/manage.sh" rollback
```

## 数据和备份

- `shared/config` 保存应用、MySQL、Redis、媒体配置与 JWK，`shared/data` 保存 MySQL、Redis 和服务运行数据，`shared/uploads` 保存快照和录像。WVP 同时写入 journald 和 `shared/data/wvp/logs`。历史文件最多保留 7 天，滚动文件总量上限 32 MB。媒体日志在 `shared/data/media/logs`。
- `/etc/server-ops/backup.d/test-wvp.sh` 已接入现有 `server-config-backup.timer`。钩子使用 MySQL `mysqldump --single-transaction`、Redis RDB 快照以及上传目录归档。此次单独执行钩子，通过校验及隔离 MySQL 恢复，恢复库含 22 张 InnoDB 表与 1 个测试账号。验证备份位于 `shared/backups/verify-20261106.ZsyR5mRH/test-wvp`。定时任务下次运行会纳入项目钩子。

运维中心的历史日志由 `deploy/tencent/logback.xml` 的 `RollingFile` 提供，实时日志使用同一配置的 `WebLog` 推送。Caddy 只代理日志列表、合法文件名的读取和 `/channel/log` WebSocket 握手。公网日志和媒体资源要求 WVP 登录。2026-11-06 从 journald 补入 1,313 条既有 WVP 记录。已验证公网历史列表、文件预览与下载、实时日志帧，以及未登录的拒绝响应。

日志配置回退文件在 `/srv/apps/test-wvp/shared/backups/log-module-20261106T042838Z/`。将其中的 `logback.xml` 和 `internal.caddy` 恢复到服务器项目的 `deploy/`，用 `deploy/enable-entry.sh public` 重载当前公网入口，再重启 `test-wvp.service`。已生成的历史文件保留在数据目录。

## 定向验证

服务器和内部 Caddy 入口已验证登录、版本 API、国标设备列表、媒体节点在线、敏感管理路由拦截。Chrome 经 SSH 隧道完成表单登录并显示空设备表，WVP API 保持同源，Caddy 的内容安全策略拦截了播放器统计请求。快速离开仪表盘时，未返回的图表请求可触发 `setData` 异常，设备页和查询 API 仍正常。

全新浏览器会话从公网入口直接打开 WVP 登录页，登录后在「国标设备 → 接入信息」显示当前 SIP 注册密码。公网验证了未登录无法读取接入配置、日志和受保护的媒体资源。合成 SIP REGISTER 在摘要认证后返回 200。此次切换后，正式平台的注册请求收到 403，需同步该条级联的 SIP 认证密码后复测。

从 Mac 向公网 IP 的 TCP 和 UDP 5060 发送合成 SIP REGISTER，两种协议均返回预期国标域的 401 Digest 挑战。ZLMediaKit 在先前的内部检查中分配了位于规定范围的本机 RTP/RTCP UDP 配对端口。天影云级联和长时播放待实测。

正式平台已注册并同步 3 个通道。原点播收到 SIP 200 OK，测试服通告的收流地址为 `127.0.0.1`。2026-11-06 用户添加腾讯云 TCP 规则后，复测通道尾号 `0001`。正式平台连接测试服公网动态端口，WVP 点播 API 成功，ZLMediaKit 报告 H.265 视频轨道就绪，测试域名 HTTPS 返回有效 FLV 数据。

同日 12:43，在媒体服务直接监听网卡后，同一通道用 UDP 和 TCP 被动分别点播，均收到 H.265，公网 HTTPS-FLV 均通过。UDP 抓包见来自正式平台的 321 个 RTP 包和测试服发出的 1 个 RTCP RR。复测后恢复设备为 UDP。

点播修复前的 SIP 抓包另见正式平台在测试服回复心跳 200 OK 后发送注销，20 秒后重新注册。需要正式平台日志确认触发原因。验收范围为一路短时点播。
