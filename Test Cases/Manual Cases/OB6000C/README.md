# OB6000C 脑电传感器 SDK 测试用例（总览）

本目录针对 **OB6000C 脑电传感器**，按模块拆分为多个用例文件。每个用例统一标注：标题（含用例 ID）、优先级、测试目的、流程与逻辑、预期结果、有效性说明、是否可自动化、人工介入、测试结果。用例 ID 沿用测试计划，便于追溯。

## 设备信息

| 项 | 内容 |
|----|------|
| 设备型号 | OB6000C（32 通道脑电传感器） |
| 广播名 | `OB6000C(<四位>)`（如 `OB6000C(6C6B)`） |
| 设备 MAC | `78:1C:9D:E4:6C:6B` |
| identity（广播名后四位） | `6C6B` |
| 类型归属 | OB 命名设备 / EEG 设备 |
| 核心能力 | NTF_EEG（32 通道脑电）+ NTF_IMPEDANCE（32 通道电极接触检测） |
| SDK 版本 | 1.4.6（Android AAR，`getVersion()` 实测） |
| AAR 路径 | `app/libs/sensorcapi-release.aar` |

> **已知现象**：设备固件上报的 `DeviceInfo.modelName` 为 `OB6000`（不带 C 后缀），而广播名与登记型号为 `OB6000C`。
> 这不影响能力判定：spec 仍按 `Test Cases/Automation/device_specs/OB6000C.json` 加载，设备匹配以 `identity=6C6B` / MAC 精确匹配为准，**不以 `modelName` 字符串判定型号**。

## 设备登记与目标切换

设备登记在 `Test Cases/Automation/config/config.json` 的 `devices` 数组：

```json
{"model": "OB6000C", "namePrefix": "OB6000C", "mac": "78:1C:9D:E4:6C:6B", "identity": "6C6B"}
```

- `targetIdentity` 默认值仍为 `8517`（Cerelax-Pro）；**测 OB6000C 时把 `targetIdentity` 改为 `6C6B`**。
- 匹配规则统一走 `TestConfig.matchesTarget()`：MAC 精确匹配优先，其次广播名后四位等于 identity；不硬编码 MAC 到用例。
- 修改外部 JSON（设备端 `/sdcard/Android/data/com.oymotion.sensorcapiktdemo/files/automation/`）即可切换目标设备，无需重编 APK。

## 已固化能力（单一事实来源：`Test Cases/Automation/device_specs/OB6000C.json`）

> 本表是 OB6000C 的「期望基线」，测试据此**硬断言**：spec 标 `supported=true` 的流未产出或通道数偏离即 FAIL；spec 标 `supported=false` 的流产出数据即 FAIL；设备明确不支持的能力记 SKIP。
> 判定口径以 `getDeviceInfo()` 的 `*ChannelCount > 0` 为准（`DeviceInfo` 为 public 字段，非 getter）；**不以** `getParam("NTF_XXX") == "ON"` 判定支持（其对不支持流也可能返回 `ON`）。

| 能力 | 判定字段（DeviceInfo public 字段） | spec 期望（channels） |
|------|-----------------------------------|----------------------|
| 脑电 EEG | `eegChannelCount` | 32（核心） |
| 阻抗 IMPEDANCE | `impeChannelCount` | 32（固定约 1Hz，需与 NTF_EEG 同起） |
| 聚合 IMU | `imuChannelCount` | 13（= acc3 + gyro3 + euler3 + quat4） |
| 加速度 ACC | `accChannelCount` | 3 |
| 陀螺仪 GYRO | `gyroChannelCount` | 3 |
| 欧拉角 Euler | `eulerChannelCount` | 3 |
| 四元数 Quat | `quatChannelCount` | 4 |
| 磁力计 MAG | `magChannelCount` | 0（不支持） |
| 角度 ANGLE | `angleChannelCount` | 0（不支持） |
| 肌电 EMG | `emgChannelCount` | 0（不支持） |
| 手势 GEST | `gestChannelCount` | 0（不支持） |
| 心电 ECG | `ecgChannelCount` | 0（不支持） |
| 呼吸 BRTH | `brthChannelCount` | 0（不支持） |
| PPG | `ppgChannelCount` | 0（不支持） |
| SpO2 | `spo2ChannelCount` | 0（不支持） |

### 流开关（spec.streams）

