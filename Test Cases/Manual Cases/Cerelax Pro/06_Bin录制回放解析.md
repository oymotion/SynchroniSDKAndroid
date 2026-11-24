# Cerelax Bin 录制/回放/解析用例（BIN-*）

### BIN-FUNC-001 连接后自动生成 .bin
- **优先级**：P0
- **测试目的**：验证 bin 自动录制。
- **流程与逻辑**：连接起流，检查是否生成 `{DeviceName}_{MAC}_{时间戳}.bin`。
- **预期结果**：生成 bin 文件。
- **有效性说明**：数据落盘。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

### BIN-FUNC-002 getBinFileInfo 返回字段
- **优先级**：P0
- **测试目的**：验证 bin 信息。
- **流程与逻辑**：对有效 bin 调 `getBinFileInfo`。
- **预期结果**：返回含 `device_mac/device_name/chip_type/replay_duration` 的 dict。
- **有效性说明**：bin 元数据完整。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

### BIN-FUNC-003 getBinFileInfo 无效输入返回 None
- **优先级**：P0
- **测试目的**：验证逆向。
- **流程与逻辑**：对不存在/无 config 的 bin 调 `getBinFileInfo`。
- **预期结果**：返回 None。
- **有效性说明**：容错。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

### BIN-FUNC-004 回放 realtime=True 按原始节奏
- **优先级**：P0
- **测试目的**：验证实时回放。
- **流程与逻辑**：`replayBinFile(path, sensor, realtime=True)`，统计 `onDataCallback` 批数；比对回放是否产生数据、包含 live 的 DataType、startTimeStamp 非 None。
- **预期结果**：回放产生数据，DataType 覆盖 live 类型，startTimeStamp 非 None；回放耗时与录制时长同量级。精确批数可能因 bin 解析器分组方式不同而存在差异，仅作参考。
- **有效性说明**：回放数据保真 + 节奏保真。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

### BIN-FUNC-005 回放 realtime=False 全速
- **优先级**：P1
- **测试目的**：验证全速回放。
- **流程与逻辑**：`replayBinFile(path, sensor, realtime=False)`，统计 `onDataCallback` 批数；比对回放是否产生数据、包含 live 的 DataType、startTimeStamp 非 None。
- **预期结果**：回放产生数据，DataType 覆盖 live 类型，startTimeStamp 非 None；回放耗时远小于录制时长（全速）。精确批数可能因 bin 解析器分组方式不同而存在差异，仅作参考。
- **有效性说明**：回放数据保真 + 全速加速。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

### BIN-FUNC-006 pause/resume/stop 回放
- **优先级**：P1
- **测试目的**：验证回放控制。
- **流程与逻辑**：回放中依次 `pauseBinReplay`/`resumeBinReplay`/`stopBinReplay`。
- **预期结果**：返回 OK，暂停/恢复/中止生效。
- **有效性说明**：回放可控。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

### BIN-FUNC-007 实时起流中拒绝回放
- **优先级**：P1
- **测试目的**：验证互斥。
- **流程与逻辑**：目标 sensor 正在实时起流时调回放。
- **预期结果**：拒绝回放。
- **有效性说明**：状态互斥。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

### BIN-FUNC-008 parseBinToCsv 生成 CSV
- **优先级**：P1
- **测试目的**：验证解析导出。
- **流程与逻辑**：`parseBinToCsv`，检查 CSV 行类型与表头。
- **预期结果**：返回 CSV 路径，文件存在；首行为 `timestamp,mac,type,...` 表头；每行 10 列；`type` 列值均在 README 定义内（`raw`/`cmd_send`/`cmd_recv`/`event`/`parsed`），无未知类型。注意：五种行类型出现与否取决于 bin 内容（如 `cmd_recv` 需有命令响应），不全出现不判失败。
- **有效性说明**：数据可分析。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

### BIN-FUNC-009 回放还原 startTimeStamp/delay 与实收一致
- **优先级**：P1
- **测试目的**：验证回放保真度。
- **流程与逻辑**：对比回放与实收的 `startTimeStamp/delay`。
- **预期结果**：一致。
- **有效性说明**：回放时间戳还原。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

