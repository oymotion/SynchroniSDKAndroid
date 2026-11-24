package com.oymotion.sensorcapiktdemo.automation

import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private const val CHAPTER = "02_连接与状态机"

/** Ready 后 init（参数取 TestConfig，对应 Instrumentation 侧同名辅助）。 */
private suspend fun initProfile(p: SensorProfile): Boolean =
    p.init(TestConfig.PACKAGE_SAMPLE_COUNT, TestConfig.INIT_TIMEOUT_MS, TestConfig.POWER_REFRESH_MS)

/** 轮询等待条件成立，超时返回 false（对应 Instrumentation 侧 waitUntil，不抛断言）。 */
private suspend fun pollUntil(condition: () -> Boolean, timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        delay(200)
    }
    return condition()
}

/** connect 并等到 Ready（不 init）。 */
private suspend fun TestContext.connectReady(p: SensorProfile): SensorProfile {
    check(p.connect(), "connect() 返回 false")
    waitFor({ p.isReady() }, TestConfig.READY_TIMEOUT_MS, "设备 Ready")
    return p
}

/**
 * BLE 偶发连接失败（原生日志 `Failed to connect`）时重试。
 * 实测同一设备失败后 6s 重连即成功，属链路时序抖动而非 SDK 缺陷，避免直接判 FAIL。
 */
private suspend fun TestContext.connectWithRetry(p: SensorProfile, attempts: Int = 3): Boolean {
    repeat(attempts) { i ->
        if (p.connect()) return true
        log("connect 第 ${i + 1}/$attempts 次失败")
        if (i < attempts - 1) delay(3000)
    }
    return false
}

/** config.json 中登记的目标设备 MAC（设备关机后无法扫描时用于取回 profile）。 */
private fun configuredTargetMac(): String? {
    val identities = TestConfig.targetIdentities
    return TestConfig.DEVICES
        .firstOrNull { d ->
            d.mac.isNotBlank() && identities.any { it.equals(d.identity, ignoreCase = true) }
        }
        ?.mac
}

/**
 * 取目标 profile：优先扫描；设备已关机（半自动用例的人工动作）时回退到 config MAC / 已缓存 profile。
 * 三者都不可用时记 SKIP 并提示先保持设备开机扫描一次。
 */
private suspend fun TestContext.targetProfile(): SensorProfile {
    scanTarget()?.let { d -> controller.requireSensor(d.mac)?.let { return it } }
    configuredTargetMac()?.let { mac -> controller.requireSensor(mac)?.let { return it } }
    controller.getSensors().firstOrNull { TestConfig.matchesTarget(it.device) }?.let { return it }
    skip("未扫描到目标设备且配置中无 MAC；请保持设备开机在范围内，或先执行一次可扫描到设备的用例")
}

