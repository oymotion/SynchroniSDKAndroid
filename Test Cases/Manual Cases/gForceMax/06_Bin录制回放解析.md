# gForceMax Bin 录制/回放/解析用例（BIN-*）

> **Android 口径**：`SensorController` 提供
> `getBinFileInfo(binPath): BinFileInfo?`、`parseBinToCsv(binPath, csvPath, onParam) `（回调式）、
> `replayBinFile(binPath, deviceMac, realtime, timeoutMs): SensorProfile?`（**立即返回**，数据经
> `setOnDataListener` 回调）、`pauseBinReplay(mac)`/`resumeBinReplay(mac)`/`stopBinReplay(mac)`
> （返回 `"OK"` 字符串）、`multiReplayBinFile(bins, profiles, realtime, timeoutMs): List<SensorProfile>?`。
> 与 Python 的差异：回放**不阻塞**、无独立子进程隔离；每例应使用**未连接/未起流的 MAC 键**，
> 用例间以 `SensorController.terminate()` + 重新 `getInstance()` 隔离状态。

### BIN-FUNC-001 连接后自动生成 .bin
- **优先级**：P1
- **测试目的**：验证 bin 自动录制。
- **流程与逻辑**：`controller.setParam("LOG_PATH", dir)` → 连接、init、`p.setParam("DEBUG_BLE_DATA_PATH","True")` → 起流 `collectSeconds` 秒 → 遍历 `dir` 下 `.bin` 文件。
- **预期结果**：目录中新增 `.bin` 文件；文件名含设备名/MAC/时间戳；文件大小随录制增长。
- **有效性说明**：Android bin 落盘目录由 `LOG_PATH` 控制（应用可写目录，如 `context.cacheDir/sdklog` 或 `Documents/sensorsdklog`）；默认目录见 `config.json` 的 `binPath` 示例。
- **可自动化**：auto
- **人工介入**：无。
- **测试结果**：待测试

### BIN-FUNC-002 getBinFileInfo 返回字段
- **优先级**：P1
- **测试目的**：验证 bin 信息。
- **流程与逻辑**：对有效 bin 调 `controller.getBinFileInfo(binPath)`。
- **预期结果**：返回非空 `BinFileInfo`；`valid==true`；核心字段可用：`mac`、`deviceName`、`durationSec`、`deviceInfo`（Android `BinFileInfo` 为结构化对象，**不是** Python 的 PascalCase dict）。
- **有效性说明**：bin 元数据完整；若固件/录制版本导致部分字段缺失，记录缺失项，但 `valid` 与 `deviceInfo` 必须有效。
- **可自动化**：auto
- **人工介入**：无。
- **测试结果**：待测试

### BIN-FUNC-003 getBinFileInfo 无效输入返回 null
- **优先级**：P1
- **测试目的**：验证逆向容错。
- **流程与逻辑**：`getBinFileInfo("")`、`getBinFileInfo("/sdcard/__no_such__.bin")`、对截断/无 config 的 bin 调用。
- **预期结果**：返回 `null`（或 `valid==false`），不崩溃、不抛未捕获异常。
- **有效性说明**：Android 返回 `BinFileInfo?`，`null` 表示无法解析；不得把空对象当有效。
- **可自动化**：auto
- **人工介入**：无。
- **测试结果**：待测试

### BIN-FUNC-004 回放 realtime=true 按原始节奏
- **优先级**：P1
- **测试目的**：验证实时回放。
- **流程与逻辑**：`replayBinFile(binPath, "REPLAY-GFM", true, 10000)`，注册 `setOnDataListener` 统计批数/样本数；记录从回放到收满数据的耗时，并与 bin 的 `durationSec` 比较。
- **预期结果**：返回非空 `SensorProfile`；回放产生数据，`getDataType()` 覆盖 live 录制中的流（EMG/IMU/GEST/IMPEDANCE）；`getStartTimeStamp()` 非空/有效；耗时与录制时长同量级。
- **有效性说明**：回放数据保真 + 节奏保真；精确批数可能因 bin 分组方式不同而差异，仅作参考。回放 MAC 键使用独立值（如 `"REPLAY-GFM"`），不复用已连接的 gForceMax MAC。
- **可自动化**：auto
- **人工介入**：无。
- **测试结果**：待测试

### BIN-FUNC-005 回放 realtime=false 全速
- **优先级**：P1
- **测试目的**：验证全速回放。
- **流程与逻辑**：`replayBinFile(binPath, "REPLAY-GFM-FAST", false, 10000)`，统计批数与耗时。
- **预期结果**：产生数据且 DataType 覆盖 live 类型；回放耗时显著小于录制时长（全速）；`startTimeStamp` 有效。
- **有效性说明**：回放数据保真 + 全速加速；`realtime` 为 `Boolean`（Kotlin），非 Python 关键字参数。
- **可自动化**：auto
- **人工介入**：无。
- **测试结果**：待测试

