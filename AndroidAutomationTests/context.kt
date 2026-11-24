package com.oymotion.sensorcapiktdemo.automation

import android.content.Context
import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import com.oymotion.sensorcapiktdemo.cerelaxpro.CerelaxSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 是否把 live BLE 数据录成 bin（落盘到每用例诊断目录）。
 * 失败时可直接用 `parseBinToCsv` / `replayBinFile` 复核原始数据；若担心影响性能类用例，
 * 可临时改为 false。
 */
internal const val DIAG_BIN_ENABLED = true

/**
 * controller 供给器：对齐 RN 的 `SensorController.Instance` 语义——用例里每次现取，不缓存。
 *
 * [SensorController.terminate] 会把静态 instance 置 null，之后再调 [SensorController.getInstance]
 * 会重新 `new SensorController()`（native 重新 create）。Android 与 RN 的差异是：新实例必须重新
 * [SensorController.registerBleBridge]，否则扫描/连接不可用。故在这里统一「实例变了才注册」。
 *
 * 注意（1.4.6 实测）：Android 上 terminate 是**终止态**——native 后端已被拆掉，重建的实例
 * `isEnable=false`、`startScan` 永远返回 false、connected 仍为 1，重启 App 前无法恢复。
 * 因此 terminate 类用例必须排在整轮执行的最后；provider 重建实例只是为了避免访问空指针。
 */
object ControllerProvider {
    @Volatile
    private var last: SensorController? = null

    @Synchronized
    fun acquire(context: Context): SensorController {
        val controller = SensorController.getInstance()
        if (controller !== last) {
            controller.registerBleBridge(context.applicationContext)
            last = controller
        }
        return controller
    }
}

