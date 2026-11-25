# OB6000C Bin 录制/回放/解析用例（BIN-*）

> Android 接口：`getBinFileInfo(path): BinFileInfo?`、`replayBinFile(path, mac, realtime, timeoutMs): SensorProfile?`、`pauseBinReplay(path): String`、`resumeBinReplay(path): String`、`stopBinReplay(path): String`、`parseBinToCsv(binPath, csvPath, OnParamListener)`（另有 suspend 扩展 `parseBinToCsvAsync`）、`multiReplayBinFile(paths, sensors, realtime, timeoutMs)`。
> **重要**：回放必须使用 `replayBinFile` 返回的**新 `SensorProfile`**，不能复用已连接/已起流的 profile，否则不会泵数据。
> bin 目录以设备实际配置为准（常见 `/storage/emulated/0/Documents/sensorsdklog/`）；录制前需 `setParam("DEBUG_BLE_DATA_PATH","True")`。

### BIN-FUNC-001 连接后自动生成 .bin
- **优先级**：P1
- **测试目的**：验证 bin 录制落盘。
- **流程与逻辑**：连接 → `init` → `setParam("DEBUG_BLE_DATA_PATH","True")` → 起流 ≥5s → 停流；在 bin 目录查找新增文件（命名形如 `{deviceName}_{MAC}_{时间戳}.bin` 或 `multi_{时间戳}_{序号}.bin`）。
- **预期结果**：目录中新增 `.bin` 文件，大小 >0；`getBinFileInfo` 可解析。
- **有效性说明**：数据落盘；未开启 `DEBUG_BLE_DATA_PATH` 时默认不持久化（见 BIN-BND-001）。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

### BIN-FUNC-002 getBinFileInfo 返回字段
- **优先级**：P1
- **测试目的**：验证 bin 信息。
- **流程与逻辑**：对有效 bin 调 `getBinFileInfo(path)`。
- **预期结果**：返回非空 `BinFileInfo`，public final 字段可读且合理：`mac`（`78:1C:9D:E4:6C:6B`）、`deviceName` 非空、`chipType`、`isUniversalStream`、`featureMap`、`deviceInfo`（`eegChannelCount=32` 等）、`sensorDatas`、`durationSec>0`；新增字段 `configVersion`、`isNewEmg`、`isContainQat6`、`ppgModel`、`notifyDataFlag`、`valid==true`、`firstDataTsMs`、`lastDataTsMs`（且 `lastDataTsMs>=firstDataTsMs`）。
- **有效性说明**：bin 元数据完整；`BinFileInfo` 中 `deviceInfo` 为 `DeviceInfo` 对象，字段直读。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

### BIN-FUNC-003 getBinFileInfo 无效输入返回 null
- **优先级**：P1
- **测试目的**：验证逆向输入容错。
- **流程与逻辑**：对不存在路径、空文件、无 CONFIG 段的损坏 bin、空串路径分别调 `getBinFileInfo`。
- **预期结果**：均返回 `null`（或 `valid==false` 的对象，按实际记录），不崩溃、不抛未捕获异常。
- **有效性说明**：容错；Android 返回类型为可空 `BinFileInfo?`，无效输入应返回 `null`。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

### BIN-FUNC-004 回放 realtime=true 按原始节奏
- **优先级**：P1
- **测试目的**：验证实时回放。
- **流程与逻辑**：先 `getBinFileInfo(path)` 取 `mac`；调 `replayBinFile(path, mac, realtime=true, timeoutMs=10000)` 得到回放 profile；注册 `setOnDataListener`，统计批数、`getDataType()` 集合、首个 `getStartTimeStamp()`，并计时。
- **预期结果**：返回非空 profile；产生回放数据；DataType 覆盖录制时的类型（EEG 必有，IMU/IMPEDANCE 按 bin 内容）；`startTimeStamp` 可读；回放耗时与 `durationSec` 同量级。精确批数受解析分组影响，仅作参考。
- **有效性说明**：回放数据保真 + 节奏保真；**不得复用 live profile**。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

