# gForceMax 腕带 SDK 测试用例（总览）

本目录针对 **gForceMax 腕带**，参照 Cerelax Pro 的测试结构，按模块拆分为多个用例文件。每个用例统一标注：测试目的、流程与逻辑、预期结果、有效性说明、是否可自动化、人工介入。用例 ID 沿用源测试计划，便于追溯。

## 设备信息

| 项 | 内容 |
|----|------|
| 设备型号 | gForceMax（EMG 腕带） |
| 广播名前缀 | `gForceMax`（形如 `gForceMax(A2A2)`） |
| 类型归属 | OYM 命名设备 / 传统 OYMotion 设备（非"新 EMG 设备"） |
| 核心能力 | NTF_EMG（8 通道肌电）、NTF_GEST（手势）、NTF_IMU（13 通道）、NTF_IMPEDANCE（8 通道电极接触检测） |
| 采样率 | EMG `500\|1000`（默认 `1000`）；IMU `50\|100\|200`（默认 `50`，以设备回读为准）；IMPEDANCE 固定约 1Hz |
| SDK 版本 | 1.4.6（Android AAR，`getVersion()` 实测） |

### 设备登记（`Test Cases/Automation/config/config.json`）

| 项 | 值 |
|----|-----|
| model | `gForceMax` |
| namePrefix | `gForceMax` |
| MAC | `BC:93:2A:3F:A2:A2` |
| identity | `A2A2` |
| targetIdentity 默认 | `8517`（Cerelax-Pro）；**测 gForceMax 时改为 `A2A2`** |

> 设备/身份匹配统一走 `TestConfig.matchesTarget()`（MAC 精确 > 广播名后四位 identity），不硬编码 MAC。
> 已知现象：部分固件把 `DeviceInfo.modelName` 上报为 `gForceUltra`，这是已知差异；**spec 仍按
> `Test Cases/Automation/device_specs/gForceMax.json` 加载**，不据此切换型号，也不判 FAIL。

## 已固化能力（单一事实来源：`Test Cases/Automation/device_specs/gForceMax.json`）

> 本表是 gForceMax 的"期望基线"，测试据此**硬断言**：spec 标 supported 的流收不到数据 / 通道数偏离即 FAIL；
> spec 标 unsupported 的流产出数据即 FAIL；能力不存在记 SKIP。
> 判定口径以 `getDeviceInfo()` 的运行时 `ChannelCount > 0` 为准，**不以** `getParam("NTF_XXX")=="ON"` 判定支持
> （其对不支持流也可能返回 `ON`）。

| 能力 | 判定字段（DeviceInfo） | spec 期望（channels） |
|------|------------------------|----------------------|
| 肌电 EMG | `emgChannelCount` | 8（核心） |
| 手势 GEST | `NTF_GEST` 运行时数据 | 1（固定约 15.625Hz，无采样率参数） |
| 聚合 IMU | `imuChannelCount` | 13（= acc3 + gyro3 + euler3 + quat4） |
| 加速度 ACC | `accChannelCount` | 3（仅存在于 NTF_IMU 聚合流中） |
| 陀螺仪 GYRO | `gyroChannelCount` | 3（仅存在于 NTF_IMU 聚合流中） |
| 欧拉角 Euler | `eulerChannelCount` | 3（仅存在于 NTF_IMU 聚合流中） |
| 四元数 Quat | `quatChannelCount` | 4（仅存在于 NTF_IMU 聚合流中） |
| 阻抗 IMPEDANCE | `impeChannelCount` | 8（固定约 1Hz，需与 NTF_EMG 同起） |
| NTF_GFORCE_ACC/GYRO/EULER/QUAT | — | 不支持独立流；`getParam` 返回 `Not supported` |
| 磁力计 / 磁角度 | `magChannelCount` / `angleChannelCount` 独立流 | 0（不支持，无磁力计） |
| 脑电 EEG | `eegChannelCount` | 0（不支持） |
| 心电 ECG | `ecgChannelCount` | 0（不支持） |
| 呼吸 BRTH | `brthChannelCount` | 0（不支持） |
| PPG / SpO2 | `ppgChannelCount` / `spo2ChannelCount` | 0（不支持） |

> 采样率：
>   - `EMG_SAMPLE_RATE`：`500|1000`，默认 `1000`，可设；
>   - `IMU_SAMPLE_RATE`：`50|100|200`，默认 `50`，可设（以设备实际回读为准）；
>   - `IMPEDANCE`：固定 `1`，`settable=false`，`coStream=NTF_EMG`（无独立采样率参数）。
>
> 滤波开关四项均支持：`FILTER_50HZ` / `FILTER_60HZ` / `FILTER_HPF` / `FILTER_LPF`。
>
> **GEST 与 EMG 高采样率的算力冲突**：`NTF_GEST` 与 `NTF_EMG` 均 supported、**不互斥**；但在 EMG
> `1000Hz` 下同时开 GEST 当前大概率出现问题（疑似芯片算力限制）。测 EMG 高采样率前先
> `setParam("NTF_GEST","OFF")`。这是当前固件的已知现象，不是永久硬约束，写用例时不得固化为永久断言。