| 流键 | supported | channels | 说明 |
|------|:---------:|:--------:|------|
| `NTF_EEG` | ✅ | 32 | 核心脑电流 |
| `NTF_IMPEDANCE` | ✅ | 32 | 电极接触检测，需与 `NTF_EEG` 同起 |
| `NTF_IMU` | ✅ | 13 | 聚合流，含 acc/gyro/euler/quat |
| `NTF_GFORCE_ACC` | ✅ | 3 | 可独立开关 |
| `NTF_GFORCE_GYRO` | ✅ | 3 | 可独立开关 |
| `NTF_GFORCE_EULER` | ✅ | 3 | 可独立开关 |
| `NTF_GFORCE_QUAT` | ✅ | 4 | 可独立开关 |
| `NTF_EMG` | ❌ | 0 | 不支持 |
| `NTF_GEST` | ❌ | 0 | 不支持 |
| `NTF_MAG` | ❌ | 0 | 不支持（无独立开关） |
| `NTF_ANGLE_DATA` | ❌ | 0 | 不支持 |
| `NTF_ECG` | ❌ | 0 | 不支持 |
| `NTF_BRTH` | ❌ | 0 | 不支持 |
| `NTF_PPG` | ❌ | 0 | 不支持 |
| `NTF_SPO2` | ❌ | 0 | 不支持 |

### 采样率（spec.sampleRates）与滤波（spec.filters）

| 参数 | 档位 | 默认 | 归属流 | settable | coStream |
|------|------|------|--------|:--------:|----------|
| `EEG_SAMPLE_RATE` | `250`（唯一档位） | `250` | `NTF_EEG` | ✅ | — |
| `IMU_SAMPLE_RATE` | `50`（唯一档位） | `50` | `NTF_IMU` | ✅ | `NTF_EEG`（需与 EEG 同起） |
| `IMPEDANCE` | `1` | `1` | `NTF_IMPEDANCE` | ❌（不可设置） | `NTF_EEG` |

- 滤波四项均支持：`FILTER_50HZ` / `FILTER_60HZ` / `FILTER_HPF` / `FILTER_LPF`。
- 单档位采样率意味着「设置能力」仍要验证：合法档位下发返回成功且 `getParam` 回读一致；非档位值（如 `500`、`0`、空串）应被拒绝或保持原值。

## 环境与前置条件

- SDK 为 Android AAR `app/libs/sensorcapi-release.aar`，`getVersion()` 实测 **1.4.6**；构建与真机测试见仓库根 `AGENTS.md` §五。
- BLE 使用 **Android 原生 BLE**：`SensorController.getInstance().registerBleBridge(context)` 完成桥接；无 dongle、无后端切换。
- Android 设备/模拟器蓝牙开启并授予扫描、连接权限；位置服务按系统版本要求开启。
- 待测设备上电、电量充足、在扫描范围内；广播名以 `OB6000C` 开头。
- 每个测试结束调用 `SensorController.terminate()`（**静态方法**，释放单例与 BLE 资源）；JUnit 的 `@After` 中同样调用。
- 连接/起流/停流等操作之间留 ≥100ms 间隔；等待设备状态用轮询，不用固定长 sleep。
- 设备 MAC / identity 写入 `Test Cases/Automation/config/config.json`（不硬编码）；能力只读 `device_specs/OB6000C.json`。
- 测试入口：`Test Cases/Automation/Generalized/` 下按章节目录的 instrumentation 用例；亦可用 Demo 侧 `AndroidAutomationTests/` 的 runner 执行。

## 文件索引

| 文件 | 内容 |
|------|------|
| [01_控制器与扫描.md](./01_控制器与扫描.md) | `CTRL-*`：扫描、requireSensor、getSensor、版本、terminate |
| [02_连接与状态机.md](./02_连接与状态机.md) | `DEV-SM-*`：连接、断连、重连、自动重连、状态迁移 |
| [03_数据流.md](./03_数据流.md) | `DATA-*`：起流、样本访问、EEG/IMPEDANCE/IMU、采样率与丢包 |
| [04_参数.md](./04_参数.md) | `PARAM-*`：setParam/getParam、EEG/IMU_SAMPLE_RATE、滤波开关 |
| [05_多设备同步.md](./05_多设备同步.md) | `MULTI-*`：同步起流/停流（需 ≥2 台） |
| [06_Bin录制回放解析.md](./06_Bin录制回放解析.md) | `BIN-*`：录制、回放、暂停/恢复/停止、CSV、多 bin |
| [07_电量日志调试.md](./07_电量日志调试.md) | `BATT-*` / `LOG-*` |
| [08_人工硬件用例.md](./08_人工硬件用例.md) | `HW-MAN-*`：人工/仪器用例 |
| [09_异步接口.md](./09_异步接口.md) | `ASYNC-*`：suspend 扩展与 CompletableFuture 变体 |
| [10_底层接口与边界补充.md](./10_底层接口与边界补充.md) | `MISC-*`：SensorData/DeviceInfo/Invalid/不适用 Android 接口 |

## 执行方式汇总

