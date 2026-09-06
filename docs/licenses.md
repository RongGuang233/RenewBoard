# 许可证与依赖说明

核对日期：2026-09-06。原创源码和文档采用 [MIT](../LICENSE)，署名为 RenewBoard contributors。第三方代码继续适用各自许可，不能因为项目使用 MIT 就重新许可第三方组件。

## 正式依赖与用途

来源为 `build.gradle.kts`、`app/build.gradle.kts` 和 `gradle/wrapper/gradle-wrapper.properties`。已核对主任务于 2026-09-06 成功生成的 `:app:dependencies --configuration releaseRuntimeClasspath` 输出（当次本地证据：`/private/tmp/renewboard-setup/dependencies.log`，结尾为 `BUILD SUCCESSFUL`）。以下运行依赖版本采用该图实际选中结果；构建及测试依赖来自正式声明。POM 的 dependencyManagement 项目只是约束，不能当作运行依赖。解析成功不等于 APK 构建或设备测试成功，本表也不宣称逐文件验证了最终 APK 内容。

| 组件 | 声明版本 / 约束 | 归属 | 许可证 |
| --- | --- | --- | --- |
| Kotlin Android、Compose compiler、serialization 插件 | 2.1.20 | 构建 | Apache-2.0 |
| Kotlin stdlib | stdlib / common 2.1.20；jdk7 / jdk8 桥接包 1.9.10 | 运行 | Apache-2.0 |
| Android Gradle Plugin | 8.9.2 | 构建 | Apache-2.0（Android SDK 另受其下载条款约束） |
| KSP | 2.1.20-1.0.32 | 构建 | Apache-2.0 |
| Gradle wrapper / distribution | 8.11.1 | 构建 | Apache-2.0；发行包所含其他组件见上游 LICENSE |
| Compose BOM | 2025.04.01 | 依赖约束，不是运行库 | Apache-2.0 |
| Compose UI / runtime / foundation 等 | BOM：UI 1.8.0 | 运行，传递依赖 | Apache-2.0 |
| Material 3 | BOM：1.3.2 | 运行 | Apache-2.0 |
| Material icons extended | BOM：1.7.8 | 运行 / 图标资源 | Apache-2.0 |
| Activity Compose | 1.10.1 | 运行 | Apache-2.0 |
| Lifecycle runtime Compose | 2.9.0 | 运行 | Apache-2.0 |
| Room runtime / ktx | 2.7.1 | 运行 | Apache-2.0 |
| Room compiler | 2.7.1 | KSP 构建处理器 | Apache-2.0 |
| WorkManager runtime ktx | 2.10.1 | 运行 | Apache-2.0 |
| kotlinx.serialization JSON / core | 1.8.1 | 运行 | Apache-2.0 |
| OkHttp | 4.12.0 | 运行 | Apache-2.0 |
| Okio / okio-jvm | 实际选中 3.6.0 | 运行，传递依赖 | Apache-2.0 |
| kotlinx.coroutines core / android | 实际选中 core / core-jvm / android 1.7.3 | 运行，传递依赖 | Apache-2.0 |
| AndroidX core、annotation、collection、SQLite、startup、savedstate、concurrent、tracing、Lifecycle 等 | 实际主要版本见下段 | 运行，传递依赖 | Apache-2.0 |
| Guava ListenableFuture | 实际选中 1.0 | 运行，传递依赖 | Apache-2.0 |
| JetBrains annotations | 实际选中 23.0.0 | 编译注解 / 传递依赖 | Apache-2.0 |
| JSpecify | 实际选中 1.0.0 | 运行 classpath 中的注解，传递依赖 | Apache-2.0 |
| JUnit | 4.13.2 | JVM 测试 | EPL-1.0 |
| MockWebServer | 4.12.0 | JVM 测试 | Apache-2.0 |
| Hamcrest | JUnit 传递请求 1.3 | 测试 | BSD-3-Clause |
| AndroidX test ext JUnit / runner / rules | 1.2.1 / 1.6.2 / 1.6.1 | 仪器测试 | Apache-2.0 |
| Compose ui-test-junit4 / ui-test-manifest | BOM 约束 | 仪器测试 / debug | Apache-2.0 |
| AndroidX Work testing | 2.10.1 | androidTestImplementation，仅仪器测试，不进入 release | Apache-2.0 |

实际图中的主要 AndroidX 传递版本：core / core-ktx 1.13.1、core-viewtree 1.0.0、annotation / annotation-jvm 1.9.1、annotation-experimental 1.4.1、arch.core 2.2.0、collection 1.5.0、concurrent-futures 1.1.0、savedstate 1.3.0、SQLite 2.5.0、startup 1.1.1、tracing 1.2.0、profileinstaller 1.4.0、emoji2 1.4.0、graphics-path 1.0.1、autofill / poolingcontainer / interpolator 1.0.0、versionedparcelable 1.1.1；生命周期组件统一选中 2.9.0。`atomicfu` 虽出现在通用 Room POM 中，但不在本次 Android releaseRuntimeClasspath 图中，故不列为实际 release 依赖。

测试组件的许可证文本一并保留，方便源码和测试产物分发；这不意味着测试库进入 release APK。Guava 文本用于 ListenableFuture 归属说明，不表示应用引入整个 Guava。

## 官方核对依据与兼容性

