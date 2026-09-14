# Cerelax 脑电头环 SDK 测试用例（总览）

本目录针对 **Cerelax 脑电头环**，参照 OB6000C 测试结构，按模块拆分为多个用例文件。每个用例统一标注：测试目的、流程与逻辑、预期结果、有效性说明、是否可自动化、人工介入。用例 ID 沿用测试计划，便于追溯。

## 设备信息

| 项 | 内容 |
|----|------|
| 设备型号 | Cerelax（脑电头环） |
| 广播名前缀 | 待确认 |
| 类型归属 | 头环 / EEG 设备 |
| 核心能力 | NTF_EEG（2 通道脑电）、NTF_PPG、NTF_IMU、NTF_IMPEDANCE（电极接触检测） |
| 采样率 | EEG `1000\|500\|250`（默认 `1000`）；IMU `50\|100\|200`；PPG `50\|100\|200\|400` |
| SDK 版本 | 0.9.6 |

## 能力假设（连接后用 getDeviceInfo() 确认）

> 下列能力均需通过 `getDeviceInfo()` 运行时确认，不做硬编码假设。`>0` 才执行对应用例，否则记录"设备不支持，跳过"。

| 能力 | 判定字段（DeviceInfo） | 假设 | 状态 |
|------|------------------------|------|------|
| 脑电 EEG | `EegChannelCount > 0` | 支持（2 通道，核心） | 待确认 |
| PPG | `PpgChannelCount > 0` | 支持（核心） | 待确认 |
| 聚合 IMU（NTF_IMU） | `ImuChannelCount > 0` | 待确认（聚合流或四路独立 `NTF_GFORCE_*`） | 待确认 |
| 加速度 ACC | `AccChannelCount > 0` | 可能支持（IMU 组成） | 待确认 |
| 陀螺仪 GYRO | `GyroChannelCount > 0` | 可能支持（IMU 组成） | 待确认 |
| 欧拉角 Euler | `EulerChannelCount > 0` | 可能支持 | 待确认 |
| 四元数 Quat | `QuatChannelCount > 0` | 可能支持 | 待确认 |
| 阻抗 IMPEDANCE | `ImpeChannelCount > 0` | 支持（2 通道电极接触检测） | 待确认 |
| SpO2 | `Spo2ChannelCount > 0` | 待确认（PPG 相关） | 待确认 |
| 心电 ECG | `EcgChannelCount > 0` | 不支持（头环无 ECG） | 待确认 |
| 肌电 EMG | `EmgChannelCount > 0` | 不支持（脑电头环） | 待确认 |
| 手势 GEST | 起流后看 NTF_GEST | 不支持（脑电头环） | 待确认 |
| 呼吸 BRTH | `BrthChannelCount > 0` | 不支持 | 待确认 |
| 磁角度 MAG_ANGLE | `MagAngleChannelCount > 0` | 待确认 | 待确认 |

> 与 OB6000C 的关键差异：Cerelax 为 **2 通道 EEG 头环**（OB6000C 为 32 通道），并**新增 PPG / IMU / Impedance 电极接触检测**；不支持 ECG / EMG / GEST / BRTH。
> 采样率有三套（EEG / IMU / PPG）。当前 SDK 版本的 `setParam` 仅提供 `EEG_SAMPLE_RATE` 设置接口；IMU / PPG 采样率设置（`IMU_SAMPLE_RATE` / `PPG_SAMPLE_RATE`）**为规划中的功能，当前版本尚未提供**，仅可读 `getDeviceInfo()` 的只读字段（`ImuSampleRate` / `PpgSampleRate`）。相关设置用例已先行登记（PARAM-FUNC-012/013、DATA-FUNC-014），待 SDK 实现后执行。

## 环境与前置条件

