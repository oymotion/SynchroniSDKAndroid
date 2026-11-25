# Android 自动化测试使用文档

本套自动化测试是 **spec 驱动** 的：用例代码是「通用算法」，不感知具体型号；型号差异全部外置在
`config.json`（本轮目标设备）与 `device_specs/<model>.json`（设备能力规格）两个文件里。
换设备 = 改 `targetIdentity` + 换对应 spec，**不需要重编 APK、不改用例代码**。

---

## 1. 核心机制

```
config.json
  └─ targetIdentity("9746")
        │  按 identity 反查 devices
        ▼
     model = "Cerelax-Pro"
        │  loadSpec(model)
        ▼
device_specs/Cerelax-Pro.json
        │  解析为 DeviceSpec
        ▼
   用例按 spec 运行时判定能力（supported/channels/sampleRates）
```

- 用例里**不硬编码**任何型号数值（通道数、采样率、流开关都读 spec）。
- 能力判定口径：`supported=true 且 channels>0` 才测，否则用 `Assume.assumeTrue` 记 SKIP。
- 型号差异只体现在 `config.json` 与 `device_specs/<model>.json`，测试代码零改动。

---

## 2. 目录结构

```
Test Cases/Automation/
├── config/config.json                 # 目标设备 + 通用参数（单一事实来源）
├── device_specs/<model>.json          # 各型号能力规格（唯一型号差异来源）
├── core/                              # 配置/spec/公共辅助（main + androidTest 共用）
│   ├── AppConfig.kt / ConfigStore.kt  # config.json 解析
│   ├── TestConfig.kt                  # targetIdentity → model → spec 查询入口
│   ├── DeviceSpec.kt / SpecStore.kt   # device_specs 解析与加载
│   └── CerelaxSpec.kt                 # stream key → DataType / DeviceInfo 三层映射
├── probes/
│   ├── ProbeDeviceTest.kt             # 设备能力探针（配合 gradlew probeDevice）
│   └── FilterProbeTest.kt             # FILTER_* 读写诊断探针（只打印不断言）
├── known_issues.json                  # 失败用例 → 问题编号映射（HTML 报告用）
├── make_html_report.py                # report_*.json → reports/report_<model>.html
├── reports/report_<model>.html        # 已生成的 HTML 报告（按型号覆盖）
└── Generalized/                       # 通用用例（按章节，与型号无关）
    ├── 00_Smoke/ 00_Longevity/ 00_Stress/
    ├── 01_Control_Scan … 07_Battery_Log
    └── 09_Async_Interface、10_Base_Interface_and_Boundary
        （08 为人工硬件用例，无自动化目录）
```

---

## 3. 快速开始：测一台已登记设备

只需改 [config/config.json](config/config.json) 的 `targetIdentity`，改成目标设备后四位 identity：

```json
{
  "targetIdentity": "9AA0",
  "devices": [
    {"model": "Cerelax-Pro", "namePrefix": "Cerelax", "mac": "BC:93:2A:3F:85:17", "identity": "8517"},
    {"model": "Cerelax-Pro", "namePrefix": "Cerelax", "mac": "BC:93:2A:3F:97:46", "identity": "9746"},
    {"model": "gForceMax",  "namePrefix": "gForceMax", "mac": "BC:93:2A:3F:9A:A7", "identity": "9AA7"},
    {"model": "gForceMax",  "namePrefix": "gForceMax", "mac": "BC:93:2A:3F:9A:A0", "identity": "9AA0"},
    {"model": "OB6000C",    "namePrefix": "OB6000C",  "mac": "78:1C:9D:E4:6C:6B", "identity": "6C6B"}
  ]
}
```

| 想测的设备 | `targetIdentity` 改成 |
|---|---|
| Cerelax-Pro（8517） | `"8517"` |
| Cerelax-Pro（9746） | `"9746"` |
| gForceMax（9AA7） | `"9AA7"` |
| gForceMax（9AA0） | `"9AA0"` |
| OB6000C | `"6C6B"` |

改完跑测试即可（见 §6）。目标设备匹配统一走 `TestConfig.matchesTarget`：
**mac 精确命中 > 广播名后四位 identity**，多台登记不会互相误命中。

也可以在 Demo App 的「自动化」Tab 里点「选择设备」运行时切换：勾选已登记设备后，
会先断开旧连接，再 `TestConfig.setTargetIdentity` 覆盖运行时目标，并把目标**写回设备端外部
`automation/config.json`**，让后续 instrumentation 运行保持一致；旁边的「扫描检查」只扫描并报告
已登记设备是否在范围内，不改目标。