## 环境与前置条件

- SDK 为 Android AAR `app/libs/sensorcapi-release.aar`，`getVersion()` 实测 1.4.6；构建/真机测试见仓库根 `AGENTS.md` §五。
- 初始化：`SensorController.getInstance()` → `controller.registerBleBridge(context)`，使用 **Android 系统原生 BLE**（无 dongle、无后端切换）。
- 权限：`BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`（Android 12+），运行时授予；宿主蓝牙开启。
- 设备 MAC / identity 写入 `Test Cases/Automation/config/config.json`；测 gForceMax 时把 `targetIdentity` 设为 `A2A2`。
- 设备上电、在扫描范围内、广播名以 `gForceMax` 开头。
- 每个测试结束调用静态方法 `SensorController.terminate()`；异常路径也需调用。
- 连接/起流/停流等操作之间留 ≥100ms 间隔。
- 测试入口：`.\gradlew.bat :app:compileDebugAndroidTestKotlin`（只编译）、`.\gradlew.bat :app:connectedDebugAndroidTest`（真机执行）。

## 文件索引

| 文件 | 内容 |
|------|------|
| [01_控制器与扫描.md](./01_控制器与扫描.md) | `CTRL-*`：扫描、requireSensor/getSensor、版本、terminate |
| [02_连接与状态机.md](./02_连接与状态机.md) | `DEV-SM-*`：连接、断连、重连、状态迁移 |
| [03_数据流.md](./03_数据流.md) | `DATA-*`：起流、样本访问、EMG/GEST/IMU/Impedance |
| [04_参数.md](./04_参数.md) | `PARAM-*`：setParam/getParam、EMG/IMU 采样率、滤波 |
| [05_多设备同步.md](./05_多设备同步.md) | `MULTI-*`：同步起流/停流（需 ≥2 台） |
| [06_Bin录制回放解析.md](./06_Bin录制回放解析.md) | `BIN-*`：录制/回放/CSV |
| [07_电量日志调试.md](./07_电量日志调试.md) | `BATT-*` / `LOG-*` |
| [08_人工硬件用例.md](./08_人工硬件用例.md) | `HW-MAN-*`：人工/仪器用例 |
| [09_异步接口.md](./09_异步接口.md) | `ASYNC-*`：suspend 与 CompletableFuture 变体 |
| [10_底层接口与边界补充.md](./10_底层接口与边界补充.md) | `MISC-*`：DeviceInfo/INVALID/不适用 Android 接口 |

用例总数 **122** 条：01=16、02=17、03=17、04=12、05=9、06=15、07=9、08=5、09=12、10=10。

## 执行方式汇总

| 模块 | auto | semi-auto | manual |
|------|:----:|:---------:|:------:|
| 控制器与扫描 | 多数 | 蓝牙开关 | 后端/dongle/扫描状态回调（不适用） |
| 连接与状态机 | 多数 | 断连/断电恢复、自动重连 | 重连状态变化回调（不适用） |
| 数据流 | 多数 | 长稳值守 | 手势/运动激发（可选） |
| 参数 | 多数 | — | NEUCIR（不适用） |
| 多设备同步 | 多数 | — | 双后端对照（不适用） |
| Bin | 多数 | 磁盘不足环境构造 | — |
| 电量/日志 | 多数 | 电量变化观察 | — |
| 异步接口 | 多数 | — | — |
| 底层接口与边界 | 多数 | INVALID 触发 | 已移除接口（不适用） |
| 人工硬件 | — | 蓝牙开关/断电恢复 | 距离/遮挡等硬件场景 |

## 判定标准

- P0 用例 100% 通过；P1 ≥95%；P2 ≥90%。
- 失败保留 SDK 日志与 `.bin` 用于复现。
- 缺设备（如多设备同步需 ≥2 台）时整体"跳过"，不判失败。
- 所有能力字段（`xxxChannelCount`/`xxxSampleRate`）以 `getDeviceInfo()` 运行时结果为准，不硬编码假设。
- **spec 偏离判定**：spec 标 supported 的流无数据或通道数不符 → FAIL；标 unsupported 的流产出数据或通道数 `>0` → FAIL；
  设备确实不具备该能力 → SKIP，需在报告中记录"不支持，跳过"。