class TestContext(
    val androidContext: Context,
    val log: (String) -> Unit,
    /** semi-auto 用例的人工步骤提示：UI 弹出独立提示并等待用户确认后才继续；无 UI 时为空实现。 */
    private val prompt: suspend (String) -> Unit = {},
) {
    /**
     * 半自动用例在需要人工动作（如关机/开机）时调用：每次只提示一个动作，用户确认后才继续，
     * 避免把「关机」「开机」等多个动作一次性堆给测试人员而无法按顺序执行。
     */
    suspend fun promptManual(message: String) {
        log("[人工操作] $message")
        prompt(message)
    }
    /** 与 RN 一致：每次现取，用例内 terminate 后也能拿到重建实例。 */
    val controller: SensorController get() = ControllerProvider.acquire(androidContext)

    /** 当前用例 id（用于失败现场归档目录）。 */
    var caseId: String = ""

    /** 当前用例的诊断产物目录：SDK 日志与 BLE bin 都落在这里。 */
    var diagDir: File = File(androidContext.cacheDir, "automation_diag/unknown")

    private val usedProfiles = LinkedHashSet<SensorProfile>()
    private val replayMacs = LinkedHashSet<String>()
    private var usedConnection = false

    /**
     * 用例开始前绑定诊断目录：
     *  - LOG_PATH 指向本用例独立目录，SDK 日志可回溯；
     *  - DEBUG_ENABLED=True 打开 SDK 调试日志（须在扫描/连接前设置才覆盖全流程）。
     */
    suspend fun bindCase(id: String) {
        caseId = id
        // 放应用外部目录，adb pull / 文件管理器可直接取；无外部存储时回退私有目录
        val root = androidContext.getExternalFilesDir("automation_diag")
            ?: File(androidContext.filesDir, "automation_diag")
        diagDir = File(root, id).apply { mkdirs() }
        runCatching { controller.setParam("LOG_PATH", diagDir.absolutePath) }
        runCatching { controller.setParam("DEBUG_ENABLED", "True") }
    }

    fun trackReplay(mac: String) {
        replayMacs.add(mac)
    }

    suspend fun scanTarget(timeoutMs: Int = TestConfig.SCAN_TIMEOUT_MS): BleDevice? =
        controller.scan(timeoutMs).firstOrNull { TestConfig.matchesTarget(it) }

    suspend fun requireTarget(): SensorProfile {
        val device = scanTarget() ?: throw AssertionError(
            "未扫描到目标设备 identity=${TestConfig.TARGET_IDENTITY}，请确认设备已开机"
        )
        return controller.requireSensor(device.mac)?.also { usedProfiles.add(it) }
            ?: throw AssertionError("requireSensor(${device.mac}) 返回 null")
    }

    /**
     * 多设备用例的设备池：扫描全部已登记设备（config.json 的 devices 清单），
     * 不限于 demo 页「选择设备」的单选目标——否则只选 1 台时，旁边已开机的其它设备
     * 会被过滤掉，多设备用例只能 SKIP。
     *
     * 排序：本轮目标设备 > 与目标同型号设备 > 其余已登记设备，取前 N 台时优先凑同型号；
     * 跨型号混测（如 MULTI-FUNC-006）在设备不足时才自然发生。
     */
    suspend fun requireKnownDevices(): List<SensorProfile> {
        val scanned = controller.scan(TestConfig.SCAN_TIMEOUT_MS).filter { TestConfig.matchesKnown(it) }
        val targetModel = runCatching { TestConfig.targetModel() }.getOrNull()
        val ordered = scanned.sortedWith(
            compareByDescending<BleDevice> { TestConfig.matchesTarget(it) }
                .thenByDescending { targetModel != null && TestConfig.deviceByMac(it.mac)?.model == targetModel },
        )
        return ordered.mapNotNull { controller.requireSensor(it.mac) }
            .onEach { usedProfiles.add(it) }
    }

    suspend fun prepare(profile: SensorProfile, init: Boolean = true): SensorProfile {
        usedProfiles.add(profile)
        usedConnection = true
        if (!profile.isReady()) {
            // 连接超时保护：避免 connect 卡死吃满整条用例超时，错误定位也更快
            val ok = withTimeoutOrNull(TestConfig.CONNECT_TIMEOUT_MS) { profile.connect() } ?: false
            check(ok, "connect() 超时（${TestConfig.CONNECT_TIMEOUT_MS}ms）或返回 false")
            waitFor({ profile.isReady() }, TestConfig.READY_TIMEOUT_MS, "设备 Ready")
        }
        if (init && !profile.hasInited()) {
            check(
                profile.init(
                    TestConfig.PACKAGE_SAMPLE_COUNT,
                    TestConfig.INIT_TIMEOUT_MS,
                    TestConfig.POWER_REFRESH_MS,
                ),
                "init() 返回 false",
            )
        }
        // 失败现场留证：把 live BLE 数据录成 bin，落到本用例诊断目录（LOG_PATH）。
        if (DIAG_BIN_ENABLED) {
            runCatching { profile.setParam("DEBUG_BLE_DATA_PATH", "True") }
        }
        return profile
    }

    suspend fun connected(): SensorProfile = prepare(requireTarget())

    suspend fun waitFor(condition: () -> Boolean, timeoutMs: Long, what: String) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(100)
        }
        check(condition(), "等待超时: $what (${timeoutMs}ms)")
    }

    suspend fun captureBatch(
        profile: SensorProfile,
        timeoutMs: Long = TestConfig.DATA_TIMEOUT_MS,
    ): SensorData {
        prepare(profile)
        val batches = ConcurrentLinkedQueue<SensorData>()
        val started = AtomicBoolean(false)
        profile.setOnDataListener { _, list ->
            list.firstOrNull()?.let { batches.offer(it.clone()) }
        }
        check(profile.startDataNotification(), "startDataNotification() 返回 false")
        started.set(true)
        try {
            waitFor({ batches.isNotEmpty() }, timeoutMs, "首包数据")
            return batches.poll()
        } finally {
            if (started.get()) profile.stopDataNotification()
        }
    }

    fun binPathOrSkip(): String {
        if (TestConfig.BIN_PATH.isBlank()) skip("未配置 TestConfig.BIN_PATH")
        val file = File(TestConfig.BIN_PATH)
        if (!file.isFile) skip("预录 bin 不存在: ${file.absolutePath}")
        return file.absolutePath
    }

    suspend fun cleanup() {
        runCatching { if (controller.isScanning) controller.stopScan() }
        for (mac in replayMacs) runCatching { controller.stopBinReplay(mac) }
        replayMacs.clear()
        for (profile in usedProfiles) {
            // 复位 autoReconnect 为默认开启：半自动用例（如 DEV-SM-009）关闭后若不复位，
            // 会通过 requireSensor 复用同一 profile 把 false 泄漏给后续用例（如 DEV-SM-010）。
            runCatching { profile.setAutoReconnect(true) }
            runCatching { if (profile.isDataTransfering()) profile.stopDataNotification() }
            runCatching { profile.setOnDataListener { _, _ -> } }
            runCatching { profile.setOnStateChangeListener { _, _ -> } }
            runCatching { profile.setOnDataTransferStateChangeListener { _, _ -> } }
            runCatching { profile.setOnDeviceInfoUpdateListener { _, _ -> } }
            runCatching { profile.setOnPowerListener { _, _ -> } }
            // 把本用例录到的 BLE 数据落盘，供失败时用 bin 回放/解析复核
            runCatching { profile.setParam("FLUSH_BLE_DATA", "") }
            // 只对已建立/就绪的连接做 disconnect；CONNECTING（连接卡住）时 disconnect 同样会挂起，
            // 跳过它，避免 cleanup 永久阻塞、把整条 run 拖死导致无法继续/无法导出。
            val state = profile.getDeviceState()
            if (state == DeviceState.READY || state == DeviceState.CONNECTED) {
                runCatching { profile.disconnect() }
            }
        }
        runCatching { controller.setOnScanResultListener { } }
        if (usedConnection) Thread.sleep(TestConfig.CASE_SETTLE_MS)
        usedProfiles.clear()
    }

    /**
     * 失败瞬间的设备现场（必须在 cleanup 之前调用，断连后状态与 NTF 开关就读不到了）。
     */
    suspend fun diagnosticSnapshot(): String = buildString {
        append("设备状态快照:")
        if (usedProfiles.isEmpty()) {
            append("\n  (本用例未建立连接 / 未持有 profile)")
            return@buildString
        }
        for (profile in usedProfiles.toList()) {
            val mac = runCatching { profile.device.mac }.getOrNull() ?: "?"
            val state = runCatching { DeviceState.nameOf(profile.getDeviceState()) }.getOrNull() ?: "?"
            val ready = runCatching { profile.isReady() }.getOrNull()
            val inited = runCatching { profile.hasInited() }.getOrNull()
            val transferring = runCatching { profile.isDataTransfering() }.getOrNull()
            append("\n  $mac state=$state ready=$ready hasInited=$inited transferring=$transferring")

            val ntf = runCatching { profile.getParam("NTF") }.getOrNull()
            if (!ntf.isNullOrBlank()) append("\n  NTF=$ntf")

            val info = runCatching { profile.getDeviceInfo() }.getOrNull()
            if (info != null) {
                append(
                    "\n  model=${info.modelName} fw=${info.firmwareVersion}" +
                        " eeg=${info.eegChannelCount} ppg=${info.ppgChannelCount}" +
                        " spo2=${info.spo2ChannelCount} imu=${info.imuChannelCount}" +
                        " acc=${info.accChannelCount} gyro=${info.gyroChannelCount}" +
                        " imp=${info.impeChannelCount}",
                )
            }
        }
    }

    /**
     * 本用例目录下的日志/bin 产物（必须在 cleanup 之后调用，确保 FLUSH_BLE_DATA 已落盘）。
     */
    fun diagnosticArtifacts(): String = buildString {
        val files = runCatching {
            diagDir.walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toList()
        }.getOrDefault(emptyList())
        append("诊断产物: ${diagDir.absolutePath}")
        if (files.isEmpty()) {
            append("\n  (未生成日志/bin，检查 DEBUG_ENABLED / DEBUG_BLE_DATA_PATH 是否生效)")
            return@buildString
        }
        files.takeLast(20).forEach { append("\n  ${it.name} ${it.length()}B") }
        files.lastOrNull { it.extension.equals("bin", ignoreCase = true) }
            ?.let { append("\n  BLE_BIN=${it.absolutePath}") }
    }
}

