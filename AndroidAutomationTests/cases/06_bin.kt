package com.oymotion.sensorcapiktdemo.automation

import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import com.oymotion.sensorcapiktdemo.cerelaxpro.CerelaxSpec
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 录制时长（秒）：与 Instrumentation 侧一致，够 pause/resume/stop 回放控制留出窗口。 */
private const val RECORD_SECONDS = 8
private const val REPLAY_TIMEOUT_MS = 10_000

/** 多文件回放：启动对齐需要更长时间，首包可能晚于 10s。 */
private const val MULTI_REPLAY_TIMEOUT_MS = 30_000
private const val MULTI_REPLAY_DATA_TIMEOUT_SEC = 30L

// =====================================================================================
// 内部辅助（对齐 CerelaxBinTest.kt）
// =====================================================================================

/** 录制/日志落盘目录；bin 由 DEBUG_BLE_DATA_PATH 写入 LOG_PATH 指向的目录。 */
private fun TestContext.binLogDir(): File =
    File(androidContext.cacheDir, "automation_bin").apply { mkdirs() }

private fun findBins(dir: File): List<File> =
    dir.listFiles()?.filter { it.name.lowercase().endsWith(".bin") }?.sortedBy { it.lastModified() }
        ?: emptyList()

private fun TestContext.macOf(binPath: String): String =
    controller.getBinFileInfo(binPath)?.mac?.takeIf { it.isNotBlank() } ?: "REPLAY"

/** 会话日志落到受控目录，便于断言 .bin 是否生成（同 Instrumentation @Before）。 */
private suspend fun TestContext.prepareBinLogDir(): File {
    val dir = binLogDir()
    controller.setParam("LOG_PATH", dir.absolutePath)
    controller.setParam("DEBUG_ENABLED", "True")
    return dir
}

/** 扫描目标 + connect + init；扫描不到按源码 assumeTrue 语义记 SKIP。 */
private suspend fun TestContext.connectedOrSkip(): SensorProfile {
    val device = scanTarget() ?: skip("未扫描到设备")
    val profile = controller.requireSensor(device.mac) ?: skip("requireSensor 返回 null")
    return prepare(profile)
}

/** 连接设备并录制一个 bin，返回新生成的 bin 绝对路径（失败即断言失败）。 */
private suspend fun TestContext.recordBin(
    seconds: Int = RECORD_SECONDS,
    onBatch: ((List<SensorData>) -> Unit)? = null,
): String {
    val dir = prepareBinLogDir()
    val before = findBins(dir).map { it.absolutePath }.toSet()

    val p = connected()
    // 打开 spec 支持的全部流，让 bin 覆盖所有模态（供逐帧/DataType 交叉验证）
    val spec = TestConfig.targetSpec()
    for (key in CerelaxSpec.supportedStreamKeys(spec)) {
        runCatching { p.setParam(CerelaxSpec.paramKey(key), "ON") }
    }
    p.setParam("DEBUG_BLE_DATA_PATH", "True")
    p.setOnDataListener { _, list -> onBatch?.invoke(list) }
    check(p.startDataNotification(), "startDataNotification 失败")
    delay(seconds * 1000L)
    p.stopDataNotification()
    runCatching { p.disconnect() }

    val deadline = System.currentTimeMillis() + 5_000
    var newBin: File? = null
    while (System.currentTimeMillis() < deadline) {
        newBin = findBins(dir).lastOrNull { it.absolutePath !in before }
        if (newBin != null) break
        delay(200)
    }
    check(newBin != null, "连接起流后应生成新的 .bin（需设备开机在范围内）")
    return newBin!!.absolutePath
}

/** 回放一个 bin；必须用控制器新返回的 profile，不能复用已连接/已起流的 profile。 */
private suspend fun TestContext.startReplay(
    path: String,
    realtime: Boolean,
    timeoutMs: Int = REPLAY_TIMEOUT_MS,
): Pair<SensorProfile, String> {
    val mac = macOf(path)
    val profile = controller.replayBinFile(path, mac, realtime, timeoutMs)
        ?: throw AssertionError("replayBinFile 返回 null")
    trackReplay(mac)
    return profile to mac
}

/** 回放数据收集器：DataType 集合、批数、首包 startTimeStamp/delay、EOF 信号。 */
private class ReplayCollector {
    val types = ConcurrentHashMap.newKeySet<Int>()
    val batches = AtomicInteger(0)
    val firstStamp = AtomicInteger(-1)
    val firstDelay = AtomicInteger(-1)
    val dataLatch = CountDownLatch(1)
    val eofLatch = CountDownLatch(1)

    fun attach(p: SensorProfile) {
        p.setOnDataListener { _, dataList ->
            for (d in dataList) {
                types.add(d.dataType)
                batches.incrementAndGet()
                if (firstStamp.get() < 0) firstStamp.set(d.startTimeStamp)
                if (firstDelay.get() < 0) firstDelay.set(d.delay)
            }
            if (dataList.isNotEmpty()) dataLatch.countDown()
        }
        p.setOnDataTransferStateChangeListener { _, isTransferring ->
            if (!isTransferring) eofLatch.countDown()
        }
    }
}

/**
 * 多文件同步回放用的 bin：显式读 `config.json` 的 `multiBinPaths`（对齐 RN 的 MULTI_BIN_PATHS），
 * 不再扫描目录。要求 ≥2 个存在的 bin，且各自 MAC 互不相同。
 */
