# 学费密文中转：复用 OpenResty

此目录提供可自行部署的 OpenResty 密文中转配置，没有默认公共同步服务。2026-10-17 已在一处经授权的现有部署中验证 HTTPS 创建、覆盖、读取解密和无效上传保旧；测试文件已清除。具体结果见[验收记录](../../docs/acceptance.md)。

复用现有 OpenResty 的 DAV、Lua 与 HTTPS：`PUT /tuition` 接收密文，`GET /tuition` 读取最新文件。没有新的 Python 服务、systemd 单元、容器、监听端口或数据库。加解密仍在电脑与手机完成，服务器不保存 AES 密钥。旧 Python 中转实现已移除。

## 本地文件与接口

- `http.conf.example`：放在 `http {}` 中的独立读写 Bearer 令牌映射与精简访问日志格式；示例只有占位符。
- `location.conf`：放在既有 HTTPS `server {}` 中的精确 `/tuition` 路由。
- `validate.lua`：仅校验 version、nonce、ciphertext 的 JSON 包装与 Base64 长度，不能验证密文真实性。放在 rewrite 阶段，保留继承的 access 阶段钩子。
- `test_openresty.py`：在本机 Docker 内运行真实 OpenResty，使用容器原生 Linux 文件系统、虚构令牌与公开向量，自动清理测试容器和临时配置。Python 仅是测试工具，不在服务器运行。

`PUT` 只接受上传令牌及 `application/json`，上限 **2,000,000 字节**；首次保存返回 **201**，覆盖返回 **204**。LessonManager 已兼容两种状态。`GET` 只接受读取令牌，返回 200 和原始 envelope；未发布为 404。错误认证 401，不允许的方法 405，类型错误 415，无效 envelope 400，超限 413，文件系统失败通常 500。上传等待正文超时为 25 秒，客户端可能只看到断开连接，访问日志为 408。手机继续按原 GET/解密协议工作。