### BIN-FUNC-005 回放 realtime=false 全速
- **优先级**：P1
- **测试目的**：验证全速回放。
- **流程与逻辑**：`replayBinFile(path, mac, realtime=false, timeoutMs=10000)`，统计批数/DataType/首个时间戳，计时。
- **预期结果**：产生回放数据；DataType 覆盖录制类型；`startTimeStamp` 可读；回放耗时远小于 `durationSec`（全速）。精确批数仅作参考。
- **有效性说明**：回放数据保真 + 全速加速。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

### BIN-FUNC-006 pause/resume/stop 回放
- **优先级**：P1
- **测试目的**：验证回放控制。
- **流程与逻辑**：`realtime=true` 回放中依次调 `pauseBinReplay(path)` → 观察数据停止 → `resumeBinReplay(path)` → 观察恢复 → `stopBinReplay(path)` → 观察结束。
- **预期结果**：三次均返回 `"OK"`；暂停期间计数不增长、恢复后增长、停止后不再有数据；`stopBinReplay` 后回放线程结束。
- **有效性说明**：回放可控；返回值成功只认 `"OK"`。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

### BIN-FUNC-007 实时起流中拒绝回放
- **优先级**：P1
- **测试目的**：验证实时流与回放互斥。
- **流程与逻辑**：目标设备正在实时起流时，对同一 MAC 调 `replayBinFile(path, mac, true, 10000)`。
- **预期结果**：返回 `null` 或回放不产生数据（记录实际行为）；实时流不受影响、不崩溃。
- **有效性说明**：状态互斥；若返回非空但无数据，也判「回放未生效」为通过。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

### BIN-FUNC-008 parseBinToCsv 生成 CSV
- **优先级**：P1
- **测试目的**：验证解析导出。
- **流程与逻辑**：调 `parseBinToCsv(binPath, csvPath, OnParamListener)`（或 suspend `parseBinToCsvAsync`），解析完成后检查文件。
- **预期结果**：回调成功、CSV 文件存在且非空；首行为 `timestamp,mac,type,...` 表头；每行 10 列；`type` ∈ {`raw`/`cmd_send`/`cmd_recv`/`event`/`parsed`}，无未知类型。五种类型是否齐全取决于 bin 内容，**不全出现不判失败**。
- **有效性说明**：数据可分析。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

### BIN-FUNC-009 回放还原 startTimeStamp/delay 与实收一致
- **优先级**：P1
- **测试目的**：验证回放保真度。
- **流程与逻辑**：对比 live 与回放首个 EEG 批的 `getStartTimeStamp()` / `getDelay()`。
- **预期结果**：回放还原 bin 记录的原始 `startTimeStamp` 与 `delay`，与 live 一致（允许同一 bin 重复回放结果一致）。
- **有效性说明**：已知缺陷（bug 7101）：`replayBinFile` 会为回放重新生成 `startTimeStamp` 而非还原 bin 原值，导致回放与 live 不一致。属真 SDK 缺陷，用例保持 FAIL 可追溯，不改脚本绕过。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

### BIN-FUNC-010 multiReplayBinFile 多文件同步回放
- **优先级**：P1
- **测试目的**：验证多 bin 文件按共享时钟对齐同步回放。
- **流程与逻辑**：准备 ≥2 个不同 MAC 的有效 bin；调 `multiReplayBinFile(paths, sensors, realtime=true, timeoutMs=10000)`（`sensors` 与 `paths` 等长，按各 bin 的 `mac` 构造）；为每个返回 profile 注册 `setOnDataListener`，统计批数与 DataType。
- **预期结果**：返回列表长度等于输入文件数；每个成员返回对应 `SensorProfile`（失败成员可能为 `null`，记录原因）；各成员均产生回放数据、DataType 覆盖各自录制类型；全组以最早首条数据为 t=0，保留录制时相对偏移。
- **有效性说明**：多设备/多段 bin 的同步回放能力；`sensors` 为必填且与文件等长。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