private fun TestContext.multiBins(): Pair<List<String>, List<String>> {
    val paths = TestConfig.MULTI_BIN_PATHS.map { it.trim() }.filter { it.isNotEmpty() }
    if (paths.size < 2) skip("需在 config.json 的 multiBinPaths 配置 ≥2 个 bin（当前 ${paths.size}）")
    val missing = paths.filterNot { File(it).isFile }
    check(missing.isEmpty(), "multiBinPaths 中 bin 不存在: $missing")
    val macs = paths.map { macOf(it) }
    check(macs.distinct().size == macs.size, "multiBinPaths 的 bin MAC 必须互不相同: $macs")
    return paths to macs
}

/**
 * 多文件同时回放开关。已知 Android 原生层在多 bin 并行回放时会杀进程，
 * 默认关闭；改外部 config.json 的 `multiReplayEnabled=true` 可复现/验证修复。
 */
private fun TestContext.requireMultiReplayEnabled() {
    if (!TestConfig.MULTI_REPLAY_ENABLED) {
        skip("multiReplay 默认关闭（已知多文件同时回放会杀进程）；改 config.json multiReplayEnabled=true 可验证")
    }
}

/**
 * 多文件回放需要未连接/未起流的 profile：先断掉这些 MAC 上已连接的设备并等状态回落，
 * 避免把连接态 profile 传给 multiReplayBinFile 触发原生崩溃。
 */
private suspend fun TestContext.freshReplaySensors(macs: List<String>): List<SensorProfile> {
    fun isTarget(mac: String) = macs.any { it.equals(mac, ignoreCase = true) }

    val stale = runCatching { controller.connectedSensors }.getOrDefault(emptyList())
        .filter { isTarget(it.device.mac) }
    stale.forEach { profile ->
        runCatching { profile.stopDataNotification() }
        runCatching { profile.disconnect() }
    }
    if (stale.isNotEmpty()) {
        log("multiReplay 前断开 ${stale.size} 个已连接 profile（${stale.map { it.device.mac }}）")
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val still = runCatching { controller.connectedSensors }.getOrDefault(emptyList())
                .count { isTarget(it.device.mac) }
            if (still == 0) break
            delay(200)
        }
    }
    return macs.map { mac -> controller.requireSensor(mac) ?: throw AssertionError("requireSensor($mac) 返回 null") }
}

// =====================================================================================
// BIN-FUNC-*：录制 / 回放 / 解析
// =====================================================================================