- Python 3.10~3.14，`sensor-sdk==0.9.6`（`pip install --upgrade sensor-sdk`）。
- 蓝牙开启（bleak 后端）或 dongle 已绑定（bumble 后端，`checkSetupDongle()` 返回 `OK`）。
- 设备上电、在扫描范围内。
- 每个脚本结束调用 `SensorControllerInstance.terminate()`；Ctrl+C 异常路径也需调用。
- 连接/起流/停流等操作之间留 ≥100ms 间隔。
- 设备 MAC、前缀写入独立 `config.py`（不硬编码）。

## 文件索引

| 文件 | 内容 |
|------|------|
| [01_控制器与扫描.md](./01_控制器与扫描.md) | `CTRL-*`：扫描、requireSensor、后端、版本、终止 |
| [02_连接与状态机.md](./02_连接与状态机.md) | `DEV-SM-*`：连接、断连、重连、状态迁移 |
| [03_数据流.md](./03_数据流.md) | `DATA-*`：起流、样本访问、EEG/PPG/IMU/Impedance |
| [04_参数.md](./04_参数.md) | `PARAM-*`：setParam/getParam、EEG/IMU/PPG_SAMPLE_RATE |
| [05_多设备同步.md](./05_多设备同步.md) | `MULTI-*`：同步起流/停流（需 ≥2 台） |
| [06_Bin录制回放解析.md](./06_Bin录制回放解析.md) | `BIN-*`：录制/回放/CSV |
| [07_电量日志调试.md](./07_电量日志调试.md) | `BATT-*` / `LOG-*` |
| [08_人工硬件用例.md](./08_人工硬件用例.md) | `HW-MAN-*`：人工/仪器用例 |
| [09_异步接口.md](./09_异步接口.md) | `ASYNC-*`：async 变体接口 |
| [10_底层接口与边界补充.md](./10_底层接口与边界补充.md) | `MISC-*`：序列化/复用/Invalid/DeviceInfo |

## 执行方式汇总

| 模块 | auto | semi-auto | manual |
|------|:----:|:---------:|:------:|
| 控制器与扫描 | 多数 | 蓝牙开关、dongle 引导 | — |
| 连接与状态机 | 多数 | 断连/断电恢复 | — |
| 数据流 | 多数 | 长稳值守 | — |
| 参数 | 多数 | — | — |
| 多设备同步 | 多数 | 后端切换 | — |
| Bin | 多数 | 磁盘不足 | — |
| 电量/日志 | 多数 | 电量变化观察 | — |
| 异步接口 | 多数 | — | — |
| 底层接口与边界 | 多数 | Invalid 触发 | — |
| 人工硬件 | — | — | 全部 |

## 判定标准

- P0 用例 100% 通过；P1 ≥95%；P2 ≥90%。
- 失败保留 SDK 日志与 `.bin` 用于复现。
- 缺设备（如多设备同步需 ≥2 台）时整体"跳过"，不判失败。
- 所有能力字段（`ChannelCount`/`SampleRate`）以 `getDeviceInfo()` 运行时结果为准，不硬编码假设。

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
| `@connection` | CTRL-* / DEV-SM-* / HW-MAN-001~003 |
| `@data-parse` | DATA-* / BIN-* / MISC-002~006 |
| `@param` | PARAM-* |
| `@state` | DEV-SM-* |
| `@semi-auto` / `@manual` | 对应 `可自动化` 字段 |

## 接口 → 用例映射（覆盖率追踪）

每个接口至少 1 个正向 + 1 个异常/边界用例（§八.2）。主要接口映射如下（完整以测试计划 §2.1 为准）：