---

## 4. `config.json` 字段说明

```json
{
  "targetIdentity": "9746",            // 本轮目标 identity（逗号分隔可多个）
  "devices": [
    {
      "model": "Cerelax-Pro",          // 型号名，决定加载 device_specs/<model>.json
      "namePrefix": "Cerelax",         // 广播名前缀（备用匹配）
      "mac": "BC:93:2A:3F:85:17",      // MAC（精确匹配优先）
      "identity": "8517"               // 广播名后四位（备选匹配）
    }
  ],
  "params": {                          // 通用测试参数，可覆盖内置默认
    "scanTimeoutMs": 5000,
    "scanRetryIntervalMs": 10000,
    "connectTimeoutMs": 15000,
    "readyTimeoutMs": 15000,
    "dataTimeoutMs": 12000,
    "initTimeoutMs": 30000,
    "packageSampleCount": 20,
    "powerRefreshMs": 1000,
    "caseSettleMs": 1000,
    "collectSeconds": 5,
    "minSamples": 1,
    "manualPauseSeconds": 30,
    "perfMeasureSeconds": 10,
    "sampleRateTolerance": 0.05,
    "longevityStreamSeconds": 30,
    "binPath": "...",                   // bin 回放/解析用的本地 bin 路径
    "multiBinPaths": ["...", "..."],    // 多 bin 同步回放的文件列表
    "multiReplayEnabled": false         // 是否启用多 bin 同步回放
  }
}
```

---

## 5. `device_specs/<model>.json` 字段说明

每个型号一份，是**唯一型号差异来源**，与 Python `device_specs/*.py` 的 SPEC 同构：

```json
{
  "model": "Cerelax-Pro",
  "type": "脑电头环（2 通道 EEG + 2 通道 PPG + 6 轴 IMU 聚合 + 电极接触检测）",
  "namePrefix": "Cerelax",
  "relax": true,                        // 是否提供 SensorAlgo 放松指数（缺省 false）
  "streams": {                          // 数据流能力
    "NTF_EEG": {"supported": true, "channels": 2},
    "NTF_PPG": {"supported": true, "channels": 2},
    "NTF_SPO2": {"supported": true, "channels": 2},
    "NTF_IMU": {"supported": true, "channels": 6},
    "NTF_IMPEDANCE": {"supported": true, "channels": 2},
    "NTF_EMG": {"supported": false, "channels": 0}   // 不支持 = supported false 或 channels 0
  },
  "imuSubChannels": {                   // 聚合流子通道组成（NTF_IMU = acc+gyro+...）
    "AccChannelCount": 3,
    "GyroChannelCount": 3,
    "EulerChannelCount": 0,
    "QuatChannelCount": 0
  },
  "filters": {                          // 滤波开关能力
    "FILTER_50HZ": true,
    "FILTER_60HZ": true,
    "FILTER_HPF": true,
    "FILTER_LPF": true
  },
  "sampleRates": {                      // 采样率参数
    "EEG_SAMPLE_RATE": {
      "rates": ["250", "500", "1000"],  // 可选档位
      "default": "1000",                // 默认档
      "stream": "NTF_EEG",              // 所属流
      "settable": true,                 // 是否可下发设置
      "coStream": null                  // 共流（如 IMPEDANCE 随 EEG 一起起停）
    },
    "IMU_SAMPLE_RATE": {"rates": ["50", "100", "200"], "default": "200", "stream": "NTF_IMU", "settable": true, "coStream": null},
    "PPG_SAMPLE_RATE": {"rates": ["50", "100", "200", "400"], "default": "50", "stream": "NTF_PPG", "settable": true, "coStream": null},
    "IMPEDANCE": {"rates": ["1"], "default": "1", "stream": "NTF_IMPEDANCE", "settable": false, "coStream": "NTF_EEG"}
  }
}
```

判定规则：

- **流**：`streams[key].supported == true && channels > 0` 才认为支持，否则用例记 SKIP。
- **采样率**：`settable == true` 才测 `setParam` 设置；`coStream` 非空表示该流随主流起停；
  可选 `subStreams` 表示该采样率同时作用于哪些子流（如 gForceMax 的 `IMU_SAMPLE_RATE`
  同时作用于 `NTF_ACC/NTF_GYRO/NTF_EULER_DATA/NTF_QUATERNION`）。