- [AGP 8.9 官方兼容表](https://developer.android.com/build/releases/agp-8-9-0-release-notes)：最低 Gradle 8.11.1、JDK 17，支持 API 35；与仓库声明一致。
- [Kotlin 插件兼容表](https://kotlinlang.org/docs/gradle-configure-project.html)：KGP 2.1.20 的完全支持范围为 Gradle 7.6.3–8.12.1、AGP 7.3.1–8.7.2。项目 AGP 8.9.2 超出该完全支持上限，不能声称整套组合得到官方完全支持；需要实际编译与测试验证。
- [Compose BOM 映射](https://developer.android.com/develop/ui/compose/bom/bom-mapping)及 [BOM POM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2025.04.01/compose-bom-2025.04.01.pom)：UI 1.8.0、Material 3 1.3.2、icons extended 1.7.8。BOM 不管理 Kotlin compiler，仓库使用同版本 Kotlin Compose 插件。
- [Room 发布记录](https://developer.android.com/jetpack/androidx/releases/room#2.7.1)、[WorkManager 发布记录](https://developer.android.com/jetpack/androidx/releases/work#2.10.1)、[KSP 版本发布](https://github.com/google/ksp/releases/tag/2.1.20-1.0.32)：所选版本有正式上游发布记录。Room 2.7 系列要求 Kotlin 2.0 及以上；项目声明为 2.1.20。
- [serialization 1.8.1 发布记录](https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.8.1)及 [JSON JVM POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.8.1/kotlinx-serialization-json-jvm-1.8.1.pom)：其 Kotlin stdlib 请求为 2.1.20。
- [OkHttp 4.12.0 POM](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.pom)确认 Apache-2.0、Okio 3.6.0 和 Kotlin stdlib 依赖；[版本源码说明](https://raw.githubusercontent.com/square/okhttp/parent-4.12.0/README.md)要求 Android API 21+、Java 8+，仓库 minSdk 26、JVM target 17 满足此最低要求。
- [Room POM](https://dl.google.com/dl/android/maven2/androidx/room/room-runtime/2.7.1/room-runtime-2.7.1.pom)与 [Work POM](https://dl.google.com/dl/android/maven2/androidx/work/work-runtime/2.10.1/work-runtime-2.10.1.pom)用于识别上述主要传递依赖。POM 请求版本不等于 Gradle 最终选中版本。

维护判断：核对了官方发布历史及所用版本源码，选用的是已发布版本，没有引入不明来源的依赖副本。AndroidX、Kotlin 和 Gradle 官方文档已有后续版本；这份记录不把历史版本称为“最新”，也不承诺旧版本仍有补丁支持。许可证核对不替代构建验收、设备测试或依赖更新评估。本许可证整理工作未运行构建或更改版本；实际依赖解析证据来自主任务。

## 随包文本及来源

`app/src/main/assets/licenses/` 保存可离线阅读的原始许可证；`INDEX.txt` 给出组件与文件映射，`NOTICE.txt` 保存项目归属声明，`RenewBoard-MIT.txt` 为原创代码授权。各 Apache 文本保留取得的上游文本，不能只用网页链接替代随包许可证。

| 本地文件 | 官方下载来源 |
| --- | --- |
| Apache-2.0.txt | https://www.apache.org/licenses/LICENSE-2.0.txt |
| AndroidX-LICENSE.txt | https://raw.githubusercontent.com/androidx/androidx/androidx-main/LICENSE.txt |
| Kotlin-LICENSE.txt | https://raw.githubusercontent.com/JetBrains/kotlin/v2.1.20/license/LICENSE.txt |
| Kotlin-NOTICE.txt | https://raw.githubusercontent.com/JetBrains/kotlin/v2.1.20/license/NOTICE.txt |
| Serialization-LICENSE.txt | https://raw.githubusercontent.com/Kotlin/kotlinx.serialization/v1.8.1/LICENSE.txt |
| Coroutines-LICENSE.txt | https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/1.9.0/LICENSE.txt |
| OkHttp-LICENSE.txt | https://raw.githubusercontent.com/square/okhttp/parent-4.12.0/LICENSE.txt |
| Okio-LICENSE.txt | https://raw.githubusercontent.com/square/okio/parent-3.6.0/LICENSE.txt |
| Guava-LICENSE.txt | https://raw.githubusercontent.com/google/guava/v33.3.1/LICENSE |
| JetBrains-Annotations-LICENSE.txt | https://raw.githubusercontent.com/JetBrains/java-annotations/26.0.2/LICENSE.txt |
| JSpecify-LICENSE.txt | https://raw.githubusercontent.com/jspecify/jspecify/v1.0.0/LICENSE |
| KSP-LICENSE.txt | https://raw.githubusercontent.com/google/ksp/2.1.20-1.0.32/LICENSE |
| Gradle-LICENSE.txt | https://raw.githubusercontent.com/gradle/gradle/v8.11.1/LICENSE |
| JUnit-LICENSE.txt | https://raw.githubusercontent.com/junit-team/junit4/r4.13.2/LICENSE-junit.txt |
| Hamcrest-LICENSE.txt | https://raw.githubusercontent.com/hamcrest/JavaHamcrest/hamcrest-java-1.3/LICENSE.txt |

AndroidX 使用官方主线的项目通用文本，同时逐项 POM 验证许可证名称；coroutines、Guava、annotations 文本来源的 tag 仅标识取得许可文本的位置，不能当作本项目实际依赖解析版本。Gradle LICENSE 包含发行包的附加许可证，不应删去后半部分。Kotlin NOTICE 保留原始编译器分发归属，但不表示整个编译器进入 APK。

## 功能参考的边界

只参考了[有数鸟开发者于 2020-04-10 的公开介绍](https://meta.appinn.net/t/topic/15300)中的订阅记录、到期提醒和支出统计理念。这是历史文字资料，不是当前产品实测，也不证明现在仍提供相同功能。本项目未复制其商标、图标、截图或专有代码；RenewBoard 与其没有关联或背书关系。
