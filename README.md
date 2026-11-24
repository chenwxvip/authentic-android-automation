# Authentic · 游戏自动化学习项目（Kotlin / Android）

> 一个基于 **Android 无障碍服务（AccessibilityService）+ MediaProjection 截屏 + 模板匹配** 的自动化测试 Demo，
> 用于学习和研究 Android 自动化技术（手势注入、无障碍节点查找、图像模板匹配、前台保活、多状态机调度）。

---

## 🤖 关于本项目的创作方式（AI 辅助生成声明）

> **本项目由作者提出需求与验收标准，绝大部分代码由 AI 助手（Tencent Hunyuan Hy / WorkBuddy）辅助编写与迭代调试完成。**
>
> - 作者负责：提出功能需求、在真机上验证效果、反馈问题、最终审核与决策；AI 负责：代码实现、重构、问题排查与脚本/文档撰写。
> - 以开源形式公开，是为了技术学习与交流；代码由 AI 生成并不意味着可免于本页的免责声明与使用风险。
> - 若你在使用或二次开发中发现问题，欢迎提 Issue / PR 共同改进。
>
> 关于 AI 生成内容的合规说明：本项目遵守平台关于 AI 辅助创作标注的要求，主动披露创作方式；不含任何由 AI 生成的虚假身份、伪造凭证或侵权内容。

---

## ⚠️ 免责声明（务必先读）

> ## 🚨 DISCLAIMER 🚨
>
> **本项目仅供个人学习、技术交流与自动化原理研究使用，严禁用于任何商业用途、盈利行为或对外提供服务。**
>
> - 本项目是通用自动化框架的技术演示，不针对任何特定游戏或平台提供"代练""挂机""刷资源"等商业能力。
> - 使用自动化工具可能违反目标应用/游戏的用户协议与服务条款，**由此导致的账号封禁、账号限制、数据丢失、财产损失等一切后果，由使用者本人自行全部承担**，与作者无关。
> - 请勿使用真实账号、生产环境账号或任何有价值账号进行测试；请自行评估并遵守所在地法律法规及目标平台规则。
> - 作者不对本项目作任何明示或暗示的担保，因使用、修改、分发本项目造成的任何直接或间接损失，作者概不负责。
> - 若你不接受以上条款，请勿下载、编译或使用本项目。一旦使用，即视为你已充分理解并同意本免责声明。

---

## 一、项目简介

`Authentic` 是一个 Kotlin 编写的 Android 无障碍自动化框架 Demo，展示如何组合以下能力完成一套"自动巡检—识别状态—执行动作"的闭环：

| 能力模块 | 实现 | 说明 |
|---|---|---|
| 手势注入 | `GestureDispatcher` | 基于无障碍 `dispatchGesture` 的物理坐标点击/滑动 |
| 节点查找 | `NodeFinder` | 通过无障碍节点树做文本/描述匹配与点击 |
| 屏幕捕获 | `CaptureService` + `MediaProjection` | 前台截屏服务，持续向帧总线供帧 |
| 模板匹配 | `ImageMatcher` / `TemplateStore` | 轻量 RGB 均值 + 逐点采样比对，无第三方 OCR |
| 数字识别 | `CountdownReader` | 对 `assets/templates/digits/` 的 0~9 数字模板逐位比对，读取倒计时 |
| 状态机 | `AccountPollMachine` / `Step1VerifyMachine` / `NavScheduler` | 登录轮询、场景导航、收菜/种菜、定时巡检 |
| 持久化 | `AccountRepository` / `StationBoard` | SharedPreferences 存账号，外部文件存状态看板 |
| 保活 | `KeepAliveService` | 前台服务 + 唤醒锁维持长时间运行 |

**说明**：源码中的具体坐标（`Config.kt`）来源于特定分辨率（基准 2400×1080 横屏 / 1220×2712 竖屏）设备的调试记录，**仅作为技术示例**。不同设备、不同分辨率、不同版本界面都需要重新校准，项目不含也不提供任何"开箱即用"的自动化效果保证。