### BIN-FUNC-010 multiReplayBinFile 多文件同步回放
- **优先级**：P0
- **测试目的**：验证 `multiReplayBinFile` 多 bin 文件按共享时钟对齐同步回放。
- **流程与逻辑**：在 `config.json` 的 `multiBinPaths` 显式配置 ≥2 个不同 MAC 的有效 bin（首次可先跑 `BIN-FUNC-010-REC` 录制，路径会打印在用例结果里）；`multiReplayBinFile(file_paths, sensors=None, realtime=True)`；统计各成员 `onDataCallback` 批数与 DataType。
- **预期结果**：返回列表长度等于输入文件数，每个成员返回对应 `SensorProfile`（或 None 表示该成员失败）；各成员均产生回放数据；DataType 覆盖各自 live 类型；全组以最早首条数据记录为 t=0，保留录制时的相对偏移（各成员 `startTimeStamp` 相对关系与录制时一致）。
- **有效性说明**：多设备/多段 bin 的同步回放能力。
- **可自动化**：auto
- **人工介入**：无（首次需运行 `BIN-FUNC-010-REC` 录制配对 bin，并把路径回填 `multiBinPaths`）
- **测试结果**：已实现，默认关闭（真机复现 SDK JNI 崩溃：`nativeMultiReplayBinFile` 使用失效 jstring 局部引用 → SIGABRT 杀进程；修复后可改 `config.json` 的 `multiReplayEnabled=true` 验证）

> **Android 已知问题（2026-11-23 真机复现，SDK JNI bug）**：`multiReplayBinFile` 传入 2 个 bin + 2 个 MAC 时必现进程被杀。
> logcat 关键信息：
> `JNI DETECTED ERROR IN APPLICATION: JNI ERROR (app bug): jstring is an invalid local reference ... from long[] SensorController.nativeMultiReplayBinFile(long, String[], String[], int, int)`，
> 随后 `Fatal signal 6 (SIGABRT)`。属 JNI 局部引用被提前释放后继续使用（与用例环境无关；已先断开同 MAC 连接态 profile 仍复现）。
> Demo 自动化默认关闭该能力（`multiReplayEnabled=false`）记 SKIP；修复前若需保留覆盖，只能把该用例放到独立进程执行。修复后改外部 `config.json` 打开开关验证。

### BIN-BND-001 默认不导出：临时文件短暂存在后删除
- **优先级**：P1
- **测试目的**：验证默认（未设置 `DEBUG_BLE_DATA_PATH`）时 bin 不持久落盘，临时文件在断开后删除。
- **流程与逻辑**：不设置 `DEBUG_BLE_DATA_PATH`，连接起流；起流期间观察系统临时目录是否出现疑似临时 bin；`stopDataNotification`/`disconnect` 后确认日志目录无新增 `.bin`，且此前观察到的临时文件已消失。
- **预期结果**：日志目录不新增 `.bin`；临时文件（若可观测）在断开后消失。
- **有效性说明**：bin 导出默认关闭 + 临时文件生命周期。
- **可自动化**：auto（临时文件观察为 best-effort/informational）
- **人工介入**：无
- **测试结果**：待测试

### BIN-ROB-001 磁盘不足时跳过/停止录制
- **优先级**：P2
- **测试目的**：验证写盘异常处理。
- **流程与逻辑**：模拟磁盘 <100MB 或写盘错误，观察是否跳过/停止且不影响实时流。
- **预期结果**：不崩溃，实时流继续。
- **有效性说明**：资源边界健壮性。
- **可自动化**：auto（通过只读目录模拟写盘失败，无需实际磁盘不足）
- **人工介入**：无
- **测试结果**：待测试

### BIN-BND-002 multiReplayBinFile 无效/重复/不足输入
- **优先级**：P1
- **测试目的**：验证多文件回放的入参容错。
- **流程与逻辑**：`multiReplayBinFile([])`；`multiReplayBinFile([单文件])`；`multiReplayBinFile(含无效 bin 的列表)`；`multiReplayBinFile(含重复 device_mac 的列表)`。
- **预期结果**：空列表/单文件被拒绝（返回空列表或提示需 ≥2 个有效文件）；无效 bin 成员返回 None 不崩溃；重复 MAC 按 SDK 语义处理（跳过或拒绝），其余成员正常回放，不抛异常。
- **有效性说明**：多文件接口容错，防止无效成员拖垮整组。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

### BIN-ROB-002 multiReplayBinFile 组级 pause/resume/stop 幂等
- **优先级**：P1
- **测试目的**：验证多文件回放的组级控制与幂等。
- **流程与逻辑**：多文件回放中对任一成员调用 `pauseBinReplay`/`resumeBinReplay`，全组暂停/恢复；`stopBinReplay` 逐成员停止；停止后重复 `stopBinReplay`。
- **预期结果**：pause 任一成员整组暂停（组时钟语义）；resume 整组恢复；stop 对全部成员生效；重复控制不崩溃、不抛异常。
- **有效性说明**：多文件回放的组控制语义。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：已实现（默认关闭：同 BIN-FUNC-010，`multiReplayEnabled=false` 时记 SKIP）

### BIN-ROB-003 回放控制重复调用幂等
- **优先级**：P1
- **测试目的**：验证回放控制重复调用幂等。
- **流程与逻辑**：未回放时 `pauseBinReplay`/`resumeBinReplay`/`stopBinReplay`；回放中重复 `pause`/`resume`，停止后再次 `stopBinReplay`。
- **预期结果**：返回 OK 或被明确拒绝（状态不符时），不崩溃、不抛异常。
- **有效性说明**：控制接口幂等。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