// ---- SensorAlgo 用例共享辅助（Android SDK 独有能力） -------------------------

/**
 * 算法/参数调用的错误判定。
 *
 * AAR 的 Kotlin suspend 扩展在 native 报错时会抛 IOException（消息以 `Error:` 开头）；
 * 这里把异常/超时统一转成同样前缀的字符串，便于用例断言而不被一条异常直接打断。
 */
internal fun algoError(s: String): Boolean = s.startsWith("Error") || s.startsWith("ERROR:")

/**
 * SensorProfile.setParam/getParam：native 报错会抛 IOException，统一转成 `Error:` 字符串，
 * 避免一个不支持/越界的 key 直接把整条用例打断。
 */
internal suspend fun safeSetParam(
    p: SensorProfile,
    key: String,
    value: String,
    timeoutMs: Long = 5_000,
): String = algoCall(timeoutMs) { p.setParam(key, value) }

internal suspend fun safeGetParam(
    p: SensorProfile,
    key: String,
    timeoutMs: Long = 5_000,
): String = algoCall(timeoutMs) { p.getParam(key) }

private suspend fun algoCall(timeoutMs: Long, block: suspend () -> String): String =
    withTimeoutOrNull(timeoutMs) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Error: ${e.message ?: e.javaClass.simpleName}"
        }
    } ?: "Error: 调用超时（${timeoutMs}ms）"