## 二、环境依赖

| 依赖 | 版本 / 要求 |
|---|---|
| JDK | 17（Kotlin JVM Target 17） |
| Android SDK | compileSdk 34 / targetSdk 34 / minSdk 24 |
| Gradle | 8.7（仓库内置 `gradle/wrapper`，首次构建自动下载） |
| Android Gradle Plugin | 随 Gradle Wrapper 及 `build.gradle.kts` 配置 |
| Kotlin | `org.jetbrains.kotlin.android` 插件 |
| 依赖库 | androidx.core:core-ktx 1.13.1、kotlinx-coroutines-android 1.8.1 |
| 真机 | Android 7.0+ 设备，需开启无障碍服务与屏幕捕获授权 |

> 仓库已配置国内镜像源（阿里云 Maven、腾讯云 Gradle 镜像）便于构建。若镜像不可用，可自行在 `settings.gradle.kts` 与 `gradle-wrapper.properties` 中改回官方源。

## 三、安装步骤

### 1. 配置本地 SDK

在项目根目录新建 `local.properties`（**此文件已被 .gitignore 忽略，不会上传**），写入你的 Android SDK 路径：

```properties
# Windows 示例（注意反斜杠转义）
sdk.dir=D\:\\Apps\\Android\\Sdk
# macOS / Linux 示例
# sdk.dir=/Users/you/Library/Android/sdk
```

### 2. 编译 Debug 包

```bash
# 项目根目录执行（仓库已内置 Gradle Wrapper，无需预先安装 Gradle）
./gradlew assembleDebug      # macOS / Linux
gradlew.bat assembleDebug    # Windows
```

首次执行 Wrapper 会自动下载 Gradle 8.7；产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

也可用 Android Studio 打开项目根目录，直接 Run / Build APK。

### 3. 安装到真机

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 四、使用说明

1. **首次启动**：打开 App，按界面引导开启无障碍服务（`AuthenticAccessibilityService`）与屏幕捕获（MediaProjection）授权。这两项是自动化运行的前提。
2. **录入账号**：在主界面输入框填写账号备注，选择渠道（QQ / 微信），点击添加。账号仅以备注形式保存在本地 SharedPreferences，不含任何密码或凭证。
3. **校准坐标**：不同设备分辨率不同。所有可校准坐标集中在 `Config.kt`，运行时的日志面板会打印每次点击的 `基准(x,y) -> 真机(x,y)`，据此修改坐标即可，无需改动状态机逻辑。
4. **运行流水线**：界面提供"开始/关闭定时"开关。开启后倒数 5 秒，随后按账号列表依次巡检、识别状态并执行自动化闭环；无需手动进入目标游戏。
5. **状态看板**：`StationBoard` 会把识别到的状态写入外部文件目录，界面按账号分行展示实时倒计时。
6. **日志排查**：运行日志写入 App 外部文件目录的 `authentic_log.txt`，同时界面有实时日志面板，便于调试。

> 模板图位于 `app/src/main/assets/templates/`，其中的账号相关文字已做脱敏打码；如需在自己的环境中使用，请按实际界面重新截取并替换对应模板。

## 五、项目结构

```
.
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/templates/        # 场景/按钮/数字 模板图（已脱敏）
│       ├── java/com/ayu/authentic/
│       │   ├── MainActivity.kt       # 控制台界面
│       │   ├── config/Config.kt      # 所有可校准坐标集中处
│       │   ├── core/                 # 状态机、调度器、账号/看板存取
│       │   ├── gesture/              # 无障碍手势与节点
│       │   ├── service/              # 无障碍服务、截屏服务、前台保活
│       │   ├── util/                 # 日志
│       │   └── vision/               # 帧总线、模板匹配、数字识别
│       └── res/
├── gradle/wrapper/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── .gitignore
└── README.md
```

## 六、许可

本项目仅用于学习交流，未声明开源许可证即表示**保留所有权利**。请勿用于商业用途，详见上方免责声明。