### BIN-BND-001 默认不导出：临时文件短暂存在后删除
- **优先级**：P1
- **测试目的**：验证默认未开启调试落盘时 bin 不持久保存。
- **流程与逻辑**：不设置 `DEBUG_BLE_DATA_PATH`（保持默认 `False`），连接起流；起流期间记录 bin 目录快照；`stopDataNotification` / `disconnect` 后再次快照，确认无新增 `.bin`。
- **预期结果**：bin 目录不新增持久 `.bin`；若观察到临时文件，断开后应消失。
- **有效性说明**：bin 导出默认关闭 + 临时文件生命周期；临时文件观察为 best-effort（Android 应用沙箱目录可能不可见，仅记录）。
- **可自动化**：auto（临时文件观察为 informational）
- **人工介入**：无
- **测试结果**：待测试

### BIN-BND-002 multiReplayBinFile 无效/重复/不足输入
- **优先级**：P1
- **测试目的**：验证多文件回放入参容错。
- **流程与逻辑**：分别调用 `multiReplayBinFile(emptyList(), emptyList(), true)`、单文件列表、含无效 bin 的列表、含重复 MAC 的列表。
- **预期结果**：空列表/单文件被拒绝（返回空列表或抛受控异常）；无效 bin 成员返回 `null` 或缺失，不崩溃；重复 MAC 按 SDK 语义处理（其余成员正常回放或明确报错），不产生未捕获异常。
- **有效性说明**：多文件接口容错。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

### BIN-ROB-001 磁盘不足时跳过/停止录制
- **优先级**：P1
- **测试目的**：验证写盘异常处理。
- **流程与逻辑**：将设备可用空间压到 <100MB（或用 `adb shell` 限制应用目录写入）后连接起流；观察 bin 写入失败时的行为与实时数据流。
- **预期结果**：不崩溃；录制跳过/停止，实时流继续；错误可观测（logcat/`setOnErrorListener`）。
- **有效性说明**：资源边界健壮性；Android 无法像 Windows 用 `icacls` 设只读，改用填满存储或应用目录权限模拟，属 semi-auto。
- **可自动化**：semi-auto
- **人工介入**：人工制造低磁盘/只读目录环境并恢复。
- **测试结果**：待测试

### BIN-ROB-002 multiReplayBinFile 组级 pause/resume/stop 幂等
- **优先级**：P1
- **测试目的**：验证多文件回放组级控制与幂等。
- **流程与逻辑**：多文件回放中对任一成员调 `pauseBinReplay(path)` → 观察全组；`resumeBinReplay(path)` → 观察全组；对每个成员调 `stopBinReplay(path)`；停止后重复 `stopBinReplay(path)`。
- **预期结果**：pause 任一成员整组暂停；resume 整组恢复；stop 对全部成员生效；重复 stop 返回 `"OK"` 或受控错误，不崩溃、不抛异常。
- **有效性说明**：多文件回放组控制语义；若 Android 实现为「逐个文件独立控制」，记录实际语义并据此判定（不硬套组级）。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：待测试

---

## 补充用例（自动化对齐）

> 本节为与 `AndroidAutomationTests` 对齐后新增的用例；不改动上文既有条目。

### BIN-BND-004 回放/解析/文件信息接口空串或无效路径不崩溃
- **优先级**：P1
- **测试目的**：验证 bin 相关接口对无效输入返回错误而不是崩溃。
- **流程与逻辑**：对 `replayBinFile`、`parseBinToCsv`、`getBinFileInfo` 分别传入空串、不存在的路径、目录路径。
- **预期结果**：返回错误或空结果；不崩溃、不抛未捕获异常。
- **有效性说明**：用户选择文件/文件被删除时容易传入无效路径。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：已实现（Demo 自动化，待真机验证）