- 已确认的 SDK 缺陷（如 BIN-FUNC-009 的 7101）单独挂问题号，不计入用例脚本缺陷。

## 用例优先级与标签

每个用例均有 `优先级` 字段（P0/P1/P2），与测试计划一致：

| 优先级 | 含义 | 通过率 |
|--------|------|--------|
| P0 | 核心功能 / 状态机主路径 / 安全 | 100% |
| P1 | 一般功能 / 边界 / 异常恢复 / 性能 | ≥95% |
| P2 | 低优先 / 观测类 | ≥90% |

用例标签（§八 覆盖率追踪）由现有字段派生，避免重复维护：

| 标签 | 派生规则 |
|------|----------|
| `@smoke` | 优先级 = P0 |
| `@regression` | 全部用例 |
| `@connection` | CTRL-* / DEV-SM-* / HW-MAN-001/003/006 |
| `@data-parse` | DATA-* / BIN-* / MISC-006 |
| `@param` | PARAM-* |
| `@state` | DEV-SM-* |
| `@semi-auto` / `@manual` | 对应 `可自动化` 字段 |
| `@not-applicable-android` | 标题/有效性说明标注"Android 不适用"的用例 |

## 接口 → 用例映射（覆盖率追踪）

每个接口至少 1 个正向 + 1 个异常/边界用例（§八.2）。主要接口映射如下：

| 类 | 接口 | 正向用例 | 异常/边界用例 |
|----|------|----------|---------------|
| SensorController | `isEnable()` | CTRL-FUNC-002 | CTRL-FUNC-001 |
| SensorController | `scan(timeoutMs)` | CTRL-FUNC-003/004 | CTRL-BND-001、CTRL-ROB-001 |
| SensorController | `setOnScanResultListener` | CTRL-FUNC-005 | CTRL-FUNC-006 |
| SensorController | `requireSensor/getSensor` | CTRL-FUNC-007/008 | CTRL-FUNC-007/008（非法 MAC） |
| SensorController | `getConnectedSensors` | CTRL-FUNC-009 | DEV-SM-004 |
| SensorController | `getVersion` | CTRL-FUNC-012 | — |
| SensorController | `terminate()`（静态） | CTRL-FUNC-013 | CTRL-FUNC-013（重复调用） |
| SensorController | `multiStartDataNotification/multiStopDataNotification` | MULTI-FUNC-001/002 | MULTI-FUNC-003/005 |
| SensorController | `replayBinFile/pause/resume/stop` | BIN-FUNC-004/005/006 | BIN-FUNC-007、BIN-ROB-003 |
| SensorController | `multiReplayBinFile` | BIN-FUNC-010 | BIN-BND-002、BIN-ROB-002 |
| SensorController | `parseBinToCsv/getBinFileInfo` | BIN-FUNC-002/008 | BIN-FUNC-003、BIN-BND-002 |
| SensorController | `setParam("LOG_PATH"/"DEBUG_ENABLED")`、`log` | LOG-FUNC-001/002/004/005 | LOG-FUNC-002 |
| SensorProfile | `connect/disconnect` | DEV-SM-001/004 | DEV-SM-002/015 |
| SensorProfile | `connectAsync/disconnectAsync` | ASYNC-FUNC-002/003 | ASYNC-FUNC-002（无效 MAC）、MISC-FUNC-010 |
| SensorProfile | `init` | DATA-FUNC-001 | DATA-FUNC-002、DATA-BND-001/002 |
| SensorProfile | `start/stopDataNotification` | DATA-FUNC-003/004 | DATA-FUNC-002 |
| SensorProfile | `setParam/getParam` | PARAM-FUNC-001/002/004/011 | PARAM-FUNC-005/006/008、PARAM-BND-001 |
| SensorProfile | `getBatteryLevel/setOnPowerListener` | BATT-FUNC-001/002/004 | BATT-FUNC-001（-1 分支） |
| SensorProfile | `setOnStateChangeListener/setOnErrorListener` | DEV-SM-001/004 | DEV-SM-002/012 |
| SensorProfile | `setAutoReconnect/isAutoReconnecting` | DEV-SM-008/016 | DEV-SM-009 |
| SensorProfile | `setOnAutoReconnectListener` | DEV-SM-010 | DEV-SM-010（接管路径） |
| SensorProfile | `setOnDeviceInfoUpdateListener` | DEV-SM-014 | DEV-SM-014（不触发即 SKIP 断言） |
| SensorData | `getDataType/getSampleRate/getChannelCount` 等 | DATA-FUNC-006 | DATA-FUNC-007、DATA-PERF-001 |
| SensorData | `clone` | DATA-FUNC-012 | — |
| SensorData | `startSampleIndex` | MISC-FUNC-006 | DATA-FUNC-007（单调性） |
| DeviceInfo | public 字段 `*ChannelCount/*SampleRate` | MISC-FUNC-008 | MISC-FUNC-008（spec 偏离） |