请求体先进入临时文件，经 Lua 检查后由原生 DAV 重命名到 `latest.json`。必须保留 `client_body_in_file_only clean`：Lua 提前读完的小请求否则可能只在内存，DAV 无法保存。`tmp` 与 `data` 必须处于**同一文件系统**，不能分别挂载。依据 [Nginx DAV 官方说明](https://nginx.org/en/docs/http/ngx_http_dav_module.html)，跨文件系统会改走复制，不能作为本方案的原子替换保证。

测试确认鉴权失败、格式/大小错误、中断、超时、临时文件写入失败及最终重命名失败保留旧文件；上传未完成时 GET 仍读取完整旧文件，提交后读取完整新文件。**“客户端报错”不等于“服务器没更新”**：替换完成后若响应丢失，新文件可能已经保存。下次可手动重新发布或在手机刷新。此方案也不承诺突然断电后的磁盘持久性；原子替换不是掉电备份。

## 部署示例与维护

以下以 1Panel 的常见目录为例。域名、容器名、worker 身份和实际挂载需按自己的环境核对；仓库不包含真实连接令牌或加密密钥。

| 服务器位置 | 改动 |
| --- | --- |
| `/opt/1panel/www/renewboard-tuition/` | 新目录，存放 `location.conf`、`validate.lua`、`data/`、`tmp/`；已有 `/opt/1panel/www → /www` 挂载可直接使用 |
| `/opt/1panel/www/conf.d/renewboard-tuition-http.conf` | 新增 http 级映射与日志格式；由示例生成，填两个独立随机令牌，root 所有、权限 600，不进 Git/命令行 |
| `/opt/1panel/www/conf.d/example.com.conf` | 在既有 server 中增加一行 `include /www/renewboard-tuition/location.conf;`，提供 `https://example.com/tuition` |
| 现有 OpenResty 日志目录 | 新增 `tuition-access.log`，只记时间、方法、状态和响应字节；错误沿用站点 error log，日志轮换使用已有 logrotate.timer，新增 /etc/logrotate.d/renewboard-tuition：每周或达到 1 MiB 时轮换，保留 4 份压缩历史（由现有定时器检查，不是实时硬上限） |

`data`、`tmp` 用现有 worker 的实际 UID/GID，权限 700；密文文件由 DAV 以 600 保存；配置与 Lua 只需 worker 可读。不要直接套用测试账户名或测试目录权限。目录在站点现有 `index` 根之外，不增加公开目录路由。AES 密钥仅存客户端；上传/读取令牌各自随机、互不相同。

建议部署顺序：

1. 检查当前可用内存、worker、文件系统、WAF 钩子与日志轮换；备份要修改的站点配置。核对 WAF 是否占用 rewrite 阶段、允许 PUT，并支持从临时文件读取 JSON。不要直接关闭全站 WAF；发生冲突先调整具体路由方案。
2. 新建上述文件及目录，只增加精确 `/tuition` 路由。该 location 独立放宽为 2 MB，现有站点的默认限制及其他接口不变。
3. 在现有 OpenResty 容器 内运行 `openresty -t`；通过后只做一次平滑 reload，不重建容器或重启业务进程。reload 会短暂同时保留新旧 worker，内存有瞬时增量；已有长连接可能延长旧 worker 退出时间，不能承诺零影响。
4. 验证现有网站及业务服务仍正常，验证 `/tuition` 空状态、分离鉴权及客户端测试发布/读取；观察内存与错误日志。真实学生数据只由用户手动发布。回退时撤销新增 include、恢复站点配置并 reload，保留已上传密文，不删除业务数据。

保存站点配置备份及实际回退步骤到自己的运维记录。回退时撤销新增 include 与独立配置，检查后平滑 reload；保留已上传密文目录，避免删除业务数据。

部署前检查内存余量；该方案没有新增常驻进程，但请求缓冲、Lua 校验及新旧 worker 在 reload 时重叠仍会增加内存，不能解决既有服务的内存问题。

## 加密协议 v1

UTF-8 JSON envelope（严格字段）：

```json
{"version":1,"nonce":"base64 12 random bytes","ciphertext":"base64 ciphertext followed by 16-byte GCM tag"}
```

AES-256-GCM，32 字节随机密钥，AAD 为 UTF-8 `RenewBoard tuition v1`。nonce 每次发布随机生成。明文：

```json
{"version":1,"publishedAt":"2026-10-17T06:00:00.000000Z","students":[{"id":"stable-student-id","name":"示例学生","status":"active","balanceCents":170000,"verified":true}]}
```

状态仅 `active|paused|archived`；金额为 CNY 整数分。正数为尚未上课的预收款，负数为已经上课的应收款，零为结清。未核对仍发送数值和 `verified:false`，手机不得冒充已核对或静默覆盖之前已确认余额。无课程、联系方式、家长备注。

发布端串行手动发送；手机按 `publishedAt` 拒绝旧数据覆盖。重复读取不能重复计款。若恢复旧账本后产生新发布时间，内容仍代表用户当前账本，需在 LessonManager 中先确认。

`test-vector.json` 是**公开测试密钥**和固定 nonce，仅用于 Python/Android 互通测试，绝不可用于真实配置。生产随机 nonce 不从该文件读取。

## 本地验证

```sh
# 需本机 Docker；脚本只绑定 127.0.0.1 的随机端口。
docker pull --platform linux/amd64 1panel/openresty:1.31.1.1-2-4-noble
python3 tools/tuition-relay/test_openresty.py
```

2026-10-17 使用与上轮服务器检查相同的镜像 tag `1panel/openresty:1.31.1.1-2-4-noble`，OpenResty 1.31.1.1。此次实际拉取的镜像索引摘要为 `sha256:1f3316a78af30040bd1e2bb4145957cae6492ecd0d525254ae761dbe289713bb`；记录测试材料身份，不据此改动服务器镜像或长期固定部署 tag。

本地验证证据与内存结果见[验收记录](../../docs/acceptance.md)。测试没有复制生产 HTTPS 证书、1Panel WAF 或业务负载，不能代替获准后的线上验收。测试中的约 100 名学生大小文件和 2 MB 极限文件仅模拟 envelope 大小；真实 AES-GCM 互通由公开固定向量与 Android/LessonManager 测试验证。