val binCases = run {
    val chapter = "06_Bin 录制与回放"
    listOf(
        // ---- BIN-FUNC -----------------------------------------------------------------
        auto("BIN-FUNC-001", "连接起流后自动生成 .bin", chapter, Priority.P0, 180_000) {
            val bin = recordBin()
            check(File(bin).exists(), "生成的 bin 文件应存在: $bin")
            "bin=${File(bin).name} bytes=${File(bin).length()}"
        },
        auto("BIN-FUNC-002", "getBinFileInfo 返回字段", chapter, Priority.P0, 120_000) {
            val path = binPathOrSkip()
            val info = controller.getBinFileInfo(path)
                ?: throw AssertionError("有效 bin 的 getBinFileInfo 应非 null")
            check(info.valid, "valid 应为 true")
            check(info.deviceName.isNotEmpty(), "deviceName 应非空")
            check(info.durationSec > 0, "durationSec 应 > 0")
            check(info.deviceInfo != null, "deviceInfo 应非 null")
            "mac=${info.mac} deviceName=${info.deviceName} durationSec=${info.durationSec}"
        },
        auto("BIN-FUNC-003", "getBinFileInfo 对不存在/空路径返回 null 或 valid=false",
            chapter, Priority.P1, 60_000) {
            check(
                controller.getBinFileInfo("/sdcard/nonexistent_cerelax.bin") == null,
                "不存在路径应返回 null",
            )
            val empty = controller.getBinFileInfo("")
            check(empty == null || !empty.valid, "空串应返回 null 或 valid=false")
            "invalid paths rejected"
        },
        auto("BIN-FUNC-004", "realtime=True 回放产生数据，startTimeStamp 有效",
            chapter, Priority.P0, 120_000) {
            val path = binPathOrSkip()
            val (p, mac) = startReplay(path, realtime = true)
            val c = ReplayCollector()
            c.attach(p)
            check(
                c.dataLatch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS),
                "realtime 回放应在 ${TestConfig.DATA_TIMEOUT_SEC}s 内产生数据",
            )
            delay(2_000)
            check(c.types.isNotEmpty(), "回放应产生非空 DataType 集合")
            // startTimeStamp 是 32 位无符号毫秒；>Int.MAX 时 Android 以负数暴露，不能判 >0
            check(c.firstStamp.get() != 0, "回放 startTimeStamp 应有效（非 0）")
            log("BIN-FUNC-004 stopBinReplay($mac) -> ${controller.stopBinReplay(mac)}")
            "types=${c.types} batches=${c.batches.get()} startTimeStamp=${c.firstStamp.get()}"
        },
        auto("BIN-FUNC-005", "realtime=False 全速回放，耗时应远小于录制时长",
            chapter, Priority.P1, 120_000) {
            val path = binPathOrSkip()
            val durationMs = ((controller.getBinFileInfo(path)?.durationSec ?: 0.0) * 1000)
                .toLong().takeIf { it > 0 } ?: (RECORD_SECONDS * 1000L)

            val (p, _) = startReplay(path, realtime = false)
            val c = ReplayCollector()
            c.attach(p)
            val t0 = System.currentTimeMillis()
            check(
                c.dataLatch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS),
                "全速回放应在 ${TestConfig.DATA_TIMEOUT_SEC}s 内产生数据",
            )
            val eofTimeoutMs = durationMs.coerceIn(10_000L, 120_000L)
            check(
                c.eofLatch.await(eofTimeoutMs, TimeUnit.MILLISECONDS),
                "全速回放应结束（EOF），等待 ${eofTimeoutMs}ms",
            )
            val elapsed = System.currentTimeMillis() - t0
            check(c.types.isNotEmpty(), "回放应产生非空 DataType 集合")
            // startTimeStamp 是 32 位无符号毫秒；>Int.MAX 时 Android 以负数暴露，不能判 >0
            check(c.firstStamp.get() != 0, "回放 startTimeStamp 应有效（非 0）")
            check(
                elapsed < durationMs,
                "全速回放耗时应远小于录制时长（$elapsed ms < $durationMs ms）",
            )
            "types=${c.types} batches=${c.batches.get()} elapsed=${elapsed}ms"
        },
        auto("BIN-FUNC-006", "pause/resume/stop 回放", chapter, Priority.P0, 180_000) {
            val path = binPathOrSkip()
            val (p, mac) = startReplay(path, realtime = true)
            val c = ReplayCollector()
            c.attach(p)
            check(
                c.dataLatch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS),
                "回放应在超时内产生数据",
            )

            check(controller.pauseBinReplay(mac) == "OK", "pauseBinReplay 应返回 OK")
            val pausedCount = c.batches.get()
            delay(2_000)
            val pausedAfter = c.batches.get()
            check(
                pausedAfter <= pausedCount + 20,
                "pause 后批数应停止增长（$pausedCount -> $pausedAfter）",
            )

            check(controller.resumeBinReplay(mac) == "OK", "resumeBinReplay 应返回 OK")
            val resumeCount = c.batches.get()
            delay(1_500)
            check(
                c.batches.get() > resumeCount,
                "resume 后批数应恢复增长（$resumeCount -> ${c.batches.get()}）",
            )

            check(controller.stopBinReplay(mac) == "OK", "stopBinReplay 应返回 OK")
            "paused=$pausedCount->$pausedAfter resumed=$resumeCount->${c.batches.get()}"
        },
        auto("BIN-FUNC-007", "实时起流中拒绝回放", chapter, Priority.P1, 120_000) {
            val path = binPathOrSkip()
            val p = connectedOrSkip()
            try {
                check(p.startDataNotification(), "startDataNotification 失败")
                val rejected: SensorProfile? = controller.replayBinFile(path, p.device.mac, true, 5_000)
                check(rejected == null, "实时起流中 replayBinFile 应被拒绝（返回 null）")
                "replay rejected while streaming"
            } finally {
                runCatching { p.stopDataNotification() }
                runCatching { p.disconnect() }
            }
        },
        auto("BIN-FUNC-008", "parseBinToCsv 生成 CSV（表头 10 列，type 列取值合法）",
            chapter, Priority.P0, 120_000) {
            val path = binPathOrSkip()
            val csv = File(
                androidContext.cacheDir,
                "automation_bin_${System.currentTimeMillis()}.csv",
            )
            csv.delete()
            val result = controller.parseBinToCsvAsync(path, csv.absolutePath)
            check(!result.startsWith("Error"), "parseBinToCsv 不应返回错误: $result")
            check(csv.isFile, "CSV 文件应生成: ${csv.absolutePath}")

            val lines = csv.readLines()
            val header = lines.firstOrNull() ?: throw AssertionError("CSV 应有表头")
            val cols = header.split(',')
            check(cols.size == 10, "CSV 应为 10 列（实际 ${cols.size}）")
            val typeIdx = cols.indexOfFirst { it.trim().equals("type", ignoreCase = true) }
            check(typeIdx >= 0, "表头应含 type 列")

            val allowed = setOf("raw", "cmd_send", "cmd_recv", "event", "parsed")
            val bad = lines.drop(1).mapNotNull { line ->
                val parts = line.split(',')
                if (parts.size > typeIdx) parts[typeIdx].trim() else ""
            }.filter { it.isNotEmpty() && it !in allowed }.take(3)
            check(bad.isEmpty(), "CSV 出现未知 type: $bad")
            "csv=${csv.absolutePath} lines=${lines.size} result=$result"
        },
        auto("BIN-FUNC-012", "bin 逐帧 DataType 覆盖与 live 交叉验证",
            chapter, Priority.P0, 300_000) {
            val spec = TestConfig.targetSpec()
            val liveTypes = ConcurrentHashMap.newKeySet<Int>()
            val path = recordBin { list -> list.forEach { liveTypes.add(it.dataType) } }

            val info = controller.getBinFileInfo(path)
                ?: throw AssertionError("录制 bin 应可被解析: $path")
            check(info.valid, "录制 bin 的 valid 应为 true")
            val binTypes = info.sensorDatas.map { it.dataType }.toSet()
            check(binTypes.isNotEmpty(), "bin 应记录至少一种 DataType")

            // live 是 bin 的子集（bin 为 BLE 包层全量，可包含 live 未交付的类型）
            val missingInBin = liveTypes.filter { it !in binTypes }
            check(missingInBin.isEmpty(), "live 收到的 DataType 应都记录在 bin 中: $missingInBin")

            // ChannelCount>0 但 bin 无记录的模态：录制时未开启或固件未发送，记录但不判失败
            val deviceInfo = info.deviceInfo
            val expectedTypes = if (deviceInfo == null) emptySet() else
                CerelaxSpec.supportedStreamKeys(spec).mapNotNull { key ->
                    val t = CerelaxSpec.KEY_TO_DATA_TYPE[key] ?: return@mapNotNull null
                    if (CerelaxSpec.channelCount(deviceInfo, key) > 0) t else null
                }.toSet()
            val notRecorded = expectedTypes.filter { it !in binTypes }

            // 逐帧：CSV 中 raw 帧数 > 0，证明 bin 含 BLE 原始包
            val csv = File(androidContext.cacheDir, "bin012_${System.currentTimeMillis()}.csv")
            val parsed = controller.parseBinToCsvAsync(path, csv.absolutePath)
            check(!parsed.startsWith("Error"), "parseBinToCsv 不应报错: $parsed")
            val lines = csv.readLines()
            val cols = lines.firstOrNull()?.split(',') ?: throw AssertionError("CSV 应有表头")
            val typeIdx = cols.indexOfFirst { it.trim().equals("type", ignoreCase = true) }
            check(typeIdx >= 0, "CSV 表头应含 type 列")
            val rawFrames = lines.drop(1).count { it.split(',').getOrNull(typeIdx)?.trim() == "raw" }
            check(rawFrames > 0, "bin 应包含 raw（BLE 包层）帧，实际=0")

            "binTypes=$binTypes live=$liveTypes rawFrames=$rawFrames notRecorded=$notRecorded"
        },
        auto("BIN-FUNC-015", "parseBinToCsvAsync（suspend 桥）生成 CSV",
            chapter, Priority.P1, 120_000) {
            val path = binPathOrSkip()
            val csv = File(androidContext.cacheDir, "bin015_${System.currentTimeMillis()}.csv")
            csv.delete()
            val result = controller.parseBinToCsvAsync(path, csv.absolutePath)
            check(!result.startsWith("Error"), "parseBinToCsvAsync 不应报错: $result")
            check(csv.isFile, "CSV 应生成: ${csv.absolutePath}")

            val lines = csv.readLines().filter { it.isNotBlank() }
            check(lines.size > 1, "CSV 除表头外应有数据行，实际=${lines.size}")
            val header = lines.first().split(',')
            check(header.size == 10, "CSV 表头应为 10 列，实际=${header.size}")
            check(
                lines.drop(1).all { it.split(',').size >= 2 },
                "CSV 每行应至少含 timestamp/type 两列",
            )
            val info = controller.getBinFileInfo(path)
                ?: throw AssertionError("getBinFileInfo 应返回有效结果: $path")
            check(info.valid, "对照 bin 的 valid 应为 true")
            "result=$result lines=${lines.size} duration=${info.durationSec}s"
        },
        auto("BIN-FUNC-009", "回放 startTimeStamp/delay 非空且 delay 非负（已知 bug 7101 不还原原值）",
            chapter, Priority.P1, 120_000) {
            val path = binPathOrSkip()
            val (p, _) = startReplay(path, realtime = false)
            val c = ReplayCollector()
            c.attach(p)
            check(
                c.dataLatch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS),
                "回放应在超时内产生数据",
            )
            // startTimeStamp 是 32 位无符号毫秒；>Int.MAX 时 Android 以负数暴露，不能判 >0
            check(c.firstStamp.get() != 0, "回放 startTimeStamp 应有效（非 0）")
            check(c.firstDelay.get() >= 0, "回放 delay 应 >= 0")
            "startTimeStamp=${c.firstStamp.get()} delay=${c.firstDelay.get()} " +
                "（已知 bug 7101：回放会重新生成 startTimeStamp，非还原 bin 原值）"
        },
        auto("BIN-FUNC-013", "bin 回放 DataType 与 DeviceInfo ChannelCount>0 交叉验证",
            chapter, Priority.P1, 120_000) {
            // 现场录制目标设备自己的 bin：保证回放数据与 DeviceInfo/spec 同源，
            // 避免复用 config.binPath 的跨型号预录 bin（如 gForceMax 目标回放 Cerelax-Pro bin）。
            val path = recordBin()
            val info = controller.getBinFileInfo(path)
            if (info == null || !info.valid || info.deviceInfo == null) {
                throw AssertionError("新录制的 bin 应可被解析: $path")
            }
            val deviceInfo = info.deviceInfo!!
            val spec = TestConfig.targetSpec()
            // 能力来源只认 spec：bin CONFIG 的 deviceInfo 不保证记录全部流
            //（实测 gForceMax 的 bin 里 gestChannelCount=0，但回放确实产出 NTF_GEST）。
            val allowed = CerelaxSpec.supportedStreamKeys(spec)
                .mapNotNull { CerelaxSpec.KEY_TO_DATA_TYPE[it] }
                .toMutableSet()
            if (DataType.NTF_IMU in allowed) {
                allowed += listOf(
                    DataType.NTF_ACC, DataType.NTF_GYRO,
                    DataType.NTF_EULER, DataType.NTF_QUATERNION,
                )
            }
            if (DataType.NTF_ACC in allowed || DataType.NTF_GYRO in allowed) {
                allowed += DataType.NTF_IMU
            }
            if (allowed.isEmpty()) skip("目标 spec 应声明至少一种支持的模态")
            // 仅用于日志：bin deviceInfo 自己声明了通道的模态（可能少于 spec）
            val declaredTypes = CerelaxSpec.supportedStreamKeys(spec).mapNotNull { key ->
                val type = CerelaxSpec.KEY_TO_DATA_TYPE[key] ?: return@mapNotNull null
                if (CerelaxSpec.channelCount(deviceInfo, key) > 0) type else null
            }

            val (p, _) = startReplay(path, realtime = false)
            val c = ReplayCollector()
            c.attach(p)
            check(
                c.dataLatch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS),
                "回放应在超时内产生数据",
            )
            check(
                c.eofLatch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS),
                "全速回放应结束（EOF）",
            )

            // 设备能力 ≠ 录制时实际开启的流：只要求“回放不出现能力之外的模态”，缺失仅记录
            val unexpected = c.types.filter { it !in allowed }
            check(unexpected.isEmpty(), "回放出现能力声明之外的模态: $unexpected")
            check(c.types.isNotEmpty(), "回放应产生数据")
            val missing = declaredTypes.filter { it !in c.types }
            "allowed=$allowed got=${c.types} deviceInfoDeclared=$declaredTypes missing(录制可能未开)=$missing"
        },
        // 多设备配对录制：对齐 RN 的 MULTI-FUNC-003-REC，产出 ≥2 个不同 MAC 的 bin，
        // 把打印出的路径回填 config.json 的 multiBinPaths 后即可跑 BIN-FUNC-010。
        auto("BIN-FUNC-010-REC", "多设备配对录制 bin（供多文件回放使用）", chapter, Priority.P1,
            300_000) {
            // LOG_PATH 必须在设备 connect/init 之前指向录制目录，否则 bin 落到用例诊断目录，findBins 查不到。
            val dir = prepareBinLogDir()
            val ready = ArrayList<SensorProfile>()
            for (p in runCatching { requireKnownDevices() }.getOrDefault(emptyList())) {
                if (ready.size == 2) break
                if (runCatching { prepare(p) }.isSuccess) {
                    ready += p
                } else {
                    log("设备 ${p.device.mac} 连接/init 未就绪，跳过")
                }
            }
            if (ready.size < 2) skip("需要 ≥2 台可连接的就绪设备（当前 ${ready.size} 台）")

            val before = findBins(dir).map { it.absolutePath }.toSet()
            try {
                val (started, errors) = controller.multiStartDataNotification(ready, 30_000, 5, 3)
                check(started.values.all { it }, "多设备起流失败: $errors")
                log("配对录制 ${RECORD_SECONDS}s -> ${dir.absolutePath}")
                delay(RECORD_SECONDS * 1000L)
            } finally {
                runCatching { controller.multiStopDataNotification(ready, 10_000) }
                ready.forEach { runCatching { it.disconnect() } }
                runCatching { controller.setParam("DEBUG_ENABLED", "False") }
            }

            val infos = findBins(dir)
                .filter { it.absolutePath !in before }
                .mapNotNull { f ->
                    controller.getBinFileInfo(f.absolutePath)?.mac
                        ?.takeIf { it.isNotBlank() }
                        ?.let { f.absolutePath to it }
                }
                .distinctBy { it.second }
            check(infos.size >= 2, "配对录制应生成 ≥2 个不同 MAC 的 bin（实际=${infos.map { it.second }}）")
            val paths = infos.take(2).map { it.first }
            "paths=$paths（回填 config.json 的 multiBinPaths 后运行 BIN-FUNC-010）"
        },
        auto("BIN-FUNC-010", "multiReplayBinFile 多文件同步回放", chapter, Priority.P1, 180_000) {
            requireMultiReplayEnabled()
            val (bins, macs) = multiBins()
            // README：paths 与 sensors 必须等长；回放须用未连接 profile
            val sensors = freshReplaySensors(macs)
            macs.forEach { trackReplay(it) }
            val profiles = controller.multiReplayBinFile(
                bins, sensors, true, MULTI_REPLAY_TIMEOUT_MS,
            )
            check(
                profiles.size == bins.size,
                "返回列表长度应等于输入文件数（${profiles.size} != ${bins.size}）",
            )
            val collectors = ArrayList<ReplayCollector>()
            profiles.forEachIndexed { i, member ->
                check(member != null, "成员 $i 启动失败（mac=${macs[i]}）")
                val c = ReplayCollector()
                c.attach(member!!)
                collectors += c
                check(
                    c.dataLatch.await(MULTI_REPLAY_DATA_TIMEOUT_SEC, TimeUnit.SECONDS),
                    "成员 $i 应在 ${MULTI_REPLAY_DATA_TIMEOUT_SEC}s 内产生回放数据",
                )
                check(c.types.isNotEmpty(), "成员 $i DataType 集合应非空")
            }
            val beforeStop = collectors.map { it.batches.get() }
            macs.forEach { mac ->
                val r = controller.stopBinReplay(mac)
                log("stopBinReplay($mac) -> $r")
                check(!r.startsWith("Error"), "stopBinReplay($mac) 应成功，实际=$r")
            }
            delay(3_000)
            collectors.forEachIndexed { i, c ->
                check(
                    c.batches.get() == beforeStop[i],
                    "停止后成员 $i 数据不应继续增长（${beforeStop[i]} -> ${c.batches.get()}）",
                )
            }
            "members=${bins.size} macs=$macs"
        },

        // ---- BIN-BND ------------------------------------------------------------------
        auto("BIN-BND-001", "默认（不设 DEBUG_BLE_DATA_PATH）时日志目录不新增 .bin",
            chapter, Priority.P1, 180_000) {
            val dir = prepareBinLogDir()
            val p = connectedOrSkip()
            // 同一 profile 可能被前面 BIN-FUNC 用例设过 DEBUG_BLE_DATA_PATH=True（参数在进程内保持），
            // 先显式复位为 False，才能验证"默认不导出"的语义。
            p.setParam("DEBUG_BLE_DATA_PATH", "False")
            delay(300)
            val before = findBins(dir).map { it.absolutePath }.toSet()

            check(p.startDataNotification(), "startDataNotification 失败")
            delay(TestConfig.COLLECT_SECONDS * 1000L)
            p.stopDataNotification()
            p.disconnect()
            delay(1_000)

            val added = findBins(dir).filter { it.absolutePath !in before }
            check(
                added.isEmpty(),
                "默认不设 DEBUG_BLE_DATA_PATH 时日志目录不应新增 .bin（实际=${added.map { it.name }}）",
            )
            "no new bin"
        },
        auto("BIN-BND-004", "replayBinFile/parseBinToCsv/getBinFileInfo 空串/无效路径不崩溃",
            chapter, Priority.P1, 60_000) {
            check(controller.getBinFileInfo("") == null, "空串 getBinFileInfo 应返回 null")
            check(
                controller.getBinFileInfo("/sdcard/__no_such__.bin") == null,
                "不存在路径 getBinFileInfo 应返回 null",
            )

            // README 未约定空/无效路径的返回形态：null、抛异常、或返回 profile 都可接受；
            // 有预录 bin 时用合法 MAC，避免与“非法 MAC”因素混在一起。
            val validMac = TestConfig.BIN_PATH
                .takeIf { it.isNotBlank() && File(it).isFile }
                ?.let { macOf(it) } ?: "REPLAY"
            val rp1: SensorProfile? = runCatching {
                controller.replayBinFile("", validMac, true, 3_000)
            }.getOrElse {
                log("BIN-BND-004 replayBinFile('') 抛出 ${it.javaClass.simpleName}（可接受）")
                null
            }
            if (rp1 != null) runCatching { controller.stopBinReplay(validMac) }
            log("BIN-BND-004 replayBinFile('') -> ${if (rp1 == null) "null/异常" else "返回 profile"}")

            val rp2: SensorProfile? = runCatching {
                controller.replayBinFile("/sdcard/__no_such__.bin", validMac, true, 3_000)
            }.getOrElse {
                log("BIN-BND-004 不存在路径抛出 ${it.javaClass.simpleName}（可接受）")
                null
            }
            if (rp2 != null) runCatching { controller.stopBinReplay(validMac) }
            log("BIN-BND-004 replayBinFile(不存在路径) -> ${if (rp2 == null) "null/异常" else "返回 profile"}")

            val cache = androidContext.cacheDir.absolutePath
            val r3 = runCatching {
                controller.parseBinToCsvAsync("", File(cache, "nope.csv").absolutePath)
            }.getOrElse { "Error: ${it.javaClass.simpleName}" }
            log("BIN-BND-004 parseBinToCsv('') -> $r3")
            val r4 = runCatching {
                controller.parseBinToCsvAsync(
                    "/sdcard/__no_such__.bin",
                    File(cache, "nope2.csv").absolutePath,
                )
            }.getOrElse { "Error: ${it.javaClass.simpleName}" }
            log("BIN-BND-004 parseBinToCsv(不存在) -> $r4")
            check(r3.isNotEmpty() && r4.isNotEmpty(), "无效路径 parseBinToCsv 应有可判定结果")
            "invalid paths no crash (parse='$r3' / '$r4')"
        },
        auto("BIN-BND-002", "multiReplayBinFile 空列表/单文件/无效/重复 MAC 不崩溃",
            chapter, Priority.P1, 120_000) {
            // 当前 SDK 的 nativeMultiReplayBinFile 对任何调用都可能触发 JNI 崩溃
            // （jstring invalid local reference -> SIGABRT），空列表/单文件也不例外。
            // 必须在最前面统一门禁，否则会直接杀进程导致整轮无报告。
            requireMultiReplayEnabled()
            val empty = controller.multiReplayBinFile(
                emptyList<String>(), emptyList<SensorProfile>(), true, 3_000,
            )
            check(empty.isEmpty(), "空列表应返回空列表")

            val bin = TestConfig.BIN_PATH.takeIf { it.isNotBlank() && File(it).isFile }
            if (bin == null) {
                return@auto "multiReplay 空列表 OK（未配置预录 bin，跳过错位/无效/重复分支）"
            }

            // README：paths 与 sensors 必须等长
            val mac = macOf(bin)
            val sensor = controller.requireSensor(mac)
                ?: throw AssertionError("requireSensor($mac) 返回 null")

            val fake = "/sdcard/__no_such_dir__/not_exist.bin"
            val invalid = controller.multiReplayBinFile(
                listOf(fake), listOf(sensor), true, 3_000,
            )
            check(invalid.size == 1, "返回列表长度应对齐输入")
            check(invalid[0] == null, "无效路径成员应为 null")

            trackReplay(mac)
            val single = controller.multiReplayBinFile(
                listOf(bin), listOf(sensor), true, 3_000,
            )
            check(single.size == 1, "单文件不应崩溃")
            repeat(2) { controller.stopBinReplay(mac) }

            val dup = controller.multiReplayBinFile(
                listOf(bin, bin), listOf(sensor, sensor), true, 3_000,
            )
            check(dup.size == 2, "重复 MAC 返回列表应对齐输入")
            repeat(2) { controller.stopBinReplay(mac) }
            "multi invalid inputs OK (mac=$mac)"
        },
        auto("MULTI-BND-003", "multiReplayBinFile paths/sensors 长度不匹配/空串被拒绝",
            chapter, Priority.P1, 120_000) {
            requireMultiReplayEnabled()
            val bin = TestConfig.BIN_PATH.takeIf { it.isNotBlank() && File(it).isFile }
                ?: skip("未配置预录 bin（TestConfig.BIN_PATH）")
            val mac = macOf(bin)
            val sensor = freshReplaySensors(listOf(mac)).first()

            // 1) 长度不匹配：2 paths + 1 sensor，应被拒绝（抛异常或返回无可用成员的列表）
            val mismatch = runCatching {
                controller.multiReplayBinFile(listOf(bin, bin), listOf(sensor), true, 3_000)
            }
            val mismatchDetail = if (mismatch.isFailure) {
                "抛 ${mismatch.exceptionOrNull()?.javaClass?.simpleName}"
            } else {
                val list = mismatch.getOrNull().orEmpty()
                val accepted = list.filterNotNull()
                runCatching { controller.stopBinReplay(mac) }
                check(
                    accepted.isEmpty(),
                    "paths/sensors 长度不匹配应被拒绝，实际返回 ${list.size} 项且含可用 profile",
                )
                "返回 ${list.size} 项（无可用 profile）"
            }

            // 2) paths 含空串：对应用户成员应为 null（被拒绝），不得崩溃
            trackReplay(mac)
            val withEmpty = runCatching {
                controller.multiReplayBinFile(listOf("", bin), listOf(sensor, sensor), true, 3_000)
            }.getOrElse {
                throw AssertionError("paths 含空串不应抛未捕获异常: ${it.javaClass.simpleName}")
            }
            check(withEmpty.size == 2, "返回列表长度应对齐输入（实际=${withEmpty.size}）")
            check(withEmpty[0] == null, "paths 含空串时该成员应为 null（被拒绝）")
            runCatching { controller.stopBinReplay(mac) }
            "mismatch -> $mismatchDetail; emptyPath -> null"
        },

        // ---- BIN-ROB ------------------------------------------------------------------
        manual("BIN-ROB-001", "磁盘不足跳过录制", chapter, Priority.P2,
            steps = listOf(
                "Android 不适用：Android 无法可靠模拟磁盘不足/只读（icacls 不适用于 app 私有目录），" +
                    "需真机手工验证（Instrumentation 侧 @Ignore）。",
                "人工步骤：把日志目录所在分区压到无剩余空间 → 起流 → 确认录制被跳过且不崩溃。",
            )),
        auto("BIN-ROB-003", "回放控制重复调用幂等", chapter, Priority.P2, 120_000) {
            val path = binPathOrSkip()
            val mac = macOf(path)

            // 合法 MAC 但未起播：返回值可能是 OK 或 Error，只要求不抛异常、有返回值
            listOf(
                "pause" to controller.pauseBinReplay(mac),
                "resume" to controller.resumeBinReplay(mac),
                "stop" to controller.stopBinReplay(mac),
            ).forEach { (op, r) ->
                log("BIN-ROB-003 未回放 $op -> $r")
                check(r.isNotEmpty(), "未回放 $op 应返回可判定的结果")
            }

            val (p, replayMac) = startReplay(path, realtime = true)
            val c = ReplayCollector()
            c.attach(p)
            check(
                c.dataLatch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS),
                "回放应在超时内产生数据",
            )
            val pause1 = controller.pauseBinReplay(replayMac)
            val pause2 = controller.pauseBinReplay(replayMac)
            val resume1 = controller.resumeBinReplay(replayMac)
            val resume2 = controller.resumeBinReplay(replayMac)
            val stop1 = controller.stopBinReplay(replayMac)
            val stop2 = controller.stopBinReplay(replayMac)
            log("BIN-ROB-003 pause1=$pause1 pause2=$pause2 resume1=$resume1 resume2=$resume2 stop1=$stop1 stop2=$stop2")
            check(pause1 == "OK", "首次 pause 应返回 OK，实际=$pause1")
            check(resume1 == "OK", "首次 resume 应返回 OK，实际=$resume1")
            check(stop1 == "OK", "首次 stop 应返回 OK，实际=$stop1")
            check(
                pause2.isNotEmpty() && resume2.isNotEmpty() && stop2.isNotEmpty(),
                "重复控制调用应有可判定结果",
            )
            "control idempotent mac=$replayMac"
        },
        auto("BIN-ROB-004", "回放批次推入算法（离线算法一致性）",
            chapter, Priority.P2, 240_000) {
            val path = binPathOrSkip()
            val info = controller.getBinFileInfo(path)
                ?: throw AssertionError("bin 应可解析: $path")
            val eegConfig = info.sensorDatas.firstOrNull { it.dataType == DataType.NTF_EEG }
                ?: skip("预录 bin 不含 EEG，无法验证放松度算法")

            val (p, mac) = startReplay(path, realtime = true)
            val queue = ConcurrentLinkedQueue<SensorData>()
            val eof = CountDownLatch(1)
            p.setOnDataListener { _, list ->
                list.filter { it.dataType == DataType.NTF_EEG }.forEach { queue.offer(it.clone()) }
            }
            p.setOnDataTransferStateChangeListener { _, transferring ->
                if (!transferring) eof.countDown()
            }

            val algo = SensorAlgo()
            check(algo.safeInit(), "SensorAlgo.init 返回 false")
            try {
                if (eegConfig.sampleRate > 0) {
                    algo.safeSetParam("EEG_SAMPLE_RATE", eegConfig.sampleRate.toInt().toString())
                }
                val steps = mutableListOf<Int>()
                var pushed = 0
                val startedAt = System.currentTimeMillis()
                while (System.currentTimeMillis() - startedAt < 30_000 && eof.count > 0) {
                    val batch = ArrayList<SensorData>()
                    while (true) {
                        batch += queue.poll() ?: break
                    }
                    if (batch.isNotEmpty() && algo.safePush(batch.toTypedArray())) pushed++
                    // 只取本次返回的最后一行，避免累计输出导致 STEP 序列回退
                    val lastStep = algoRows(algo.safeResult("EEG")).lastOrNull()
                        ?.firstOrNull()?.toIntOrNull()
                    if (lastStep != null && steps.lastOrNull() != lastStep) steps += lastStep
                    delay(100)
                }

                check(pushed > 0, "回放 EEG 批次应可推入算法")
                check(steps.isNotEmpty(), "离线算法应产出结果行")
                check(
                    steps.zipWithNext().all { (a, b) -> b >= a },
                    "回放算法 STEP 应单调递增: $steps",
                )
                "pushed=$pushed steps=${steps.size} ${steps.firstOrNull()}..${steps.lastOrNull()}"
            } finally {
                runCatching { algo.destroy() }
                runCatching { controller.stopBinReplay(mac) }
            }
        },
        auto("BIN-ROB-002", "multiReplayBinFile 组级 pause/resume/stop 幂等",
            chapter, Priority.P2, 180_000) {
            requireMultiReplayEnabled()
            val (bins, macs) = multiBins()
            // README：paths 与 sensors 必须等长；回放须用未连接 profile
            val sensors = freshReplaySensors(macs)
            val profiles = controller.multiReplayBinFile(
                bins, sensors, true, MULTI_REPLAY_TIMEOUT_MS,
            )
            val started = profiles.filterNotNull()
            if (started.isEmpty()) skip("组回放应至少启动一个成员")
            macs.forEach { trackReplay(it) }

            val collector = ReplayCollector()
            collector.attach(started.first())
            check(
                collector.dataLatch.await(MULTI_REPLAY_DATA_TIMEOUT_SEC, TimeUnit.SECONDS),
                "组回放应在 ${MULTI_REPLAY_DATA_TIMEOUT_SEC}s 内产生数据",
            )

            val groupMac = macs.first()
            log("BIN-ROB-002 组 pause1 -> ${controller.pauseBinReplay(groupMac)}")
            log("BIN-ROB-002 组 pause2 -> ${controller.pauseBinReplay(groupMac)}")
            log("BIN-ROB-002 组 resume1 -> ${controller.resumeBinReplay(groupMac)}")
            log("BIN-ROB-002 组 resume2 -> ${controller.resumeBinReplay(groupMac)}")
            macs.forEach { mac ->
                val r = controller.stopBinReplay(mac)
                log("BIN-ROB-002 组 stop($mac) -> $r")
                check(!r.startsWith("Error"), "组 stop($mac) 应成功，实际=$r")
            }
            macs.forEach { mac ->
                val r = controller.stopBinReplay(mac)
                log("BIN-ROB-002 组 stop 幂等($mac) -> $r")
                check(!r.startsWith("Error"), "重复 stop($mac) 应成功，实际=$r")
            }
            "group control idempotent macs=$macs"
        },

        // ---- BIN-FUNC-014（源码排在最后） ---------------------------------------------
        auto("BIN-FUNC-014",
            "FLUSH_BLE_DATA 起流中刷盘返回 OK、重复调用幂等；停流后 bin 正常落盘且可解析",
            chapter, Priority.P1, 120_000) {
            val dir = prepareBinLogDir()
            val before = findBins(dir).map { it.absolutePath }.toSet()

            val p = connected()
            p.setParam("DEBUG_BLE_DATA_PATH", "True")
            check(p.startDataNotification(), "startDataNotification 失败")
            delay(3_000)

            val r1 = p.setParam("FLUSH_BLE_DATA", "")
            log("FLUSH_BLE_DATA -> $r1")
            check(!r1.startsWith("Error"), "FLUSH_BLE_DATA 应返回 OK，实际=$r1")
            val r2 = p.setParam("FLUSH_BLE_DATA", "")
            log("FLUSH_BLE_DATA 重复调用 -> $r2")
            check(!r2.startsWith("Error"), "FLUSH_BLE_DATA 重复调用应幂等返回 OK，实际=$r2")

            p.stopDataNotification()
            runCatching { p.disconnect() }

            val deadline = System.currentTimeMillis() + 5_000
            var newBin: File? = null
            while (System.currentTimeMillis() < deadline) {
                newBin = findBins(dir).lastOrNull { it.absolutePath !in before }
                if (newBin != null) break
                delay(200)
            }
            check(newBin != null, "flush 后停流断开应生成新 bin")
            val info = controller.getBinFileInfo(newBin!!.absolutePath)
            check(info != null, "flush 后的 bin 应可被 getBinFileInfo 解析")
            "flush1=$r1 flush2=$r2 bin=${newBin!!.name} duration=${info!!.durationSec}s"
        },
    )
}