| 模块 | auto | semi-auto | manual |
|------|:----:|:---------:|:------:|
| 控制器与扫描 | 多数 | 蓝牙开关、扫描错误路径 | — |
| 连接与状态机 | 多数 | 断连/断电恢复、bond 状态 | — |
| 数据流 | 多数 | 长稳值守、电极接触 | — |
| 参数 | 多数 | — | — |
| 多设备同步 | 多数 | 第二台设备上下电 | — |
| Bin | 多数 | 磁盘不足、文件导出 | — |
| 电量/日志 | 多数 | 电量变化观察 | — |
| 异步接口 | 多数 | — | — |
| 底层接口与边界 | 多数 | Invalid 触发 | — |
| 人工硬件 | — | — | 全部 |

## 判定标准

- P0 用例 100% 通过；P1 ≥95%；P2 ≥90%。
- 失败保留 logcat 与 `.bin` 用于复现：`adb logcat -s System.out` + `adb pull` bin 文件。
- 缺设备（如多设备同步需 ≥2 台）时整体「跳过（SKIP）」，不判失败。
- 能力字段（`*ChannelCount` / `*SampleRate`）以 `getDeviceInfo()` 运行时结果为准；spec 为唯一期望基线。
- 三态记录：PASS（符合 spec 与断言）/ FAIL（偏离 spec 或断言不通过）/ SKIP（设备不支持、缺设备、无法触发）。
- 错误判定沿用 SDK 语义：`setParam`/`getParam`/`parseBinToCsv` 成功只认 `"OK"`（CSV 解析返回非空路径/成功标识）；以 `Error`、`Not supported`、`<` 开头的字符串或抛异常视为被拒绝/失败。

## 用例优先级与标签

每个用例均有 `优先级` 字段（P0/P1/P2）：

| 优先级 | 含义 | 通过率 |
|--------|------|--------|
| P0 | 核心功能 / 状态机主路径 / 安全 | 100% |
| P1 | 一般功能 / 边界 / 异常恢复 / 性能 | ≥95% |
| P2 | 低优先 / 观测类 | ≥90% |

用例标签由现有字段派生，避免重复维护：

| 标签 | 派生规则 |
|------|----------|
| `@smoke` | 优先级 = P0 |
| `@regression` | 全部用例 |
| `@connection` | CTRL-* / DEV-SM-* / HW-MAN-001~003 |
| `@data-parse` | DATA-* / BIN-* / MISC-006 |
| `@param` | PARAM-* |
| `@state` | DEV-SM-* |
| `@semi-auto` / `@manual` | 对应 `可自动化` 字段 |

## 接口 → 用例映射（覆盖率追踪）

每个接口至少 1 个正向 + 1 个异常/边界用例。主要接口映射如下：

| 类 | 接口 | 正向用例 | 异常/边界用例 |
|----|------|----------|---------------|
| SensorController | `isEnable()` | CTRL-FUNC-002 | CTRL-FUNC-001 |
| SensorController | `startScan(int)` / `stopScan()` / `scan(int)`（suspend） | CTRL-FUNC-003/004/006 | CTRL-BND-001、CTRL-ROB-001 |
| SensorController | `setOnScanResultListener` | CTRL-FUNC-005 | CTRL-FUNC-006 |
| SensorController | `setOnEnableChangedListener` | CTRL-FUNC-002 | CTRL-FUNC-001 |
| SensorController | `setOnScanStateChangeListener` | CTRL-FUNC-014 | CTRL-FUNC-014（蓝牙关闭 errorMsg） |
| SensorController | `requireSensor(BleDevice/String)` / `getSensor(String)` | CTRL-FUNC-007/008 | CTRL-FUNC-007（无效入参） |
| SensorController | `getSensors()` / `getConnectedSensors()` | CTRL-FUNC-009 | — |
| SensorController | `multiStartDataNotification` / `multiStopDataNotification` | MULTI-FUNC-001/002 | MULTI-FUNC-003、MULTI-FUNC-007 |
| SensorController | `replayBinFile` / `pauseBinReplay` / `resumeBinReplay` / `stopBinReplay` | BIN-FUNC-004/006 | BIN-FUNC-007、BIN-ROB-002 |
| SensorController | `multiReplayBinFile` | BIN-FUNC-010 | BIN-BND-002、BIN-ROB-002 |
| SensorController | `parseBinToCsv` / `getBinFileInfo` | BIN-FUNC-002/008 | BIN-FUNC-003、BIN-BND-002 |
| SensorController | `SensorController.terminate()`（静态） | CTRL-FUNC-013 | CTRL-FUNC-013（重复调用） |
| SensorProfile | `connect()` / `disconnect()`（suspend 扩展，返回 Boolean） | DEV-SM-001/004 | DEV-SM-002、DEV-SM-006 |
| SensorProfile | `connectAsync()` / `disconnectAsync()`（CompletableFuture） | ASYNC-FUNC-002/003 | ASYNC-FUNC-010 |
| SensorProfile | `init()`（suspend 扩展） | DATA-FUNC-001 | DATA-FUNC-002、DATA-BND-001/002 |
| SensorProfile | `startDataNotification()` / `stopDataNotification()` | DATA-FUNC-003/004 | DATA-FUNC-002、DATA-BND-001/002 |
| SensorProfile | `setParam` / `getParam`（suspend 扩展） | PARAM-FUNC-001/002/004/006/010/011 | PARAM-FUNC-005/008、PARAM-BND-001 |
| SensorProfile | `getBatteryLevel()` / `setOnPowerListener` | BATT-FUNC-001/002 | BATT-FUNC-004（失效模式） |
| SensorProfile | `setAutoReconnect` / `isAutoReconnecting` | DEV-SM-008 | DEV-SM-009 |
| SensorData | `getSampleRate()` / `getChannelCount()` / `getSampleCount()` | DATA-FUNC-006 | DATA-PERF-001（标称值校验） |
| SensorData | `clone()` | DATA-FUNC-012 | — |
| DeviceInfo | public 字段（`*ChannelCount` / `*SampleRate`） | MISC-FUNC-008 | MISC-FUNC-007 |
| SensorController | `log(msg, level)` / `DEBUG_ENABLED` / `LOG_PATH` | LOG-FUNC-001/005 | LOG-FUNC-002 |
| SensorController | `setOnScanResultListener` 注册状态查询 | CTRL-FUNC-005 | MISC-FUNC-001（Android 无查询接口，SKIP） |