### BIN-FUNC-006 pause/resume/stop 回放
- **优先级**：P1
- **测试目的**：验证回放控制。
- **流程与逻辑**：回放中依次调 `controller.pauseBinReplay(mac)` → 观察 2s 内批数不增长 → `resumeBinReplay(mac)` → 观察批数恢复增长 → `stopBinReplay(mac)` → 观察回调停止。
- **预期结果**：三个接口均返回 `"OK"`；暂停期间无新增数据、恢复后继续、停止后终止。
- **有效性说明**：回放可控；`mac` 为 `replayBinFile` 时使用的独立键，不是物理设备 MAC。
- **可自动化**：auto
- **人工介入**：无。
- **测试结果**：待测试

### BIN-FUNC-007 实时起流中拒绝回放
- **优先级**：P1
- **测试目的**：验证互斥。
- **流程与逻辑**：目标设备正在实时起流时，用**同一 MAC** 调 `replayBinFile(bin, device.mac, true, 5000)`。
- **预期结果**：返回 `null`（拒绝回放）；实时流不受影响，`isDataTransfering()` 仍为 `true`。
- **有效性说明**：状态互斥。Android 用返回 `null` 表达拒绝，不抛异常也算合格；若返回非空 profile 且实时流被中断判 FAIL。
- **可自动化**：auto
- **人工介入**：无。
- **测试结果**：待测试

### BIN-FUNC-008 parseBinToCsv 生成 CSV
- **优先级**：P1
- **测试目的**：验证解析导出。
- **流程与逻辑**：`parseBinToCsv(binPath, csvPath, onParam)`（需包装为挂起等待回调），检查返回、`csvPath` 文件是否存在、首行表头与行类型。
- **预期结果**：回调返回成功（结果字符串非 `Error` 开头）；CSV 文件存在；首行为 `timestamp,mac,type,...`；每行 10 列；`type` ∈ {`raw`,`cmd_send`,`cmd_recv`,`event`,`parsed`}，无未知类型。五种类型不全出现属正常（取决于 bin 内容）。
- **有效性说明**：Android 为**双参 + 回调**接口（`binPath`,`csvPath`），与 Python 单参返回路径不同；CSV 输出路径需应用可写。
- **可自动化**：auto
- **人工介入**：无。
- **测试结果**：待测试

### BIN-FUNC-009 回放还原 startTimeStamp/delay 与实收一致
- **优先级**：P1
- **测试目的**：验证回放保真度。
- **流程与逻辑**：录制时记录 live 首批 `getStartTimeStamp()`/`getDelay()`；回放同一 bin 记录对应值，比对差值。
- **预期结果**：回放 `startTimeStamp`/`delay` 与 live 一致（差值 0）。
- **有效性说明**：已知问题 7101——`replayBinFile` 会重新生成 `startTimeStamp` 而非还原 bin 原值，属真 SDK 缺陷。Android 复现该问题应记录并挂 7101，不误判为用例/脚本问题；修复前本用例标记为受缺陷影响。
- **可自动化**：auto
- **人工介入**：无。
- **测试结果**：待测试

### BIN-FUNC-010 multiReplayBinFile 多文件同步回放
- **优先级**：P1
- **测试目的**：验证多 bin 文件按共享时钟对齐同步回放。
- **流程与逻辑**：准备 ≥2 个不同 MAC（或不同时段）的有效 bin；调 `controller.multiReplayBinFile(bins, emptyList(), true, 10000)`；为每个返回的 `SensorProfile` 注册 `setOnDataListener`，统计批数与 DataType。
- **预期结果**：返回列表长度等于输入文件数；各成员非空且产生数据；DataType 覆盖各自 live 类型；全组以最早首条数据为 t=0，保留录制时相对偏移。
- **有效性说明**：Android 签名为 `(bins, profiles, realtime, timeoutMs)`，第二参传空列表 `emptyList()` 由 SDK 创建回放 profile；成员为 `null` 表示该文件失败。
- **可自动化**：auto
- **人工介入**：无。
- **测试结果**：待测试

