# 更新记录（Changelog）

> 分支：`Testing`　日期：2026-11-24

## 概览

本次更新以「**Android 自动化测试对齐 Python 仓库的 spec 驱动范式**」为主线，把原先散落在
`app/src/androidTest/` 里的型号耦合用例，重构为「通用用例 + 外部配置/规格」的架构，并补齐了
设备能力探测、Demo 侧自动化执行器与多设备接入能力。同时把 SDK AAR 升级到 1.4.6。

核心变化一句话：**换设备 = 改 `config.json` 的 `targetIdentity` + 换对应 `device_specs/<model>.json`，
不用重编 APK、不用改用例代码。**

---

## 1. SDK 升级

- `app/libs/sensorcapi-release.aar`：**1.3.8 → 1.4.6**
- 手工用例（Cerelax Pro 01~10 章）随 1.4.6 语义同步更新。

## 2. 测试架构重构（spec 驱动）

- 测试从 `app/src/androidTest/java/com/oymotion/sensorcapiktdemo/`（平铺、型号耦合）迁移到
  `Test Cases/Automation/`，目录结构与 Python 仓库 `Test Cases/Automation/` 对齐：

  | 位置 | 用途 |
  |---|---|
  | `Test Cases/Automation/config/config.json` | 目标设备 + 通用参数（单一事实来源） |
  | `Test Cases/Automation/device_specs/<model>.json` | 各型号能力规格（唯一型号差异来源） |
  | `Test Cases/Automation/core/` | 配置/spec/公共辅助（main 与 androidTest 共用） |
  | `Test Cases/Automation/Generalized/00~10` | 章节目录用例（与手工用例章节一一对应） |
  | `Test Cases/Automation/probes/` | 设备能力探测探针 |

- 新增 spec 驱动核心类：`AppConfig` / `TestConfig` / `DeviceSpec` / `SpecStore` /
  `CerelaxSpec` / `CerelaxTestSupport`。
- 配置/规格加载优先级：**设备端外部 JSON > APK assets > Kotlin 内置默认**，任一层失败只记日志并回退。

## 3. 设备能力探针

- 新增探针：`probes/ProbeDeviceTest.kt`（能力探测）、`FilterProbeTest.kt`（滤波读写诊断）、
  `Sm014DiagTest.kt`（诊断）。
- `app/build.gradle` 新增 `probeDevice` Gradle 任务，实现「探测 → adb pull 结果 →
  生成 `device_specs/<model>.json` → 幂等更新 `config.json` → push 回设备」的闭环：

  ```powershell
  .\gradlew.bat probeDevice -PprobeIdentity=9746
  ```

## 4. Demo 侧自动化

- 新增 `AndroidAutomationTests/`（`runner` / `context` / `report` / `types` / `cases/01~10`），
  作为 Demo App「自动化」Tab 的执行器。
- 新增 `app/src/main/.../AutomationScreen.kt`，提供 Demo 内运行时切换目标设备、跑用例、
  生成 JSON + TXT 双格式报告的能力。

## 5. 设备接入与手工用例

- 登记设备从 1 台扩展到 4 台，并补齐对应 `device_specs`：

  | 型号 | identity | MAC |
  |---|---|---|
  | Cerelax-Pro | 8517 | BC:93:2A:3F:85:17 |
  | Cerelax-Pro | 9746 | BC:93:2A:3F:97:46 |
  | gForceMax | A2A2 | BC:93:2A:3F:A2:A2 |
  | OB6000C | 6C6B | 78:1C:9D:E4:6C:6B |

- 新增手工用例目录：`Test Cases/Manual Cases/gForceMax/`、`Test Cases/Manual Cases/OB6000C/`。

## 6. 构建配置

- `app/build.gradle` 新增 sourceSets 划分（`main` 与 `androidTest` srcDirs 互不重叠）、
  `syncAutomationAssets`（把 config/spec JSON 打进 assets 兜底）、`probeDevice` 任务。

---

详细使用说明见 [Test Cases/Automation/README.md](Test%20Cases/Automation/README.md) 与
[Test Cases/Automation/USAGE.md](Test%20Cases/Automation/USAGE.md)。