### BIN-FUNC-012 bin 逐帧分析：各 DataType package counts 与 live 交叉验证
- **优先级**：P0
- **测试目的**：对录制的 bin 做原始帧逐类统计，定位"某 DataType 无数据"是固件未发送还是 SDK 未解析/未录制。
- **流程与逻辑**：`DEBUG_BLE_DATA_PATH=True` 起流录制 → 断开后用 [bin_rate_check.py](../../../../../Bin%20Analysis/bin_rate_check.py)（方法见 [BIN_ANALYSIS.md](../../../../../Bin%20Analysis/BIN_ANALYSIS.md)）解析 bin → 统计每类 DataType 的原始帧数（package counts）→ 与 live `onDataCallback` 实际收到的 DataType 集合做交叉验证。
- **预期结果**：`getDeviceInfo()` 中 `ChannelCount>0` 的模态，其对应 DataType 在 bin 中原始帧数 > 0；live 收到的 DataType 集合 ⊆ bin 记录的 DataType 集合（bin 为 BLE 包层全量，可包含 live 未交付的类型）；若某 `ChannelCount>0` 模态 bin 中帧数为 0，判定为"固件未发送"而非 SDK 解析问题。
- **有效性说明**：bin 是 BLE 包层地面真值，可区分"固件没发"与"SDK 没交付/没录制"，是 Impedance/IMU 等能力缺失问题的定位依据。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：已实现（Demo 自动化，待真机验证）

### BIN-FUNC-013 bin 回放 DataType 与 DeviceInfo ChannelCount>0 交叉验证
- **优先级**：P1
- **测试目的**：验证回放数据帧的 DataType 与设备能力声明一致。
- **流程与逻辑**：录制有效 bin；`replayBinFile` 回放；遍历 `onData` 帧，按 `getDataType()` 分桶收集出现的 DataType 集合；与 `getDeviceInfo()` 中 `ChannelCount>0` 的模态集合对比。
- **预期结果**：回放中出现的 DataType 集合 = `ChannelCount>0` 的模态集合；`ChannelCount>0` 的模态在回放中均能出现（录制时固件未发送的模态除外，见 BIN-FUNC-012）。
- **有效性说明**：回放数据与设备能力声明一致，防止串流/错流。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

### BIN-FUNC-014 FLUSH_BLE_DATA 起流中刷盘
- **优先级**：P1
- **测试目的**：验证起流中手动刷盘。
- **流程与逻辑**：`DEBUG_BLE_DATA_PATH=True` 起流，调用 `setParam("FLUSH_BLE_DATA","")`；重复调用；停流断开后检查日志目录是否生成新 bin 且可被 `getBinFileInfo` 解析。
- **预期结果**：`FLUSH_BLE_DATA` 返回 OK；重复调用幂等；停流断开后生成新 bin 且可解析。
- **有效性说明**：起流中数据及时落盘。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

### BIN-FUNC-015 parseBinToCsvAsync（suspend 桥）生成 CSV
- **优先级**：P1
- **测试目的**：验证异步解析导出（Android suspend 桥，与回调版 parseBinToCsv 同语义）。
- **流程与逻辑**：`parseBinToCsvAsync` 解析有效 bin，检查返回结果与 CSV 内容。
- **预期结果**：与 `parseBinToCsv` 同语义，返回 CSV 路径，文件存在且表头/行结构正确。
- **有效性说明**：Android 异步接口覆盖。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：已实现（Demo 自动化，待真机验证）

### BIN-BND-004 replayBinFile/parseBinToCsv/getBinFileInfo 空串/None/无效路径
- **优先级**：P1
- **测试目的**：验证空/None/无效文件路径入参。
- **流程与逻辑**：`getBinFileInfo("")`、`getBinFileInfo(None)`；`replayBinFile("")`/`replayBinFile(None)`/`replayBinFile(不存在路径)`；`parseBinToCsv("")`/`parseBinToCsv(None)`/`parseBinToCsv(非 bin 文件)`。
- **预期结果**：返回 None/False 或被拒绝，不崩溃、不抛异常。
- **有效性说明**：文件接口容错，防止无效路径崩溃。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

### BIN-ROB-004 回放批次推送算法
- **优先级**：P2
- **测试目的**：验证离线场景。
- **流程与逻辑**：用 `replayBinFile` 回放含 EEG/PPG 的 bin，把 `onData` 批次推入算法。
- **预期结果**：结果行与实时流结构一致；`STEP` 单调递增。
- **有效性说明**：算法不依赖实时链路特性。
- **可自动化**：auto（需预录 bin）
- **人工介入**：无
- **测试结果**：已实现（Demo 自动化，待真机验证）
