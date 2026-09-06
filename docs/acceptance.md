# 1.0.0 本地验收

2026-09-06，已完成本地源码、签名 release 构建与 API35 ARM64 模拟器验收。未连接真机、未配置真实坚果云。

## 实际验证结果

| 范围 | 结果 |
| --- | --- |
| JVM 业务测试 | 14/14，通过：周/月/年整数周期、月末/闰年锚点、提前续费、赠送、不同权益期限、套餐只记一次成本、分币种、归档删除保留付款、完整/缺字段/损坏/未知版本 JSON |
| WebDAV 模拟 HTTP 测试 | 11/11，通过：非空完整业务 JSON 的 PUT→GET核验→MOVE，认证头、目录列表、有效恢复内容、上传/核验/MOVE/下载失败、只保留最近10份成功自动备份、手动不删、过期临时文件回收 |
| 真实 Room 设备测试 | 5/5，通过：空库、文件数据库重开持久化、编辑归档删除、坏恢复不改变本机、有效整体恢复、并发提醒 claim 与重开去重 |
| 真实 Compose 设备测试 | 3/3，通过：新增编辑、Activity重建、归档删除后账本仍在、无效输入纠正、Jan31→Feb29→提前续费Mar31→赠送7天Apr7且付款数不变 |
| 系统通知设备测试 | 2/2，通过：拒绝授权不消耗事件；授权后实际通知发出，重复 Worker 执行不刷新通知 postTime，事件已占用 |
| APK 构建 | debug、androidTest、release 均成功；release使用独立可升级签名，非debug密钥 |
| 静态检查 | lintDebug通过，0错误；17项提示为依赖有新版12项、KTX写法4项、旧Android备份配置提示1项。旧Android已设置allowBackup=false，Android12+另明确排除云备份及设备迁移 |
| 最终 release 模拟器检查 | 正式包安装成功；首装空态、快捷录入联合套餐、两项不同到期权益、一笔68 CNY付款、强制停止再打开持久化与账本均已检查；实际系统文件导出、读取预览、确认恢复回环通过 |

合计 **35 项自动测试通过**。设备：Android 15 / API35，ARM64，Pixel 6 AVD，Emulator 37.1.11，独立5554端口。SwiftShader Vulkan路径曾出现黑屏，改用宿主GPU并禁用Vulkan后实际画面正常；这不是Android真机验收。

修复并复测了：API26主题属性版本边界、FAB可访问名称、键盘遮住编辑保存按钮、残缺JSON被当默认空数据、失败远端副本不应挤掉成功备份。

## release 产物

- 文件：`RenewBoard-1.0.0-release.apk`，12,217,514 bytes。
- 包名 `cn.renewboard`，versionName `1.0.0` / versionCode `1`，minSdk26 / targetSdk35。
- SHA-256：`1ff7174bd297e27218d362dff8cfa1802044540398a18f728e78e6f8208b66db`。
- apksigner：Verifies，APK Signature Scheme v2，通过；RSA3072，单签名者。
- 签名证书 SHA-256：`9581b8f6993e5c88f3e5d49c837c1b28c7fa23caae6e2c9ca81e04660ec357aa`。
- 密钥及密码保存在仓库外受限本地目录，不进入源码、APK资源或发布附件。

本地原始验证输出在 `app/build/test-results/testDebugUnitTest/`、`app/build/reports/lint-results-debug.html` 和 `artifacts/`；这些包含本机运行细节，不公开上传。图中订阅均为模拟器演示数据，安装APK默认无数据。

## 已知验证边界

- 真实坚果云未配置，因此没有真实云端上传/下载回环；模拟WebDAV成功不等于真实坚果云兼容性已验收。
- 无连接真机；Android8–14各系统及OEM后台省电行为未逐一测试。minSdk26与API检查通过不替代这些设备测试。
- WorkManager系统延迟、长时间锁屏和弱网下的长期稳定性未作持续数日验证，不承诺准点通知。
- Kotlin/AGP组合超出Kotlin文档的完全支持矩阵上限；本项目实际编译与上述测试已通过，详见许可证说明。

## 公开发布范围

目标为 GitHub `RongGuang233/RenewBoard`，release tag `v1.0.0`。

公开：原创源码、Gradle wrapper与正式依赖清单、测试代码、构建脚本、README、MIT/第三方许可证、隐私及本验收说明、演示截图；release附件只含已核验APK和SHA256校验文件。

排除：签名私钥/密码、本机环境说明、local.properties、Gradle缓存、构建中间文件、测试运行日志、任何其他项目或用户业务数据。