### BIN-BND-001 默认不导出：临时文件短暂存在后删除
- **优先级**：P1
- **测试目的**：验证默认（未设置 `DEBUG_BLE_DATA_PATH`）时 bin 不持久落盘，临时文件在断开后删除。
- **流程与逻辑**：不设置 `DEBUG_BLE_DATA_PATH`（或先置 `"False"`），连接起流；起流期间观察 `LOG_PATH` 目录是否出现临时 bin；`stopDataNotification`/`disconnect` 后确认目录无新增 `.bin`，且此前观察到的临时文件已消失。
- **预期结果**：日志目录不新增 `.bin`；临时文件（若可观测）在断开后消失。
- **有效性说明**：bin 导出默认关闭 + 临时文件生命周期；Android 临时文件可能位于应用 cache 目录，观察为 best-effort/informational。
- **可自动化**：auto（临时文件观察为 best-effort/informational）
- **人工介入**：无。
- **测试结果**：待测试

### BIN-ROB-001 磁盘不足时跳过/停止录制
- **优先级**：P1
- **测试目的**：验证写盘异常处理。
- **流程与逻辑**：用 `adb shell` 将 `LOG_PATH` 目录改为只读（或填充至剩余空间 <100MB），开启 `DEBUG_BLE_DATA_PATH` 起流 60s，观察异常与实时流。
- **预期结果**：不崩溃；实时流继续（数据回调不中断）；SDK 记录写盘失败或被静默跳过；恢复可写后新录制正常。
- **有效性说明**：Android 无 `icacls`，用 `adb shell chmod` / 存储填充模拟；应用私有目录在非 root 设备可能无法改权限，可用"存储剩余空间不足"方式替代，无法构造时记 SKIP。
- **可自动化**：auto（环境构造依赖 adb/root，无法构造则 SKIP）
- **人工介入**：无（必要时人工调整存储/权限）。
- **测试结果**：待测试

### BIN-BND-002 multiReplayBinFile 无效/重复/不足输入
- **优先级**：P1
- **测试目的**：验证多文件回放入参容错。
- **流程与逻辑**：`multiReplayBinFile(emptyList(), emptyList(), true, 3000)`；单文件列表；含不存在路径的列表；含重复 MAC/重复文件路径的列表。
- **预期结果**：空列表返回空结果或被拒绝；无效成员为 `null`/被跳过，其余成员正常；重复输入不崩溃、不抛未捕获异常；所有分支均应返回或明确失败，不得永久阻塞。
- **有效性说明**：多文件接口容错；Android 返回 `List<SensorProfile>?`，需判空。
- **可自动化**：auto
- **人工介入**：无。
- **测试结果**：待测试

### BIN-ROB-002 multiReplayBinFile 组级 pause/resume/stop 幂等
- **优先级**：P1
- **测试目的**：验证多文件回放组级控制与幂等。
- **流程与逻辑**：多文件回放中对任一成员 MAC 调 `pauseBinReplay` → 检查全组数据是否暂停；`resumeBinReplay` → 恢复；对每个成员 `stopBinReplay`；停止后重复 `stopBinReplay`。
- **预期结果**：pause/resume 对整个回放组生效（或按 SDK 文档仅该成员生效，需记录实际语义）；stop 对全部成员生效；重复控制返回 `"OK"` 或明确拒绝，不崩溃。
- **有效性说明**：多文件回放组控制语义；Android 成员以回放 MAC 键标识，组级语义以实测为准，偏差需记录而非臆断。
- **可自动化**：auto
- **人工介入**：无。
- **测试结果**：待测试

### BIN-ROB-003 回放控制重复调用幂等
- **优先级**：P1
- **测试目的**：验证单文件回放控制接口的幂等性。
- **流程与逻辑**：未回放时依次调 `pauseBinReplay("REPLAY-NONE")`/`resumeBinReplay`/`stopBinReplay`；回放中重复 `pauseBinReplay`（两次）、重复 `resumeBinReplay`（两次）；`stopBinReplay` 后再调一次。
- **预期结果**：各次调用返回 `"OK"` 或被明确拒绝（`Error...`），不崩溃、不抛未捕获异常；重复 pause/resume 不造成数据重复/丢失。
- **有效性说明**：单文件回放控制在重复调用/状态不符时的健壮性；Android 返回 `String`，判据为"不崩溃 + 返回可解释"。
- **可自动化**：auto
- **人工介入**：无。
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

### BIN-ROB-004 回放批次推入算法（离线算法一致性）
- **优先级**：P2
- **测试目的**：验证同一份 EEG 数据在 live 与离线回放两种路径下，算法结果一致。
- **流程与逻辑**：回放含 EEG 的 bin → 将 EEG 批次推入 `SensorAlgo` → 取结果 → 与 live 同批次结果比对。
- **预期结果**：离线回放结果与 live 一致（允许浮点误差）；STEP 序列单调；不崩溃。
- **有效性说明**：预录 bin 不含 EEG 时记 SKIP；该用例验证算法输入链路而非设备能力。
- **可自动化**：auto
- **人工介入**：无
- **测试结果**：已实现（Demo 自动化，待真机验证）