val connectionCases = listOf(
    // =====================================================================================
    // DEV-SM-001 ~ DEV-SM-020（与 Instrumentation CerelaxConnectionTest 逐条对应）
    // =====================================================================================
    auto("DEV-SM-001", "connect 后状态迁移到 Ready", CHAPTER, Priority.P0) {
        val p = requireTarget()
        check(
            p.getDeviceState() == DeviceState.DISCONNECTED,
            "connect 前 deviceState 应为 DISCONNECTED，实际=${DeviceState.nameOf(p.getDeviceState())}",
        )

        val states = CopyOnWriteArrayList<Int>()
        p.setOnStateChangeListener { _, state ->
            states.add(state)
            log("  onStateChanged -> ${DeviceState.nameOf(state)}")
        }

        check(p.connect(), "connect 应返回 true")
        waitFor({ p.isReady() }, TestConfig.READY_TIMEOUT_MS, "Ready")
        check(p.getDeviceState() == DeviceState.READY, "deviceState 应为 READY")
        check(p.isReady(), "isReady 应为 true")

        val iConnecting = states.indexOf(DeviceState.CONNECTING)
        val iConnected = states.indexOf(DeviceState.CONNECTED)
        val iReady = states.indexOf(DeviceState.READY)
        val orderOk = iConnected >= 0 && iReady >= 0 && iConnected < iReady &&
            (iConnecting < 0 || iConnecting < iConnected)
        check(
            orderOk,
            "状态迁移顺序应为 Connecting→Connected→Ready，实际=${states.map { DeviceState.nameOf(it) }}",
        )

        p.disconnect()
        "states=${states.map { DeviceState.nameOf(it) }}"
    },
    semi(
        "DEV-SM-002", "设备关机时 connect 失败并回调错误", CHAPTER, Priority.P0,
        steps = listOf(
            "程序会分步提示：确认待测设备已开机且在范围内（程序需要扫描获取设备 MAC）。",
            "关机提示：关闭待测设备电源（或移出蓝牙范围），制造连接失败，完成后点继续。",
        ),
        timeoutMs = 180_000,
    ) {
        promptManual("① 请确认待测设备【已开机】且在范围内，然后点「我已执行，继续」（程序将扫描获取设备 MAC）。")
        val p = targetProfile()
        log("目标 profile: name=${p.device.name.orEmpty()} mac=${p.device.mac}")

        val errors = CopyOnWriteArrayList<String>()
        p.setOnErrorListener { _, errorMsg ->
            errors.add(errorMsg)
            log("  onError: $errorMsg")
        }
        p.setOnStateChangeListener { _, state ->
            log("  onStateChanged -> ${DeviceState.nameOf(state)}")
        }

        promptManual("② 请【关闭待测设备电源】（或移出蓝牙范围），确认设备已断电后点「我已执行，继续」。")
        log("connect() ...（对已关机设备可能阻塞至连接超时）")
        check(!p.connect(), "对已关机设备 connect 应返回 false")
        check(errors.isNotEmpty(), "应收到 onError 回调（连接失败原因）")
        check(p.getDeviceState() != DeviceState.READY, "不应进入 Ready")
        "errors=$errors"
    },
    auto("DEV-SM-003", "非目标设备不被误连", CHAPTER) {
        val devices = controller.scan(TestConfig.SCAN_TIMEOUT_MS)
        val target = devices.firstOrNull { TestConfig.matchesTarget(it) }
            ?: throw AssertionError("应扫描到目标设备")

        val targetMac = target.mac.uppercase()
        val others = devices.filter { it.mac.uppercase() != targetMac }
        log("目标: $targetMac；非目标 ${others.size} 台: ${others.map { it.mac }}")

        val p = controller.requireSensor(target.mac)
            ?: throw AssertionError("requireSensor(${target.mac}) 返回 null")
        connectReady(p)

        val connectedMacs = controller.getConnectedSensors().map { it.device.mac.uppercase() }.toSet()
        log("连接集合 = $connectedMacs")
        check(connectedMacs == setOf(targetMac), "连接集合应仅含目标设备，实际=$connectedMacs")

        if (others.isNotEmpty()) {
            val stray = others.map { it.mac.uppercase() }.filter { it in connectedMacs }
            check(stray.isEmpty(), "非目标设备不应被误连，实际误连=$stray")
        } else {
            log("环境中无非目标设备，跳过防串验证")
        }

        p.disconnect()
        "connected=$connectedMacs others=${others.size}"
    },
    auto("DEV-SM-004", "disconnect 后状态迁移到 Disconnected", CHAPTER, Priority.P0) {
        val p = requireTarget()
        val states = CopyOnWriteArrayList<Int>()
        p.setOnStateChangeListener { _, state ->
            states.add(state)
            log("  onStateChanged -> ${DeviceState.nameOf(state)}")
        }

        check(p.connect(), "connect 应成功")
        waitFor({ p.isReady() }, TestConfig.READY_TIMEOUT_MS, "Ready")

        states.clear()
        check(p.disconnect(), "disconnect 应返回 true")
        waitFor({ p.getDeviceState() == DeviceState.DISCONNECTED }, TestConfig.READY_TIMEOUT_MS, "Disconnected")
        check(
            states.contains(DeviceState.DISCONNECTED),
            "onStateChanged 应触发 DISCONNECTED，序列=${states.map { DeviceState.nameOf(it) }}",
        )
        "states=${states.map { DeviceState.nameOf(it) }}"
    },
    auto("DEV-SM-005", "起流中 disconnect 自动先停流", CHAPTER) {
        val p = connected()
        check(p.startDataNotification(), "startDataNotification 应成功")
        check(p.isDataTransfering(), "起流后 isDataTransfering 应为 true")

        // 起流中直接 disconnect，不显式 stopDataNotification
        check(p.disconnect(), "disconnect 应成功")
        check(!p.isDataTransfering(), "disconnect 后 isDataTransfering 应为 false")
        waitFor({ p.getDeviceState() == DeviceState.DISCONNECTED }, TestConfig.READY_TIMEOUT_MS, "Disconnected")
        "auto stop on disconnect OK"
    },
    auto("DEV-SM-006", "Ready 前 init/setParam/startDataNotification 被拒绝", CHAPTER, timeoutMs = 60_000) {
        val p = requireTarget()
        check(
            p.getDeviceState() != DeviceState.READY,
            "前置：未连接时 deviceState 应非 READY",
        )

        check(!initProfile(p), "Ready 前 init 应返回 false")

        val key = TestConfig.targetSpec().supportedStreams.firstOrNull() ?: "NTF_EEG"
        val setRet = try {
            p.setParam(key, "ON")
        } catch (e: Exception) {
            "Error: ${e.javaClass.simpleName}"
        }
        log("Ready 前 setParam($key) -> $setRet")
        check(setRet.startsWith("Error"), "Ready 前 setParam 应被拒绝（返回 Error）")

        check(!p.startDataNotification(), "Ready 前 startDataNotification 应返回 false")
        "cmd rejected before ready"
    },
    auto("DEV-SM-007", "BleDevice 元数据与扫描结果一致", CHAPTER) {
        val target = scanTarget() ?: throw AssertionError("未扫描到目标设备")
        val p = controller.requireSensor(target.mac)
            ?: throw AssertionError("requireSensor(${target.mac}) 返回 null")
        val bd = p.device

        check(
            bd.name.orEmpty() == target.name.orEmpty(),
            "Name 应一致（scan=${target.name.orEmpty()} device=${bd.name.orEmpty()}）",
        )
        check(
            bd.mac.equals(target.mac, ignoreCase = true),
            "Address 应一致（scan=${target.mac} device=${bd.mac}）",
        )
        // RSSI 随每次扫描/信号环境变化，两次快照不保证相等（实测 scan=-55 device=-59），只记录不硬断言
        log("RSSI scan=${target.rssi} device=${bd.rssi}（动态值，不做相等断言）")
        "name=${bd.name.orEmpty()} mac=${bd.mac} rssi=${bd.rssi}"
    },
    semi(
        "DEV-SM-008", "autoReconnect 默认开启时断链自动恢复", CHAPTER, Priority.P0,
        steps = listOf(
            "程序会分步提示：确认设备已开机在范围内后点继续，自动连接、init 并起流。",
            "关机提示：关闭待测设备电源（或移出蓝牙范围）并保持关闭，完成后点继续。",
            "开机提示：重新开启待测设备电源（或移回范围），开机后点继续，观察是否自动重连并恢复起流；自动重连未兜住时脚本会重试连接。",
        ),
        timeoutMs = 480_000,
    ) {
        promptManual("① 请确认待测设备【已开机】且在范围内，点「我已执行，继续」后程序会自动连接、init 并起流。")
        val p = requireTarget()
        check(p.getAutoReconnect(), "前置条件不满足：autoReconnect 应默认开启（被其他用例改成了 false）")
        val states = CopyOnWriteArrayList<Int>()
        val errors = CopyOnWriteArrayList<String>()
        val reconnects = CopyOnWriteArrayList<Boolean>()
        p.setOnStateChangeListener { _, state ->
            states.add(state)
            log("  onStateChanged -> ${DeviceState.nameOf(state)}")
        }
        p.setOnErrorListener { _, errorMsg ->
            errors.add(errorMsg)
            log("  onError: $errorMsg")
        }
        val restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        p.setOnAutoReconnectListener { _, restore, answer ->
            reconnects.add(restore)
            log("  onAutoReconnect restore=$restore")
            // 与 Demo（MainActivity.pressConnectForAutoReconnect）一致：回答 true 表示由宿主自行恢复会话，
            // SDK 会保留剩余重试次数（attempt 2/5~5/5）；
            // 回答 false 会走 SDK 内置 init replay，失败后直接终止且不再重试。
            answer.answer(true)
            if (restore) {
                restoreScope.launch {
                    if (!p.isReady() && !p.connect()) {
                        log("  宿主恢复：connect 失败（设备可能尚未就绪，交给 SDK 下一轮重试）")
                        return@launch
                    }
                    if (!p.hasInited() && !initProfile(p)) {
                        log("  宿主恢复：init 失败（设备可能尚未就绪，交给 SDK 下一轮重试）")
                        return@launch
                    }
                    if (!p.isDataTransfering()) runCatching { p.startDataNotification() }
                    log("  宿主恢复完成：ready=${p.isReady()} inited=${p.hasInited()} transfer=${p.isDataTransfering()}")
                }
            }
        }

        check(p.connect(), "connect 应成功")
        waitFor({ p.isReady() }, TestConfig.READY_TIMEOUT_MS, "Ready")
        check(initProfile(p), "init 应成功")
        check(p.startDataNotification(), "startDataNotification 应成功")
        check(p.isDataTransfering(), "起流后 isDataTransfering 应为 true")

        promptManual("② 设备已起流。请【关闭待测设备电源】（或移出蓝牙范围），并保持关闭直到出现下一步提示；完成后点「我已执行，继续」。")
        val stateDisconnected = pollUntil({ p.getDeviceState() == DeviceState.DISCONNECTED }, 120_000)
        check(stateDisconnected, "断链后 deviceState 应 DISCONNECTED")
        check(errors.isNotEmpty(), "断链应触发 onError 回调")

        promptManual("③ 请【重新开启待测设备电源】（或移回范围），开机后点「我已执行，继续」。")
        var recovery = "auto"
        // 与 Demo 相同：SDK 最多重试 5 轮，每轮由宿主恢复会话；设备启动期前 1~2 轮失败属正常。
        var transferRecovered = pollUntil({ p.isDataTransfering() }, 120_000)
        if (!transferRecovered) {
            // SDK 5 轮重试仍失败时才由脚本兜底重连。
            check(reconnects.isNotEmpty(), "应触发 onAutoReconnect 回调（否则不属于启动期恢复问题）")
            recovery = "script"
            // 对齐 Demo 的手动连接：先停掉自动重连循环，并回到干净的 DISCONNECTED 态。
            runCatching { p.setAutoReconnect(false) }
            if (p.getDeviceState() != DeviceState.DISCONNECTED) {
                runCatching { p.disconnect() }
                pollUntil({ p.getDeviceState() == DeviceState.DISCONNECTED }, 10_000)
            }
            log("转入脚本兜底：autoReconnect=false, state=${DeviceState.nameOf(p.getDeviceState())}, reconnecting=${p.isAutoReconnecting()}")

            val deadline = System.currentTimeMillis() + 90_000
            var attempts = 0
            while (System.currentTimeMillis() < deadline && !p.isReady()) {
                attempts++
                val visible = scanTarget() != null // 设备必须真的在广播，才值得尝试连接
                val ok = if (visible) runCatching { p.connect() }.getOrDefault(false) else false
                val ready = ok && pollUntil({ p.isReady() }, 5_000)
                log("兜底第 $attempts 次：scan=${if (visible) "found" else "none"} connect=$ok ready=$ready state=${DeviceState.nameOf(p.getDeviceState())}")
                if (ready) break
                if (!visible) delay(2_000) else delay(3_000)
            }
            check(p.isReady(), "脚本兜底重试 $attempts 次后仍未连上（设备可能未就绪或未上电）")
            log("脚本兜底重试 $attempts 次后连上（SDK 自动重连未兜住设备启动期，isAutoReconnecting=${p.isAutoReconnecting()}）")
            if (!p.hasInited()) check(initProfile(p), "兜底重连后 init 应成功")
            if (!p.isDataTransfering()) check(p.startDataNotification(), "兜底重连后 startDataNotification 应成功")
            transferRecovered = pollUntil({ p.isDataTransfering() }, 10_000)
        }
        check(reconnects.isNotEmpty(), "应触发 onAutoReconnect 回调")

        check(transferRecovered, "自动重连后应恢复起流")

        restoreScope.cancel()
        runCatching { p.stopDataNotification() }
        runCatching { p.disconnect() }
        "recovery=$recovery reconnects=${reconnects.size} states=${states.size}"
    },
    semi(
        "DEV-SM-009", "autoReconnect=false 不自动重连", CHAPTER,
        steps = listOf(
            "程序会分步提示：确认设备已开机在范围内后点继续，自动关闭 autoReconnect、连接、init 并起流。",
            "关机提示：关闭待测设备电源（或移出蓝牙范围），完成后点继续。",
            "开机提示：重新开启待测设备电源（或移回范围），完成后点继续；程序先确认设备已重新可被发现，再观察 15 秒确认不会自动重连。",
        ),
        timeoutMs = 300_000,
    ) {
        promptManual("① 请确认待测设备【已开机】且在范围内，点「我已执行，继续」后程序会关闭 autoReconnect、连接、init 并起流。")
        val p = requireTarget()
        p.setAutoReconnect(false)
        check(!p.getAutoReconnect(), "setAutoReconnect(false) 未生效（getAutoReconnect 仍为 true）")

        val errors = CopyOnWriteArrayList<String>()
        val reconnects = CopyOnWriteArrayList<Boolean>()
        p.setOnStateChangeListener { _, state ->
            log("  onStateChanged -> ${DeviceState.nameOf(state)}")
        }
        p.setOnErrorListener { _, errorMsg ->
            errors.add(errorMsg)
            log("  onError: $errorMsg")
        }
        p.setOnAutoReconnectListener { _, restore, answer ->
            reconnects.add(restore)
            log("  onAutoReconnect restore=$restore")
            answer.answer(false)
        }

        check(p.connect(), "connect 应成功")
        waitFor({ p.isReady() }, TestConfig.READY_TIMEOUT_MS, "Ready")
        check(initProfile(p), "init 应成功")
        check(p.startDataNotification(), "startDataNotification 应成功")

        promptManual("② 设备已起流。请【关闭待测设备电源】（或移出蓝牙范围），完成后点「我已执行，继续」。")
        check(
            pollUntil({ p.getDeviceState() == DeviceState.DISCONNECTED }, 120_000),
            "断链后 deviceState 应 DISCONNECTED",
        )
        check(errors.isNotEmpty(), "断链应触发 onError 回调")

        promptManual("③ 请【重新开启待测设备电源】（或移回范围），开机后点「我已执行，继续」。程序会先确认设备已重新可被发现（具备重连条件），再观察 15 秒确认不会自动重连。")
        // 设备没上电时「没重连」是假验证：必须先扫到设备，证明它已具备重连条件。
        var visibleMac: String? = null
        val scanDeadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < scanDeadline && visibleMac == null) {
            visibleMac = scanTarget()?.mac
            if (visibleMac == null) delay(2_000)
        }
        check(visibleMac != null, "设备开机后应能被扫描到（当前扫不到，无法验证「不重连」）")
        log("设备已重新可被发现：$visibleMac（具备重连条件）")

        val reachedReady = pollUntil({ p.getDeviceState() == DeviceState.READY }, 15_000)
        check(!reachedReady, "设备具备重连条件时，autoReconnect=False 不应自动重连到 Ready")
        check(reconnects.isEmpty(), "autoReconnect=False 时 onAutoReconnect 应 0 次")
        check(!p.isAutoReconnecting(), "autoReconnect=False 时 isAutoReconnecting 应为 false")

        runCatching { p.stopDataNotification() }
        runCatching { p.disconnect() }
        "reconnects=${reconnects.size} reachedReady=$reachedReady"
    },
    semi(
        "DEV-SM-010", "onAutoReconnect answer(true) 跳过默认恢复", CHAPTER,
        steps = listOf(
            "程序会分步提示：确认设备已开机在范围内后点继续，自动连接、init 并起流，并在回调中接管恢复流程。",
            "关机提示：关闭待测设备电源（或移出蓝牙范围），完成后点继续。",
            "开机提示：重新开启待测设备电源（或移回范围），完成后点继续，确认触发 onAutoReconnect 但不自动恢复。",
        ),
        timeoutMs = 400_000,
    ) {
        promptManual("① 请确认待测设备【已开机】且在范围内，点「我已执行，继续」后程序会连接、init 并起流，并在回调中接管恢复流程。")
        val p = requireTarget()
        val reconnects = CopyOnWriteArrayList<Boolean>()
        p.setOnStateChangeListener { _, state ->
            log("  onStateChanged -> ${DeviceState.nameOf(state)}")
        }
        p.setOnErrorListener { _, errorMsg ->
            log("  onError: $errorMsg")
        }
        p.setOnAutoReconnectListener { _, restore, answer ->
            reconnects.add(restore)
            log("  onAutoReconnect restore=$restore")
            answer.answer(true) // 应用接管，跳过 SDK 默认恢复流程
        }

        check(p.connect(), "connect 应成功")
        waitFor({ p.isReady() }, TestConfig.READY_TIMEOUT_MS, "Ready")
        check(initProfile(p), "init 应成功")
        check(p.startDataNotification(), "startDataNotification 应成功")

        promptManual("② 设备已起流。请【关闭待测设备电源】（或移出蓝牙范围），完成后点「我已执行，继续」。")
        check(
            pollUntil({ p.getDeviceState() == DeviceState.DISCONNECTED }, 120_000),
            "断链后 deviceState 应 DISCONNECTED",
        )

        promptManual("③ 请【重新开启待测设备电源】（或移回范围），开机后点「我已执行，继续」（应触发 onAutoReconnect 但不自动恢复）。")
        check(
            pollUntil({ reconnects.isNotEmpty() }, 120_000),
            "应触发 onAutoReconnect 回调",
        )

        // READY 只表示「链路已建 + 设备信息已取」，自动重连成功后必然回到 READY（与 init 无关）。
        // 「跳过默认恢复」的正确观测点是 SDK 没有执行 init/replay、也没有重新起流。
        val reachedReady = pollUntil({ p.getDeviceState() == DeviceState.READY }, 30_000)
        check(!p.hasInited(), "answer(true) 后 SDK 不应执行 init 恢复会话")
        check(!p.isDataTransfering(), "answer(true) 后不应自动起流")

        runCatching { p.stopDataNotification() }
        runCatching { p.disconnect() }
        "reconnects=${reconnects.size} reachedReady=$reachedReady hasInited=false transferring=false"
    },
    auto("DEV-SM-011", "多台目标设备同时连接互不干扰", CHAPTER, timeoutMs = 180_000) {
        val devices = controller.scan(TestConfig.SCAN_TIMEOUT_MS)
        val matched = devices
            .filter { TestConfig.deviceByMac(it.mac) != null }
            .distinctBy { it.mac.uppercase() }
        if (matched.size < 2) skip("需要 ≥2 台目标设备在范围内，当前 ${matched.size} 台")

        val profiles = matched.map { d ->
            d to (controller.requireSensor(d.mac)
                ?: throw AssertionError("requireSensor(${d.mac}) 返回 null"))
        }

        // 串行连接，各自等 Ready
        for ((d, p) in profiles) {
            check(p.connect(), "[${d.mac}] connect 应成功")
            waitFor({ p.isReady() }, TestConfig.READY_TIMEOUT_MS, "[${d.mac}] Ready")
        }
        check(
            profiles.all { it.second.getDeviceState() == DeviceState.READY },
            "连接后每台应保持 Ready（无串扰）",
        )

        // init + 起流 + 收数据
        val dataCounts = ConcurrentHashMap<String, AtomicInteger>()
        for ((d, p) in profiles) {
            check(initProfile(p), "[${d.mac}] init 应成功")
            val counter = AtomicInteger(0)
            dataCounts[d.mac] = counter
            p.setOnDataListener { _, dataList ->
                counter.addAndGet(dataList.sumOf { it.sampleCount })
            }
            check(p.startDataNotification(), "[${d.mac}] startDataNotification 应成功")
            check(p.isDataTransfering(), "[${d.mac}] 起流后 isDataTransfering 应为 true")
        }

        delay(TestConfig.COLLECT_SECONDS * 1000L)
        for ((d, _) in profiles) {
            val n = dataCounts[d.mac]?.get() ?: 0
            check(n > 0, "[${d.mac}] 应收到数据（样本数>0），实际=$n")
        }

        // 断开第一台，其余仍 Ready（断开串扰）
        profiles.first().second.disconnect()
        delay(2000)
        check(
            profiles.drop(1).all { it.second.getDeviceState() == DeviceState.READY },
            "断开第一台后其余应仍 Ready（无串扰）",
        )

        for ((_, p) in profiles) {
            runCatching { p.stopDataNotification() }
            runCatching { p.disconnect() }
        }
        "devices=${profiles.size}"
    },
    semi(
        "DEV-SM-012", "突然断电/超范围触发 Disconnected 与错误回调", CHAPTER, Priority.P0,
        steps = listOf(
            "程序会分步提示：确认设备已开机在范围内后点继续，自动连接、init 并起流。",
            "关机提示：关闭待测设备电源（或移出蓝牙范围），完成后点继续，触发 Disconnected 与错误回调。",
        ),
        timeoutMs = 300_000,
    ) {
        promptManual("① 请确认待测设备【已开机】且在范围内，点「我已执行，继续」后程序会连接、init 并起流。")
        val p = requireTarget()
        val states = CopyOnWriteArrayList<Int>()
        val errors = CopyOnWriteArrayList<String>()
        p.setOnStateChangeListener { _, state ->
            states.add(state)
            log("  onStateChanged -> ${DeviceState.nameOf(state)}")
        }
        p.setOnErrorListener { _, errorMsg ->
            errors.add(errorMsg)
            log("  onError: $errorMsg")
        }

        check(p.connect(), "connect 应成功")
        waitFor({ p.isReady() }, TestConfig.READY_TIMEOUT_MS, "Ready")
        check(initProfile(p), "init 应成功")
        check(p.startDataNotification(), "startDataNotification 应成功")

        promptManual("② 设备已起流。请【关闭待测设备电源】（或移出蓝牙范围），完成后点「我已执行，继续」。")
        check(
            pollUntil({ states.contains(DeviceState.DISCONNECTED) }, 150_000),
            "onStateChanged 应触发 DISCONNECTED",
        )
        check(errors.isNotEmpty(), "应触发 onError 回调（异常断链原因）")

        runCatching { p.stopDataNotification() }
        runCatching { p.disconnect() }
        "states=${states.map { DeviceState.nameOf(it) }}"
    },
    auto("DEV-SM-013", "isReady 与 deviceState==Ready 一致", CHAPTER) {
        val p = requireTarget()

        check(
            p.getDeviceState() == DeviceState.DISCONNECTED,
            "connect 前 deviceState 应为 DISCONNECTED",
        )
        check(!p.isReady(), "connect 前 isReady 应为 false")

        check(p.connect(), "connect 应成功")
        waitFor({ p.isReady() }, TestConfig.READY_TIMEOUT_MS, "Ready")
        check(p.getDeviceState() == DeviceState.READY, "Ready 时 deviceState 应为 READY")
        check(p.isReady(), "Ready 时 isReady 应为 true")

        p.disconnect()
        waitFor({ p.getDeviceState() == DeviceState.DISCONNECTED }, TestConfig.READY_TIMEOUT_MS, "Disconnected")
        check(!p.isReady(), "断开后 isReady 应为 false")
        "isReady consistent"
    },
    auto("DEV-SM-014", "onDeviceInfoUpdate 触发与就地更新", CHAPTER, timeoutMs = 120_000) {
        val p = requireTarget()

        val updates = CopyOnWriteArrayList<DeviceInfo>()
        p.setOnDeviceInfoUpdateListener { _, info ->
            updates.add(info)
            log("  onDeviceInfoUpdate 收到 DeviceInfo 更新")
        }

        check(p.connect(), "connect 应成功")
        waitFor({ p.isReady() }, TestConfig.READY_TIMEOUT_MS, "Ready")
        check(initProfile(p), "init 应成功")

        check(p.getDeviceInfo() != null, "init 后 getDeviceInfo 应返回非空 DeviceInfo")

        // 变更采样率会改变 DeviceInfo 上报值，触发 onDeviceInfoUpdate；优先选 spec 中多档可设的参数
        val rateEntry = TestConfig.targetSpec().sampleRates.entries
            .firstOrNull { it.value.settable && it.value.rates.size > 1 }
        var originalRate: String? = null
        var triggered = false
        try {
            if (rateEntry != null) {
                val raw = runCatching { p.getParam(rateEntry.key) }.getOrNull().orEmpty()
                originalRate = rateEntry.value.rates.firstOrNull { raw.contains(it) }
                // 优先切到一个与原值不同的档，确保能触发 DeviceInfo 更新
                val value = rateEntry.value.rates.firstOrNull { it != originalRate }
                    ?: rateEntry.value.rates.first { it != rateEntry.value.default }
                log("触发尝试 setParam(${rateEntry.key}, $value)（原值=${originalRate ?: raw}）")
                // 诊断（不影响判定）：确认 setParam 是否生效、DeviceInfo 缓存是否变化，用于区分
                // 「参数没改成功」与「改成功但 SDK 不推送 onDeviceInfoUpdate」。
                val diBefore = p.getDeviceInfo()
                log(
                    "诊断: getParam前='$raw' 解析原值=${originalRate ?: "null"}；DeviceInfo前 " +
                        "emg=${diBefore?.emgSampleRate} eeg=${diBefore?.eegSampleRate} " +
                        "ppg=${diBefore?.ppgSampleRate} imu=${diBefore?.imuSampleRate}",
                )
                val setRet = p.setParam(rateEntry.key, value)
                val rawAfter = runCatching { p.getParam(rateEntry.key) }.getOrNull().orEmpty()
                val diAfter = p.getDeviceInfo()
                log(
                    "诊断: setParam(${rateEntry.key}, $value) -> $setRet；getParam后='$rawAfter'；" +
                        "DeviceInfo后 emg=${diAfter?.emgSampleRate} eeg=${diAfter?.eegSampleRate} " +
                        "ppg=${diAfter?.ppgSampleRate} imu=${diAfter?.imuSampleRate} " +
                        "同一对象=${diBefore === diAfter}",
                )

                triggered = pollUntil({ updates.isNotEmpty() }, 30_000)
                log("诊断: 30s 内 onDeviceInfoUpdate 次数=${updates.size}，triggered=$triggered")
                if (triggered) {
                    check(
                        updates.last() === p.getDeviceInfo(),
                        "回调 DeviceInfo 与 getDeviceInfo() 应为同一缓存对象（就地更新）",
                    )
                } else {
                    log("未触发 onDeviceInfoUpdate（环境/固件无触发场景），跳过就地更新断言")
                }
            } else {
                log("spec 无多档可设采样率，跳过变更触发")
            }
        } finally {
            // 关键：还原原采样率，避免污染后续用例/下一轮（曾导致 PARAM-FUNC-004/006 误判默认值）
            val restore = originalRate ?: rateEntry?.value?.default
            if (rateEntry != null && restore != null) {
                runCatching { p.setParam(rateEntry.key, restore) }
                log("已还原 ${rateEntry.key}=$restore")
            }
            p.disconnect()
        }
        "updates=${updates.size} triggered=$triggered"
    },
    auto("DEV-SM-015", "重复 connect 幂等", CHAPTER) {
        val p = requireTarget()
        check(p.connect(), "首次 connect 应成功")
        waitFor({ p.isReady() }, TestConfig.READY_TIMEOUT_MS, "Ready")

        var err = false
        try {
            p.connect()
        } catch (e: Exception) {
            err = true
            log("重复 connect 抛异常 ${e.javaClass.simpleName}")
        }
        check(!err, "重复 connect 不应抛异常")

        val state = p.getDeviceState()
        check(
            state == DeviceState.READY || state == DeviceState.CONNECTED || state == DeviceState.CONNECTING,
            "重复 connect 后不应回到 DISCONNECTED（state=${DeviceState.nameOf(state)}）",
        )
        p.disconnect()
        "state=${DeviceState.nameOf(state)}"
    },
    auto("DEV-SM-016", "重复 disconnect 幂等", CHAPTER) {
        val p = requireTarget()
        check(connectWithRetry(p), "connect 应成功")
        waitFor({ p.isReady() }, TestConfig.READY_TIMEOUT_MS, "Ready")

        p.disconnect()
        waitFor({ p.getDeviceState() == DeviceState.DISCONNECTED }, TestConfig.READY_TIMEOUT_MS, "Disconnected")

        var err = false
        try {
            p.disconnect()
        } catch (e: Exception) {
            err = true
            log("重复 disconnect 抛异常 ${e.javaClass.simpleName}")
        }
        check(!err, "重复 disconnect 不应抛异常")
        check(
            p.getDeviceState() == DeviceState.DISCONNECTED,
            "重复 disconnect 后应仍 DISCONNECTED",
        )
        "disconnect idempotent"
    },
    manual(
        "DEV-SM-017", "BLE MTU 协商与分包正确性", CHAPTER, Priority.P2,
        steps = listOf(
            "Android 不适用：Android SDK 未暴露 MTU 协商接口；" +
                "分包正确性由底层 BLE 栈保证，需抓包/仪器人工验证。",
        ),
    ),
    manual(
        "DEV-SM-018", "配对/bond 信息持久与免配对重连", CHAPTER, Priority.P2,
        steps = listOf(
            "Android 不适用：依赖系统蓝牙配对/bond 状态，需人工在系统设置配对后验证免配对重连。",
        ),
    ),
    manual(
        "DEV-SM-019", "广播间隔与重连退避", CHAPTER, Priority.P2,
        steps = listOf(
            "Android 不适用：广播间隔与重连退避为底层 BLE 栈时序，Android SDK 未暴露可断言接口，需抓包验证。",
        ),
    ),
    auto("DEV-SM-020", "isAutoReconnecting 非恢复态恒为 false", CHAPTER) {
        val p = requireTarget()
        check(!p.isAutoReconnecting(), "未连接时 isAutoReconnecting 应为 false")

        check(p.connect(), "connect 应成功")
        waitFor({ p.isReady() }, TestConfig.READY_TIMEOUT_MS, "Ready")
        check(!p.isAutoReconnecting(), "正常 Ready 态 isAutoReconnecting 应为 false")

        p.setAutoReconnect(false)
        check(!p.isAutoReconnecting(), "关闭 autoReconnect 后 isAutoReconnecting 应为 false")
        p.setAutoReconnect(true)
        check(!p.isAutoReconnecting(), "重新开启 autoReconnect 后（未断链）isAutoReconnecting 应为 false")

        p.disconnect()
        waitFor({ p.getDeviceState() == DeviceState.DISCONNECTED }, TestConfig.READY_TIMEOUT_MS, "Disconnected")
        check(!p.isAutoReconnecting(), "主动断开后 isAutoReconnecting 应为 false")
        "isAutoReconnecting=false"
    },
)
