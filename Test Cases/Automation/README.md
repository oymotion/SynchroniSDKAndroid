# Android 自动化测试（对齐 Python `Test Cases/Automation/`）

Android 侧自动化分为两部分：

| 目录 | 用途 |
|---|---|
| `AndroidAutomationTests/`（仓库根） | Demo 页「自动化」Tab 的用例与执行器（`cases/01~10`，其中 `08_system` 为半自动观测） |
| `Test Cases/Automation/` | Instrumentation 测试、配置、设备规格、探针 |

## 目录结构

```
Test Cases/Automation/
├── config/config.json                 # 目标设备 + 通用参数（单一事实来源）
├── device_specs/<model>.json          # 各型号能力规格（唯一型号差异来源）
├── core/                              # 配置/spec/公共辅助（main + androidTest 共用）
├── probes/
│   ├── ProbeDeviceTest.kt             # 设备能力探针（配合 gradlew probeDevice）
│   └── FilterProbeTest.kt             # FILTER_* 读写诊断探针（只打印不断言）
└── Generalized/
    ├── 00_Smoke/ 00_Longevity/ 00_Stress/
    └── 01_Control_Scan … 07_Battery_Log、09_Async_Interface、10_Base_Interface_and_Boundary
       （08 为人工硬件用例，无自动化目录）
```

对应 Python：

- `config.py` ↔ `config/config.json` + `core/AppConfig.kt` + `core/TestConfig.kt`
- `common.py` ↔ `core/CerelaxTestSupport.kt` + `core/CerelaxSpec.kt`
- `device_specs/*.py` ↔ `device_specs/*.json` + `core/DeviceSpec.kt` / `core/SpecStore.kt`
- `Generalized/01~07、09~10` ↔ 同名子目录（08 为人工硬件用例，无目录）

## 用例覆盖现状

| 层 | 规模 | 说明 |
|---|---:|---|
| Demo 自动化（`AndroidAutomationTests/`） | 188 条 | 覆盖全部可自动化手工用例；仅人工硬件项 `HW-MAN-*` 无自动化（Cerelax 6 / gForceMax 5 / OB6000C 3） |
| Instrumentation（`Generalized/`） | 144 个方法 | Demo 的子集；其中 22 个 `@Ignore`（Android 不适用/需人工/未实现占位） |
| Cerelax Pro 手工 | 194 条 | 01~10 章，含 `SYS-ROB-*` |
| gForceMax 手工 | 177 条 | 按 spec 过滤；EEG/PPG/SPO2 等不适用用例未写入 |
| OB6000C 手工 | 186 条 | 按 spec 过滤；PPG/SPO2 等不适用用例未写入 |

- Instrumentation 当前未覆盖约 50 条 Cerelax 非人工用例（SensorAlgo 整段、`SYS-ROB-*`、部分 `MISC/BIN/MULTI/PARAM` 边界），Demo 侧是当前最全实现。
- 手工用例为能力覆盖的权威规格：`Test Cases/Manual Cases/{Cerelax Pro,gForceMax,OB6000C}/`。
- 两套自动化入口的 ID 前缀/方法名映射一致；`@Ignore` 需按原因区分“Android 不适用”与“未实现”。

## 配置与规格加载优先级

设备端按以下顺序读取，任一环节失败只记录日志并回退，不崩溃：

1. 外部 JSON：`/sdcard/Android/data/com.oymotion.sensorcapiktdemo/files/automation/`
   - `config.json`
   - `device_specs/<model>.json`
2. APK assets 内置默认（构建时由 Gradle `syncAutomationAssets` 从本目录拷贝）
3. Kotlin 内置默认（`AppConfig.DEFAULT`）

因此**改目标设备/能力不需要重编 APK**，直接改外部 JSON 即可。

`config.json` 关键字段：

```json
{
  "targetIdentity": "9746",
  "devices": [
    {"model": "Cerelax-Pro", "namePrefix": "Cerelax", "mac": "BC:93:2A:3F:85:17", "identity": "8517"},
    {"model": "Cerelax-Pro", "namePrefix": "Cerelax", "mac": "BC:93:2A:3F:97:46", "identity": "9746"},
    {"model": "gForceMax", "namePrefix": "gForceMax", "mac": "BC:93:2A:3F:9A:A7", "identity": "9AA7"},
    {"model": "OB6000C", "namePrefix": "OB6000C", "mac": "78:1C:9D:E4:6C:6B", "identity": "6C6B"}
  ],
  "params": {"scanTimeoutMs": 5000, "readyTimeoutMs": 15000, "binPath": "..."}
}
```

`device_specs/<model>.json` 与 Python SPEC 同构：`streams` / `imuSubChannels` / `filters` / `sampleRates`
（`sampleRates` 条目可选带 `subStreams`，表示该采样率同时作用于哪些子流，如 gForceMax 的 IMU）。
流 key 使用 AAR 1.4.x 命名：`NTF_MAG`、`NTF_ANGLE_DATA`（不是 `NTF_MAG_ANGLE`）。
当前已登记 3 个型号：`Cerelax-Pro`、`gForceMax`、`OB6000C`。

## 运行

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

# 只编译
.\gradlew.bat :app:compileDebugAndroidTestKotlin --console=plain

# 真机/模拟器跑全部 instrumentation 测试
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain

# 只跑某个用例类
.\gradlew.bat :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.oymotion.sensorcapiktdemo.cerelaxpro.CerelaxScanTest --console=plain
```

用例统一走 `TestConfig.matchesTarget`（mac 精确 > 广播名后四位 identity），不写死 MAC。

## 新设备接入（探针闭环）

```powershell
# 1) 探测（identity 或 index 二选一）
.\gradlew.bat probeDevice -PprobeIdentity=9746
.\gradlew.bat probeDevice -PprobeIndex=0 -PstreamSeconds=10
```

`probeDevice` 会：

1. 跑 `ProbeDeviceTest`（扫描 -> connect/init -> DeviceInfo -> getParam -> 起流统计）；
2. `adb pull` 探针结果（`files/automation/probes/`）；
3. 生成 `Test Cases/Automation/device_specs/<model>.json`（实测通道优先、DeviceInfo 次之）；
4. 幂等更新 `config/config.json` 的 `devices`；
5. `adb push` 回设备外部目录。

生成结果中带 `TODO` 的字段（采样率档位、滤波开关、type 描述）需要人工复核。

## 用例编写约定

- 能力判定只读 spec：`TestConfig.targetSpec()` / `TestConfig.specForIdentity(...)`。
- spec 不支持的流用 `org.junit.Assume.assumeTrue(...)` 记为 SKIP，不硬断言。
- 流 key → DataType / DeviceInfo 字段的映射统一用 `CerelaxSpec`。
- 手工用例 ID 与方法名映射保持不变（如 `CTRL-FUNC-001` ↔ `ctrlFunc001_...`）。