### BIN-FUNC-012 bin 逐帧 DataType 覆盖与 live 交叉验证
- **优先级**：P0
- **测试目的**：验证录制 bin 中的数据类型集合与实时采集一致，不漏流、不误报。
- **流程与逻辑**：录制含多流的 bin → `parseBinToCsv` → 统计每帧 DataType → 与 live 期间实际收到的 DataType 集合比对。
- **预期结果**：bin 内 DataType 覆盖 live 出现的流；无缺失、无误报；CSV 可完整解析。
- **有效性说明**：漏流会导致离线分析结论错误，是 bin 链路的核心一致性检查。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：已实现（Demo 自动化，待真机验证）

### BIN-FUNC-013 bin 回放 DataType 与 DeviceInfo 通道能力交叉验证
- **优先级**：P1
- **测试目的**：验证回放出现的流都在 `DeviceInfo` 支持范围内。
- **流程与逻辑**：`getBinFileInfo` → 回放 → 收集 DataType；与 `DeviceInfo` 中 `ChannelCount>0` 的流比对。
- **预期结果**：回放出现的流均在设备支持范围内；支持的流不出现能力之外的通道数。
- **有效性说明**：允许按录制配置缺席某些流，但不能出现设备根本不支持的流。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：已实现（Demo 自动化，待真机验证）

### BIN-FUNC-014 FLUSH_BLE_DATA 刷盘幂等且停流后 bin 可解析
- **优先级**：P1
- **测试目的**：验证起流中刷盘接口可用、可重复调用，且刷盘后 bin 文件完整可解析。
- **流程与逻辑**：起流 → `setParam("FLUSH_BLE_DATA","")` 两次 → 停流 → 对 bin 执行 `getBinFileInfo` / `parseBinToCsv`。
- **预期结果**：两次刷盘均返回 OK；bin 文件存在且可解析；不丢尾部数据、不崩溃。
- **有效性说明**：异常退出前刷盘是保证 bin 不损坏的关键路径。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：已实现（Demo 自动化，待真机验证）

### BIN-FUNC-015 parseBinToCsvAsync（suspend 桥）生成 CSV
- **优先级**：P1
- **测试目的**：验证 suspend 版 CSV 解析桥能正常完成并产出与同步接口一致的 CSV。
- **流程与逻辑**：对有效 bin 调用 `parseBinToCsvAsync` → 等待完成 → 读取 CSV 并与同步 `parseBinToCsv` 结果对比。
- **预期结果**：生成 CSV 文件，首行表头、数据行可解析；与同步接口结果一致；不崩溃。
- **有效性说明**：异步桥用于避免主线程阻塞，必须与同步语义一致。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：已实现（Demo 自动化，待真机验证）

### BIN-ROB-003 回放中断后 bin 文件仍可解析
- **优先级**：P2
- **测试目的**：验证回放过程中途停止/异常退出不会破坏源 bin 文件。
- **流程与逻辑**：开始回放 → 中途 `stopDataNotification`/取消 → 对源 bin 执行 `getBinFileInfo` 与 `parseBinToCsv`。
- **预期结果**：源 bin 仍可解析；无截断/损坏；不崩溃。
- **有效性说明**：回放中断是用户常见操作，不能损坏原始录制文件。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：已实现（Demo 自动化，待真机验证）

### BIN-ROB-004 回放批次推入算法（离线算法一致性）
- **优先级**：P2
- **测试目的**：验证同一份 EEG 数据在 live 与离线回放两种路径下，算法结果一致。
- **流程与逻辑**：回放含 EEG 的 bin → 将 EEG 批次推入 `SensorAlgo` → 取结果 → 与 live 同批次结果比对。
- **预期结果**：离线回放结果与 live 一致（允许浮点误差）；STEP 序列单调；不崩溃。
- **有效性说明**：预录 bin 不含 EEG 时记 SKIP；该用例验证算法输入链路而非设备能力。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：已实现（Demo 自动化，待真机验证）