### Android 不适用的源用例（保留 ID）

| 用例 ID | 原能力 | Android 侧处理 |
|---------|--------|----------------|
| CTRL-FUNC-010 | `getBLEBackendName` 后端识别 | 无后端切换，`@Ignore` |
| CTRL-FUNC-011 | `checkSetupDongle` 引导 | 无 dongle，`@Ignore` |
| CTRL-FUNC-014 | `onScanStateChange` 回调 | 无该回调，`@Ignore` |
| DEV-SM-017 | `onAutoReconnectingStateChange` 回调 | 无该回调，改用 `isAutoReconnecting()`（DEV-SM-016） |
| MULTI-FUNC-008 | bumble/bleak 双后端对齐 | 无后端切换，`@Ignore` |
| HW-MAN-002 | dongle 驱动绑定 | 无 dongle，`@Ignore` |
| HW-MAN-007 | dongle 热插拔 | 无 dongle，等价场景见 HW-MAN-006 |
| MISC-FUNC-001 | `hasDeviceFoundCallback` 查询 | 无该查询，`@Ignore` |
| MISC-FUNC-002~005 | `Sample.reset` / `SensorData` 序列化与复用 | 接口已移除，`@Ignore` |
| MISC-FUNC-009 | `submit` 后台执行器 | 无 `submit`，等价能力见 ASYNC-FUNC-002/010 |

> 未映射到用例的接口显式标"未覆盖"，待 README 定案后补齐。

## 超时 / 轮询 / 重试约定（§四）

自动化脚本落地时遵循，手工用例的"等待"类步骤也按此判定：

- **轮询等待**代替固定 sleep：间隔默认 0.2s；扫描发现上限 `scanTimeoutMs=5000`、建立连接上限 `readyTimeoutMs=15000`、参数下发回包上限 5s、首包数据上限 `dataTimeoutMs=12000`；超时判 FAIL 并记录"已等待 Xs 未收到回调"。
- **重试**：仅环境性偶发（单次 BLE 掉线）允许重试，上限 2 次并记录；扫描匹配重试间隔 `scanRetryIntervalMs=10000`；断言类失败不重试；重试后仍失败标 flaky 单独排查。
- **JUnit 超时保护**：所有可能阻塞的用例加 `@Test(timeout=...)`（D 章边界/回放建议 300000ms），避免 instrumentation 永久挂起。
- **硬失败 vs 环境异常分流**：断言不通过记 FAIL；设备未响应/链路断开先复测，仍异常判环境故障退出，不误记 SDK 缺陷。
- **semi-auto 人工窗口**：Android 无 stdin，人工操作用固定等待窗口 `manualPauseSeconds=30`，测试人员在窗口内完成开关蓝牙/断电等动作。

## 环境与依赖固化（版本矩阵，§六）

报告与结论须显式记录，脱离环境谈结论无效：

| 项 | 必填内容 |
|----|----------|
| SDK 版本 | `getVersion()` 实测值（当前 1.4.6） |
| Android / OS | 版本号 |
| 宿主 BLE 适配器 | 芯片型号 + 系统 BLE 栈版本（不同厂商行为有差异，跨适配器结果不可混比） |
| 设备 | 型号、固件版本、电量水平 |
| 配对状态 | 是否已 bond |
| 测试配置 | `targetIdentity`、`packageSampleCount`、超时/容差取值 |

## 参数测试方法论（§二）

数值型参数统一用 6 点边界法覆盖等价类与边界：合法值域内取代表值（等价类）+ 边界及边界 ±1；非法/异常值另测越界、空值（Android 为空串，无 `None`）、错误类型，验证被正确拒绝而非静默接受。已落地的边界用例见 `CTRL-BND-001`、`DATA-BND-001/002`、`PARAM-BND-001`。

## 缺陷归因与上报（§十一）

- 报 bug 前自检：蓝牙开关、运行时权限、宿主 BLE 适配器、设备电量、bond 状态、SDK/Android 版本、`config.json` 的 identity/MAC。
- 设备型号/能力优先排查：先查 `gForceMax.json` 与 `getDeviceInfo()` 实际值，再判断是否设备能力问题，避免把"设备不支持"误报"SDK 缺陷"。
- 注意已知现象：`modelName` 可能上报 `gForceUltra`；GEST 与 EMG 1000Hz 存在算力冲突；BIN-FUNC-009 受问题 7101 影响。
- 上报至少含：用例名、环境矩阵、断言输出、完整 logcat（`adb logcat -s System.out` 与 SDK 日志目录）、最小复现步骤。