- **通道数**：用例用 `spec.channels(key)` 断言，不写死数值。
- **IMU 子流**：`streams` 还含 `NTF_GFORCE_ACC/NTF_GFORCE_GYRO/NTF_GFORCE_EULER/NTF_GFORCE_QUAT`
  子流项（Cerelax 仅 ACC/GYRO 有独立开关，gForceMax 四项全有），供 `CerelaxSpec` 校验聚合流拆分。
- **放松指数**：`relax == true` 表示设备提供 SensorAlgo 的 RELAX 能力（缺省 false）。
- **滤波**：spec 的 `filters` 只声明「支持哪些 FILTER_* 键」，实际可切换键以设备
  `getParam("FILTER")` 返回的聚合列表为准（两者不一致时用 `FilterProbeTest` 诊断）。

---

## 6. 运行测试

在仓库根 `Android/SynchroniSDKAndroid` 下：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

# 只编译（检查语法）
.\gradlew.bat :app:compileDebugAndroidTestKotlin --console=plain

# 真机/模拟器跑全部 instrumentation 测试
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain

# 只跑某个用例类
.\gradlew.bat :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.oymotion.sensorcapiktdemo.cerelaxpro.CerelaxScanTest --console=plain
```

- 成功以末尾 `BUILD SUCCESSFUL` 为准。
- Kotlin daemon 偶发 `AccessDeniedException` 会自动回退非 daemon 编译，可忽略；频繁时 `.\gradlew.bat --stop`。

---

## 7. 配置 / spec 加载优先级

| 内容 | 优先级（高 → 低） |
|---|---|
| `config.json` | 外部 `/sdcard/Android/data/com.oymotion.sensorcapiktdemo/files/automation/config.json` > assets > 内置默认 |
| `device_specs/<model>.json` | 外部 `/sdcard/.../files/automation/device_specs/<model>.json` > assets |

- 外部 JSON 解析失败只记日志并回退，不崩溃。
- 因此**改目标设备/能力不需要重编 APK**：把改好的 JSON `adb push` 到设备外部目录即可（或改 assets 后重编）。

---

## 8. 接入新设备

先探测能力，再生成 spec：

```powershell
# 探测（identity 或 index 二选一）
.\gradlew.bat probeDevice -PprobeIdentity=9746
.\gradlew.bat probeDevice -PprobeIndex=0 -PstreamSeconds=10
```

`probeDevice` 会：扫描 → connect/init → 读 DeviceInfo → getParam → 起流统计，然后生成
`device_specs/<model>.json`（实测通道优先、DeviceInfo 次之），并幂等更新 `config.json` 的 `devices`。

生成结果里带 `TODO` 的字段（采样率档位、滤波开关、`type` 描述）需要人工复核后再用。

---

## 9. 用例编写约定

- 能力来源只认 spec：`TestConfig.targetSpec()` / `TestConfig.specForIdentity(identity)`。
- 不支持的流用 `org.junit.Assume.assumeTrue(...)` 记 SKIP，不硬断言。
- stream key → DataType / DeviceInfo 字段的映射统一走 `CerelaxSpec`（三层映射：
  spec 开关 key → `getDataType()` 常量 → `DeviceInfo` 通道/采样率字段）。
- 手工用例 ID 与方法名映射固定，如 `CTRL-FUNC-001` ↔ `ctrlFunc001_...`。

---

## 10. 测试报告

> 报告**只由 Demo App 的「自动化」Tab 运行时生成**（执行器在
> [AndroidAutomationTests/report.kt](../../AndroidAutomationTests/report.kt)）。
> `gradlew connectedDebugAndroidTest` 跑的 instrumentation 套件不写这份报告，
> 结论看 Gradle 测试结果与 `adb logcat -s System.out`（见 §11）。

每次 Demo 页运行生成 **两份同名不同后缀** 的报告：

| 文件 | 格式 | 用途 |
|---|---|---|
| `report_<时间戳>.json` | JSON | 机器读（结构化，供脚本汇总/对比/入库） |
| `report_<时间戳>.txt` | 纯文本 | 人读（一眼扫 `[PASS]/[FAIL]` 列表） |

JSON 结构：`summary` + `results` 数组，每条用例一个对象：

```json
{
  "generatedAt": "2026-11-18T12:00:00.000+08:00",
  "summary": "total=3 pass=2 fail=0 skip=1",
  "results": [
    {
      "id": "CTRL-FUNC-001",
      "title": "...",
      "chapter": "01_控制与扫描",
      "priority": "P0",
      "mode": "AUTO",
      "verdict": "PASS",
      "detail": "...",
      "elapsedMs": 1234
    }
  ]
}
```

字段说明：

- `verdict`：`PASS` / `FAIL` / `SKIP` / `PENDING`（`PENDING` 为 semi-auto/manual 占位）。
- `detail`：一句话结论或失败原因（如 `等待超时: 设备 Ready (15000ms)`），**不含过程日志**。

报告写入两个位置：

1. 私有外部目录（带时间戳，保留历史版本）：
   `/sdcard/Android/data/com.oymotion.sensorcapiktdemo/files/automation/report_*.json`
2. 公共下载目录（固定文件名，每次覆盖上一份）：
   `/sdcard/Download/sensor_automation_report.json` / `.txt`

> `detail` 只存结论，**详细执行日志走 logcat，不进报告**（见 §11）。

### 10.1 HTML 报告

把 Demo 导出的 `report_*.json` 转成可读的 HTML 报告（每型号一份，文件名不含时间戳，覆盖上一版）：

```powershell
python "Test Cases/Automation/make_html_report.py" reports/report_gForceMax_20261125_122752.json
python "Test Cases/Automation/make_html_report.py" reports/*.json --out reports
```

- 输出：`reports/report_<model>.html`，含「总用例 / 通过 / 失败 / 跳过 / 待人工 / 通过率（已执行）」概览、
  缺陷清单、按章节用例明细。
- 自动/半自动/人工三类都进报告并标注；spec 不支持（`detail` 以「当前设备 spec 不支持」开头）的用例不进报告。
- 通过率 = 通过 / 已执行（通过 + 失败 + 跳过），`PENDING`（待人工）不计入分母。
- 失败用例若在 [known_issues.json](known_issues.json) 登记问题编号，报告显示「失败 · 问题 NNNN」并进缺陷清单；
  键支持「用例ID」全局生效与「型号/用例ID」机型优先，`_` 开头的键忽略。

---

## 11. 日志与排查

日志**生成在手机端**，电脑端通过 `adb` 拉取。分两条通道：

| 运行方式 | 日志落点 | 电脑端查看 |
|---|---|---|
| Instrumentation（`gradlew connectedDebugAndroidTest`） | 手机 logcat 的 `System.out` 标签 | `adb logcat -s System.out` |
| Demo App 内（「自动化」Tab 点运行） | App UI 日志区（内存，最多 500 条） | 直接看 App 界面 |

排查步骤：

```powershell
# 1) 实时抓详细日志（含每步 scan/connect/init/断言失败原因）
adb logcat -s System.out -v time > log.txt

# 2) 拉报告定位「哪个用例挂了」
adb pull /sdcard/Android/data/com.oymotion.sensorcapiktdemo/files/automation/report_*.json
adb pull /sdcard/Download/sensor_automation_report.json
```

一句话：**Demo 运行先看报告定位失败用例 → 再抓 `adb logcat -s System.out` 看该用例的详细失败步骤；
instrumentation 运行没有报告，直接看 Gradle 测试结果 + `adb logcat -s System.out`。**

---

## 12. 用例规模与覆盖现状

| 层 | 规模 | 说明 |
|---|---:|---|
| Demo 自动化（`AndroidAutomationTests/`） | 188 条 | 覆盖全部可自动化手工用例；仅 `HW-MAN-*` 人工硬件项无自动化 |
| Instrumentation（`Generalized/`） | 147 个章节用例方法 | 另有 4 个 smoke/longevity/stress 总测；Demo 的子集，其中 16 个 `@Ignore` |
| Cerelax Pro 手工 | 194 条 | `Test Cases/Manual Cases/Cerelax Pro/` |
| gForceMax 手工 | 177 条 | `Test Cases/Manual Cases/gForceMax/`，已按 spec 排除不适用能力 |
| OB6000C 手工 | 186 条 | `Test Cases/Manual Cases/OB6000C/`，已按 spec 排除不适用能力 |

- Instrumentation 尚未覆盖约 50 条 Cerelax 非人工用例（SensorAlgo 整段、`SYS-ROB-*`、部分 `MISC/BIN/MULTI/PARAM` 边界）。
- 两套入口的用例 ID 与方法名映射一致；`@Ignore` 需区分“Android 不适用”和“未实现”。
- 只有 Demo 侧生成 `report_*.json/.txt`；Instrumentation 以 Gradle 结果 + logcat 为准（见 §10、§11）。