> 未映射到用例的接口显式标「未覆盖」，待 README 定案后补齐。

## 超时 / 轮询 / 重试约定

自动化脚本落地时遵循，手工用例的「等待」类步骤也按此判定：

- **轮询等待**代替固定 sleep：间隔默认 0.2s；扫描发现上限 10s、建立连接上限 15s、参数下发回包上限 5s；超时判 FAIL 并记录「已等待 Xs 未收到回调」。
  - 对应 `TestConfig`：`scanTimeoutMs=5000`、`readyTimeoutMs=15000`、`dataTimeoutMs=12000`、`initTimeoutMs=30000`。
- **重试**：仅环境性偶发（单次 BLE 掉线、扫描未命中）允许重试，上限 2 次并记录（`scanRetryIntervalMs=10000`）；断言类失败不重试；重试后仍失败标 flaky 单独排查。
- **JUnit 超时**：每个用例设 `@Test(timeout = ...)` 或 `withTimeout`，防止 BLE 回调永不到达导致挂死；超时视为 FAIL 并保留现场日志。
- **硬失败 vs 环境异常分流**：断言不通过记 FAIL；设备未响应/链路断开先复测，仍异常判环境故障退出，不误记 SDK 缺陷。
- **semi-auto 人工窗口**：instrumentation 无 stdin，打印操作提示后固定等待窗口（`manualPauseSeconds=30`），测试人员须在窗口内完成开关蓝牙/上下电等操作。

## 环境与依赖固化（版本矩阵）

报告与结论须显式记录，脱离环境谈结论无效：

| 项 | 必填内容 |
|----|----------|
| SDK 版本 | `getVersion()` 实测值（当前 1.4.6） |
| Android / OS | 版本号 |
| BLE 适配器 | 手机型号 + 芯片厂（不同厂 BLE 栈行为有差异，跨适配器结果不可混比） |
| 设备 | 型号、固件版本（`firmwareVersion`）、电量水平 |
| 配对状态 | 是否已 bond |
| 目标配置 | `targetIdentity`（OB6000C 应为 `6C6B`）、spec 文件版本 |

## 参数测试方法论

数值型参数统一用 6 点边界法覆盖等价类与边界：合法值域内取代表值（等价类）+ 边界及边界 ±1；非法/异常值另测越界、空值、错误类型，验证被正确拒绝而非静默接受。OB6000C 的单档位采样率（`EEG_SAMPLE_RATE=250`、`IMU_SAMPLE_RATE=50`）同样要覆盖「非档位值被拒绝」，已落地为 `PARAM-BND-*`。

## 缺陷归因与上报

- 报 bug 前自检：蓝牙开关、扫描/连接权限、位置服务、设备电量、bond 状态、SDK/Android 版本。
- 型号/能力优先排查：先查 `device_specs/OB6000C.json` 与 `getDeviceInfo()` 实际通道数，再判断是否设备能力问题，避免把「设备不支持」误报「SDK 缺陷」。
- 注意 `modelName` 上报 `OB6000` 是已知现象，不作为缺陷；型号判定以 identity/MAC 与 spec 为准。
- 上报至少含：用例 ID、环境矩阵、断言输出、完整 logcat（`adb logcat -s System.out`）、最小复现步骤、涉及的 `.bin`。