| 类 | 接口 | 正向用例 | 异常/边界用例 |
|----|------|----------|---------------|
| SensorController | isEnable | CTRL-FUNC-002 | CTRL-FUNC-001 |
| SensorController | startScan/stopScan/scan | CTRL-FUNC-003/004/006 | CTRL-BND-001、CTRL-ROB-001/002 |
| SensorController | requireSensor/getSensor | CTRL-FUNC-007/008 | CTRL-BND-002 |
| SensorController | getConnectedSensors/Devices | CTRL-FUNC-009 | — |
| SensorController | multiStart/multiStop | MULTI-FUNC-001/002 | MULTI-BND-001、MULTI-ROB-001 |
| SensorController | replayBinFile/pause/resume/stop | BIN-FUNC-004/006 | BIN-FUNC-007、BIN-ROB-002 |
| SensorController | multiReplayBinFile | BIN-FUNC-011 | BIN-BND-003、BIN-ROB-003 |
| SensorController | parseBinToCsv/getBinFileInfo | BIN-FUNC-002/008 | BIN-FUNC-003、BIN-BND-002 |
| SensorController | terminate | CTRL-FUNC-013 | — |
| SensorProfile | connect/disconnect | DEV-SM-001/004 | DEV-SM-002、DEV-SM-015/016 |
| SensorProfile | init | DATA-FUNC-001 | DATA-FUNC-002、DATA-BND-001/002、DATA-ROB-001 |
| SensorProfile | start/stopDataNotification | DATA-FUNC-003/004 | DATA-FUNC-002、DATA-ROB-002 |
| SensorProfile | setParam/getParam | PARAM-FUNC-001/002/004/006 | PARAM-FUNC-005/008、PARAM-BND-001/002 |
| SensorProfile | getBatteryLevel/onPowerChanged | BATT-FUNC-001/002 | BATT-FUNC-004（失效模式） |
| SensorProfile | async 变体 | ASYNC-FUNC-001~012 | ASYNC-FUNC-010（混用限制） |
| SensorData | getSampleRate/getChannelCount 等 | DATA-FUNC-006 | DATA-PERF-003（标称值校验） |
| SensorData | clone/clear/reset/to_flatbuffers | DATA-FUNC-012、MISC-003~005 | — |

> 未映射到用例的接口（如底层序列化签名待确认项）显式标"未覆盖"，待 README 定案后补齐。

## 超时 / 轮询 / 重试约定（§四）

自动化脚本落地时遵循，手工用例的"等待"类步骤也按此判定：

- **轮询等待**代替固定 sleep：间隔默认 0.2s；扫描发现上限 10s、建立连接上限 15s、参数下发回包上限 5s；超时判 FAIL 并记录"已等待 Xs 未收到回调"。
- **重试**：仅环境性偶发（单次 BLE 掉线）允许重试，上限 2 次并记录；断言类失败不重试；重试后仍失败标 flaky 单独排查。
- **硬失败 vs 环境异常分流**：断言不通过记 FAIL；设备未响应/链路断开先复测，仍异常判环境故障退出，不误记 SDK 缺陷。

## 环境与依赖固化（版本矩阵，§六）

报告与结论须显式记录，脱离环境谈结论无效：

| 项 | 必填内容 |
|----|----------|
| SDK 版本 | `getVersion()` 实测值 |
| Python / OS | 版本号 |
| BLE 适配器 | 型号 + 驱动版本（不同芯片厂 BLE 栈行为有差异，跨适配器结果不可混比） |
| 设备 | 型号、固件版本、电量水平 |
| 配对状态 | 是否已 bond |

## 参数测试方法论（§二）

数值型参数统一用 6 点边界法覆盖等价类与边界：合法值域内取代表值（等价类）+ 边界及边界 ±1；非法/异常值另测越界、空值、错误类型，验证被正确拒绝而非静默接受。已落地的边界用例见 `CTRL-BND-001`、`DATA-BND-001/002` 等。

## 缺陷归因与上报（§十一）

- 报 bug 前自检：蓝牙开关、dongle 识别、驱动、设备电量、bond 状态、SDK/Python 版本。
- 设备型号/能力优先排查：先查文档有无型号要求，再判断是否设备能力问题，避免把"设备不支持"误报"SDK 缺陷"。
- 上报至少含：用例名、环境矩阵、断言输出、完整 traceback、最小复现步骤。