/** SensorAlgo.init：native 只回调完成码（True=成功）；超时/异常按 False 处理。 */
internal suspend fun SensorAlgo.safeInit(timeoutMs: Long = 15_000): Boolean =
    withTimeoutOrNull(timeoutMs) {
        try {
            init()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    } ?: false

internal suspend fun SensorAlgo.safeSetParam(key: String, value: String, timeoutMs: Long = 5_000): String =
    algoCall(timeoutMs) { setParam(key, value) }

internal suspend fun SensorAlgo.safeGetParam(key: String, timeoutMs: Long = 5_000): String =
    algoCall(timeoutMs) { getParam(key) }

internal suspend fun SensorAlgo.safeResult(type: String, timeoutMs: Long = 5_000): String =
    algoCall(timeoutMs) { getResult(type) }

/** SensorAlgo.pushSamples：true=该批被接受；false=拒绝/异常/超时。 */
internal suspend fun SensorAlgo.safePush(samples: Array<SensorData>, timeoutMs: Long = 15_000): Boolean =
    withTimeoutOrNull(timeoutMs) {
        try {
            pushSamples(samples, "")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    } ?: false

/** 把批次标称采样率同步给算法；标称非法（<=0）时跳过，避免传 "0" 污染参数。 */
internal suspend fun SensorAlgo.syncEegSampleRate(samples: List<SensorData>) {
    val rate = samples.firstOrNull()?.sampleRate?.toInt() ?: 0
    if (rate > 0) safeSetParam("EEG_SAMPLE_RATE", rate.toString())
}

internal suspend fun TestContext.withAlgo(body: suspend TestContext.(SensorAlgo) -> String): String {
    val algo = SensorAlgo()
    check(algo.safeInit(), "SensorAlgo.init 返回 false")
    check(algo.hasInit(), "SensorAlgo.hasInit=false")
    return try {
        body(algo)
    } finally {
        runCatching { algo.destroy() }
    }
}

/**
 * 采集指定流的数据批次（clone 后返回），供 SensorAlgo 推送。
 *
 * [minDurationMs] 用于满足算法的数据时间窗（如 PPG 需要更长窗口）；两个条件都满足才返回。
 */
internal suspend fun TestContext.collectAlgoBatches(
    streamKey: String,
    minSamples: Int,
    minDurationMs: Long = 0,
    timeoutMs: Long = 60_000,
): List<SensorData> {
    val p = connected()
    val paramKey = CerelaxSpec.paramKey(streamKey)
    val onRet = try {
        p.setParam(paramKey, "ON")
    } catch (e: Exception) {
        "Error: ${e.message ?: e.javaClass.simpleName}"
    }
    check(!algoError(onRet), "$paramKey=ON 被拒绝（$onRet）")

    val types = CerelaxSpec.dataTypesForKey(streamKey).toSet()
    val queue = ConcurrentLinkedQueue<SensorData>()
    p.setOnDataListener { _, list ->
        list.filter { it.dataType in types }.forEach { queue.offer(it.clone()) }
    }
    check(p.startDataNotification(), "startDataNotification 失败")
    val startedAt = System.currentTimeMillis()
    try {
        waitFor(
            {
                queue.sumOf { it.sampleCount } >= minSamples &&
                    System.currentTimeMillis() - startedAt >= minDurationMs
            },
            timeoutMs,
            "$streamKey 采集（≥$minSamples 样本 / ≥${minDurationMs}ms）",
        )
    } finally {
        runCatching { p.stopDataNotification() }
    }
    check(queue.isNotEmpty(), "$streamKey 未采集到数据")
    return queue.toList()
}

/** 结果文本中的数据行（跳过 `STEP|...` 表头；NO_HEADER 输出全部是数据行）。 */
internal fun algoRows(result: String): List<List<String>> =
    result.lineSequence()
        .map { it.trim() }
        .filter { it.contains('|') }
        .map { line -> line.split('|').map { it.trim() } }
        .filter { it.firstOrNull()?.toIntOrNull() != null }
        .toList()

/** 结果里的表头行（如 `STEP|TIMESTAMP|VALID|RELAX|CONCENTRATE|POSITION`），无表头返回 null。 */
internal fun algoHeader(result: String): List<String>? =
    result.lineSequence()
        .map { it.trim() }
        .filter { it.contains('|') }
        .map { line -> line.split('|').map { it.trim() } }
        .firstOrNull { it.firstOrNull()?.equals("STEP", ignoreCase = true) == true }

/** 按表头字段名取值，表头缺失时用 [fallback] 下标。 */
internal fun algoField(row: List<String>, header: List<String>?, name: String, fallback: Int): String? {
    val byName = header?.indexOfFirst { it.equals(name, ignoreCase = true) } ?: -1
    return row.getOrNull(if (byName >= 0) byName else fallback)
}

/** 取最后一行结果的 STEP（无数据行时抛断言）。 */
internal fun algoStep(result: String): Int {
    val row = algoRows(result).lastOrNull() ?: throw AssertionError("结果中没有数据行: ${result.trim()}")
    return row[0].toIntOrNull() ?: throw AssertionError("STEP 字段不可解析: $row")
}
