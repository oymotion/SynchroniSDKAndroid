package com.oymotion.sensorcapiktdemo.automation

import android.bluetooth.BluetoothManager
import android.content.Context
import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val CHAPTER = "01_控制器与扫描"

/**
 * 系统蓝牙开关的等待上限。
 * Android 蓝牙状态由系统广播经 SensorBleBridge → native 异步回传，`isEnable()` / `onEnableChanged`
 * 相对人工拨动开关有约 1~2s 延迟（1.4.6 实测），因此不能拨完立即断言，必须轮询等待。
 */
private const val BT_STATE_TIMEOUT_MS = 15_000L

/**
 * 系统蓝牙的真实开关状态（`BluetoothAdapter.isEnabled`，无 native 同步延迟）。
 * 无权限/异常时返回 null，调用方回退到 `controller.isEnable`。
 */
private fun TestContext.systemBluetoothEnabled(): Boolean? =
    runCatching {
        (androidContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.isEnabled
    }.getOrNull()

/** 安全调用 startScan，返回（是否抛异常, 返回值）。 */
private fun TestContext.safeStartScan(periodMs: Int): Pair<Boolean, Boolean> =
    try {
        false to controller.startScan(periodMs)
    } catch (e: Exception) {
        log("startScan($periodMs) 抛异常 ${e.javaClass.simpleName}: ${e.message}")
        true to false
    }

/** 非抛异常的轮询等待（超时返回 false），供需要 SKIP 语义的前置判定使用。 */
private suspend fun TestContext.awaitCondition(condition: () -> Boolean, timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        delay(100)
    }
    return condition()
}

/**
 * 扫描类用例的公共前置：手机蓝牙须开启，且 SDK 使能态已同步为 true。
 * 不满足记 SKIP（环境未就绪），避免蓝牙关闭 / terminate 终止态下「扫描从未启动」的假 PASS。
 */
private suspend fun TestContext.requireScanEnvironment() {
    if (systemBluetoothEnabled() == false) skip("环境未就绪：手机蓝牙未开启")
    if (!awaitCondition({ controller.isEnable }, BT_STATE_TIMEOUT_MS)) {
        skip("环境未就绪：controller.isEnable=false（蓝牙未开启或 terminate 终止态，请重启 App）")
    }
}

val controlCases = listOf(
    // CTRL-FUNC-001：蓝牙未开启时 isEnable==false，startScan 被拒绝、scan 无结果。semi-auto（人工开关蓝牙）。
    semi(
        "CTRL-FUNC-001", "蓝牙关闭时 isEnable=false、startScan 被拒绝、scan 无结果", CHAPTER, Priority.P0,
        steps = listOf(
            "请【关闭手机/平板】蓝牙（不是待测头环），头环保持开机在范围内",
            "请【开启手机/平板】蓝牙以恢复（本阶段只检查 isEnable）",
        ),
        timeoutMs = 120_000,
    ) {
        promptManual("① 请【关闭手机/平板】蓝牙（不是待测头环），头环保持开机在范围内。关闭后点「我已执行，继续」。")
        // 系统蓝牙关闭后 isEnable 异步回传（约 1~2s），轮询等待而不是立即断言
        waitFor({ !controller.isEnable }, BT_STATE_TIMEOUT_MS, "关闭蓝牙后 isEnable=false")

        val startRet = controller.startScan(1_000)
        log("蓝牙关闭时 startScan=$startRet, isScanning=${controller.isScanning}")
        check(!startRet, "蓝牙关闭时 startScan 应被拒绝（返回 false），实际=$startRet")
        check(!controller.isScanning, "蓝牙关闭时不应进入扫描态")
        controller.stopScan()
        val scanned = controller.scan(TestConfig.SCAN_TIMEOUT_MS)
        check(scanned.isEmpty(), "蓝牙关闭时 scan 应无结果，实际=${scanned.size} 台")

        promptManual("② 请【开启手机/平板】蓝牙以恢复。开启后点「我已执行，继续」。")
        waitFor({ controller.isEnable }, BT_STATE_TIMEOUT_MS, "恢复后 isEnable=true")
        check(!controller.isScanning, "恢复后不应残留扫描态")
        "isEnable=false->true, startScan(关闭)=false, scan=0 台"
    },

    // CTRL-FUNC-002：蓝牙开关时 isEnable 与 onEnableChanged 回调一致。semi-auto（人工开关蓝牙）。
    semi(
        "CTRL-FUNC-002", "蓝牙开关时 isEnable 与 onEnableChanged 回调一致", CHAPTER, Priority.P1,
        steps = listOf(
            "确保【手机/平板】蓝牙已开启（关闭则先开启）",
            "请【关闭手机/平板】蓝牙",
            "请【开启手机/平板】蓝牙",
        ),
        timeoutMs = 120_000,
    ) {
        val events = CopyOnWriteArrayList<Boolean>()
        controller.setOnEnableChangedListener { enabled ->
            events.add(enabled)
            log("onEnableChanged enabled=$enabled")
        }

        // 起点必须是「开启」态，否则第一步关闭不产生状态变化、回调天然不会触发。
        // 判定用系统蓝牙真实状态：测试人员常常「先关蓝牙再点执行」，此时 controller.isEnable 还停留在
        // 上一次的 true（native 同步有 1~2s 延迟），若据此判断会跳过准备步骤、错过关闭回调。
        val startOn = systemBluetoothEnabled() ?: controller.isEnable
        if (!startOn) {
            promptManual("① 检测到手机蓝牙未开启。请先【开启手机/平板】蓝牙，开启后点「我已执行，继续」。")
        }
        // 等 SDK 使能态追上系统状态（开启），再把准备阶段的回调清掉，只验证后续关→开
        waitFor({ controller.isEnable }, BT_STATE_TIMEOUT_MS, "起始状态：蓝牙已开启")
        events.clear()

        promptManual("② 请【关闭手机/平板】蓝牙，关闭后点「我已执行，继续」。")
        waitFor({ !controller.isEnable }, BT_STATE_TIMEOUT_MS, "关闭蓝牙后 isEnable=false")
        waitFor({ events.any { !it } }, BT_STATE_TIMEOUT_MS, "关闭蓝牙后收到 enabled=false 回调")

        promptManual("③ 请【开启手机/平板】蓝牙，开启后点「我已执行，继续」。")
        waitFor({ controller.isEnable }, BT_STATE_TIMEOUT_MS, "开启蓝牙后 isEnable=true")
        waitFor({ events.any { it } }, BT_STATE_TIMEOUT_MS, "开启蓝牙后收到 enabled=true 回调")
        "isEnable=true, 回调序列=$events"
    },

    // CTRL-FUNC-003：startScan 返回 true、isScanning 翻转，并连接目标设备到 Ready。
    auto(
        "CTRL-FUNC-003", "startScan 返回 true、isScanning 翻转并连接目标到 Ready", CHAPTER, Priority.P0,
        120_000,
    ) {
        val (raised, ret) = safeStartScan(TestConfig.SCAN_TIMEOUT_MS)
        check(!raised, "startScan 不应抛异常")
        check(ret, "startScan 应返回 true")
        check(controller.isScanning, "startScan 后 isScanning 应为 true")

        controller.stopScan()
        delay(500)
        check(!controller.isScanning, "stopScan 后 isScanning 应为 false")

        val p = connected()
        check(p.isReady(), "connect 应成功且到 Ready")
        p.disconnect()
        "scan state OK, connected Ready"
    },

    // CTRL-FUNC-004：scan 返回列表且含目标设备，Address/RSSI 非空。
    auto("CTRL-FUNC-004", "scan 返回目标设备且 Address/RSSI 有效", CHAPTER, Priority.P0) {
        val d = scanTarget() ?: throw AssertionError("scan 应发现目标设备")
        check(d.mac.isNotEmpty(), "目标设备 Address 应非空")
        check(d.rssi != 0, "目标设备 RSSI 应为非零（BLE 信号强度）")
        log("目标设备: name=${d.name.orEmpty()} mac=${d.mac} rssi=${d.rssi}")
        "name=${d.name.orEmpty()} mac=${d.mac} rssi=${d.rssi}"
    },

    // CTRL-FUNC-005：onScanResult 周期触发且列表含目标设备。
    auto(
        "CTRL-FUNC-005", "onScanResult 周期触发且列表含目标设备", CHAPTER, Priority.P1,
        30_000,
    ) {
        val events = CopyOnWriteArrayList<Pair<Long, List<BleDevice>>>()
        val t0 = System.currentTimeMillis()
        controller.setOnScanResultListener { devices ->
            events.add(System.currentTimeMillis() - t0 to devices.toList())
            log("onScanResult +${System.currentTimeMillis() - t0}ms ${devices.size} 台")
        }
        check(controller.startScan(3_000), "startScan 应返回 true")
        delay(8_000)
        controller.stopScan()

        check(events.size >= 2, "onScanResult 应至少触发 2 次，实际=${events.size}")
        val hasTarget = events.any { (_, devices) -> devices.any { TestConfig.matchesTarget(it) } }
        check(hasTarget, "onScanResult 列表应含目标设备")
        "callbacks=${events.size}"
    },

    // CTRL-FUNC-006：stopScan 后回调停止（不再新增）。
    auto(
        "CTRL-FUNC-006", "stopScan 后 onScanResult 停止回调", CHAPTER, Priority.P1,
        60_000,
    ) {
        val events = CopyOnWriteArrayList<Long>()
        val t0 = System.currentTimeMillis()
        controller.setOnScanResultListener { events.add(System.currentTimeMillis() - t0) }

        check(controller.startScan(3_000), "startScan 应返回 true")
        waitFor({ events.isNotEmpty() }, 15_000, "stopScan 前 onScanResult 触发")
        controller.stopScan()

        delay(3_000) // 排空在途回调
        val baseline = events.size
        delay(6_000) // 稳定期（2 个周期）
        val after = events.size
        log("stopScan 后：排空后 $baseline 次 -> 稳定期后 $after 次（新增 ${after - baseline}）")
        check(after == baseline, "stopScan 后不应再有新回调（$baseline -> $after）")
        "stopped after $baseline callbacks"
    },

    // CTRL-FUNC-007：requireSensor 有效 MAC 命中、无效 MAC 未命中。
    auto("CTRL-FUNC-007", "requireSensor 有效 MAC 命中、无效 MAC 未命中", CHAPTER, Priority.P0) {
        val d = scanTarget() ?: throw AssertionError("未扫描到目标设备")
        check(controller.requireSensor(d.mac) != null, "requireSensor(有效 mac) 应返回 SensorProfile")
        // 无效输入用空串 / 非法格式；"00:00:00:00:00:00" 是合法 MAC（Python _MAC_PATTERN 也匹配），
        // requireSensor 会为其创建 profile 返回非空，不属于「非法输入」，故这里不用它，避免残留污染。
        for (bad in listOf("", "INVALID_MAC", "AA:BB:CC:DD:EE:FF:GG")) {
            val r = try {
                controller.requireSensor(bad)
            } catch (e: Exception) {
                log("requireSensor('$bad') 抛异常 ${e.javaClass.simpleName}")
                null
            }
            check(r == null, "requireSensor('$bad') 应返回 null（非法输入未命中）")
        }
        "requireSensor OK mac=${d.mac}"
    },

    // CTRL-FUNC-008：getSensor 正确 MAC 命中、错误 MAC 未命中。
    auto("CTRL-FUNC-008", "getSensor 正确 MAC 命中、错误 MAC 未命中", CHAPTER, Priority.P0) {
        val d = scanTarget() ?: throw AssertionError("未扫描到目标设备")
        val created = controller.requireSensor(d.mac)
        check(created != null, "requireSensor 应创建 profile")
        check(controller.getSensor(d.mac) === created, "getSensor(正确 mac) 应命中同一 profile")
        check(controller.getSensor("00:00:00:00:00:00") == null, "getSensor(错误 mac) 不应命中")
        "getSensor hit/miss OK"
    },

    // CTRL-FUNC-009：连接后 getConnectedSensors 含目标 mac，断开后不含。
    auto(
        "CTRL-FUNC-009", "连接后 getConnectedSensors 含目标、断开后不含", CHAPTER, Priority.P0,
        120_000,
    ) {
        val p = connected()
        val mac = p.device.mac
        val connectedMacs = controller.getConnectedSensors().map { it.device.mac }
        log("连接后 getConnectedSensors = $connectedMacs")
        check(mac in connectedMacs, "连接后 getConnectedSensors 应含 $mac")

        p.disconnect()
        waitFor(
            { controller.getConnectedSensors().none { it.device.mac == mac } },
            10_000,
            "断开后 getConnectedSensors 不含 $mac",
        )
        log("断开后 getConnectedSensors = ${controller.getConnectedSensors().map { it.device.mac }}")
        "connected/disconnected mac=$mac"
    },

    // CTRL-FUNC-010：getBLEBackendName。Android 无后端切换（原生 BLE），标记不适用。
    manual(
        "CTRL-FUNC-010", "getBLEBackendName", CHAPTER, Priority.P2,
        steps = listOf("Android 不适用：SDK 固定使用系统原生 BLE，无 bleak/bumble 后端切换与 getBLEBackendName"),
    ),

    // CTRL-FUNC-011：checkSetupDongle。Android 无 USB dongle 概念，标记不适用。
    manual(
        "CTRL-FUNC-011", "checkSetupDongle", CHAPTER, Priority.P2,
        steps = listOf("Android 不适用：无 USB BLE dongle / WinUSB 驱动引导流程，checkSetupDongle 为桌面专属"),
    ),

    // CTRL-FUNC-012：getVersion 返回非空且为 x.y.z 版本号格式。
    auto("CTRL-FUNC-012", "getVersion 返回非空且为 x.y.z 版本号格式", CHAPTER, Priority.P1) {
        val version = controller.version
        log("getVersion = $version")
        check(version.isNotEmpty(), "getVersion 应返回非空")
        check(
            Regex("^\\d+\\.\\d+\\.\\d+").containsMatchIn(version),
            "getVersion 应为 x.y.z 版本号格式，实际=$version",
        )
        "version=$version"
    },

    // CTRL-FUNC-013：terminate 重复调用不抛异常（连接态下验证单例重置）。
    // Android 1.4.6 实测：terminate 是终止态——native 后端被拆掉，重建实例后 isEnable=false、
    // startScan 恒为 false 且 connected 仍为 1，重启 App 前无法恢复。故本用例 destructive，
    // 固定排在整轮执行最后；断言口径回到手工用例：两次 terminate 均不抛异常 + 单例已重置。
    auto(
        "CTRL-FUNC-013", "terminate 重复调用不抛异常、单例重置", CHAPTER, Priority.P1,
        120_000, destructive = true,
    ) {
        val d = scanTarget() ?: throw AssertionError("未扫描到目标设备")
        // 不走上下文跟踪，避免 terminate 后 cleanup 再访问已释放的 profile。
        val p = controller.requireSensor(d.mac) ?: throw AssertionError("requireSensor 返回 null")
        check(p.connect(), "应先成功连接")
        waitFor({ p.isReady() }, TestConfig.READY_TIMEOUT_MS, "设备 Ready")

        val before = controller.getConnectedSensors().size
        val oldInstance = controller
        log("连接态 getConnectedSensors = $before")
        check(before >= 1, "连接态 getConnectedSensors 应至少 1 台")

        var firstError: Throwable? = null
        try {
            SensorController.terminate()
        } catch (e: Exception) {
            firstError = e
        }
        check(firstError == null, "第1次 terminate 不应抛异常: ${firstError?.message}")

        // 重新 getInstance 应返回新实例（单例已重置）；terminate 后后端不可用，仅记录不判失败。
        val fresh = controller
        check(fresh !== oldInstance, "terminate 后 getInstance() 应返回新实例（单例已重置）")
        val afterConnected = runCatching { fresh.getConnectedSensors().size }.getOrNull()
        val afterEnable = runCatching { fresh.isEnable }.getOrNull()
        log("第1次 terminate 后：newInstance=true connected=$afterConnected isEnable=$afterEnable（终止态，需重启 App）")

        var secondError: Throwable? = null
        try {
            SensorController.terminate()
        } catch (e: Exception) {
            secondError = e
        }
        check(secondError == null, "第2次 terminate 不应抛异常: ${secondError?.message}")
        "terminate idempotent, before=$before；terminate 后 controller 处于终止态（继续测试请重启 App）"
    },

    // CTRL-BND-001：startScan(period_in_ms) 边界值不崩溃 / 非法值被拒绝。
    auto("CTRL-BND-001", "startScan 边界值不崩溃、非法值被拒绝", CHAPTER, Priority.P1, 60_000) {
        // Android startScan 参数为 Int，Int.MAX_VALUE+1 无法表示（会溢出），故边界集为 5 点。
        val legal = setOf(0, 1, Int.MAX_VALUE - 1, Int.MAX_VALUE)
        val illegal = setOf(-1)

        // 基线：常规周期必须真正启动扫描，避免 startScan 恒 false 也被判过
        val baseline = safeStartScan(1_000)
        check(
            !baseline.first && baseline.second,
            "startScan(1000) 应返回 true（raised=${baseline.first} ret=${baseline.second}）",
        )
        check(controller.isScanning, "startScan(1000) 后 isScanning 应为 true")
        controller.stopScan()
        delay(300)

        (legal + illegal).forEach { value ->
            val (raised, ret) = safeStartScan(value)
            controller.stopScan()
            if (value in legal) {
                check(!raised, "合法值 startScan($value) 不应抛异常")
                log("合法值 startScan($value) -> $ret（不抛异常即通过）")
            } else {
                check(raised || !ret, "非法值 startScan($value) 应被拒绝（返回 false 或抛异常）")
                log("非法值 startScan($value) -> raised=$raised ret=$ret")
            }
        }
        "boundary values OK"
    },

    // CTRL-ROB-001：扫描期间重复 start/stop 无异常且最终 isScanning==false。
    // 对齐 Python 版 4 项判定口径：前置环境可用 + startScan 真返回 true + isScanning 真翻转，
    // 并覆盖一次「扫描进行中」，避免蓝牙关闭/终止态下「零操作 PASS」。
    auto(
        "CTRL-ROB-001", "扫描期间重复 start/stop 无异常且最终非扫描态", CHAPTER, Priority.P2,
        60_000,
    ) {
        requireScanEnvironment()

        try {
            // 基线：扫描必须真正启动并进入扫描态，否则后面 20 轮毫无意义
            val (baseRaised, baseRet) = safeStartScan(300)
            check(!baseRaised, "基线 startScan(300) 不应抛异常")
            check(baseRet, "基线 startScan(300) 应返回 true")
            check(awaitCondition({ controller.isScanning }, 2_000), "startScan 后 isScanning 应变为 true")
            delay(350) // 超过一个 300ms 周期，覆盖「扫描进行中」
            check(controller.isScanning, "扫描进行中 isScanning 应保持 true")
        } finally {
            controller.stopScan()
        }
        check(awaitCondition({ !controller.isScanning }, 2_000), "stopScan 后 isScanning 应变为 false")

        var errCount = 0
        repeat(20) { i ->
            try {
                controller.startScan(300)
            } catch (e: Exception) {
                errCount++
                log("startScan#$i 抛异常 ${e.javaClass.simpleName}")
            }
            try {
                controller.stopScan()
            } catch (e: Exception) {
                errCount++
                log("stopScan#$i 抛异常 ${e.javaClass.simpleName}")
            }
        }
        check(errCount == 0, "重复 start/stop 不应抛异常（异常数=$errCount）")
        check(!controller.isScanning, "最终 isScanning 应为 false")
        "baseline scanned, 20 rounds, no exception"
    },

    // CTRL-BND-002：getSensor/requireSensor 空串/非法 MAC 边界，未命中返回 null 且不崩溃。
    auto("CTRL-BND-002", "getSensor/requireSensor 非法 MAC 返回 null 且不崩溃", CHAPTER, Priority.P1) {
        val badMacs = listOf("", "INVALID_MAC", "AA:BB:CC:DD:EE:FF:GG", "00:00:00:00:00")
        for (mac in badMacs) {
            val r1 = try {
                controller.requireSensor(mac)
            } catch (e: Exception) {
                log("requireSensor('$mac') 抛异常 ${e.javaClass.simpleName}")
                null
            }
            val r2 = try {
                controller.getSensor(mac)
            } catch (e: Exception) {
                log("getSensor('$mac') 抛异常 ${e.javaClass.simpleName}")
                null
            }
            check(r1 == null, "requireSensor('$mac') 应返回 null")
            check(r2 == null, "getSensor('$mac') 应返回 null")
        }
        "malformed MAC rejected"
    },

    // CTRL-ROB-002：startScan/stopScan 重复调用幂等（不抛异常，最终 isScanning==false）。
    // 对齐 RN：只做 start/stop 幂等，不混入 scan/scanOnce —— scanOnce 紧随 stopScan 调用时
    // 原生结果回调不来，且 scan 的 suspend 不可取消，会永久挂起（已实测卡死）。
    // 同 ROB-001：先验证扫描真能启动/停止，避免终止态下「零操作 PASS」。
    auto(
        "CTRL-ROB-002", "startScan/stopScan 重复调用幂等", CHAPTER, Priority.P2,
        60_000,
    ) {
        requireScanEnvironment()

        try {
            val (baseRaised, baseRet) = safeStartScan(300)
            check(!baseRaised, "基线 startScan(300) 不应抛异常")
            check(baseRet, "基线 startScan(300) 应返回 true")
            check(awaitCondition({ controller.isScanning }, 2_000), "startScan 后 isScanning 应变为 true")
        } finally {
            controller.stopScan()
        }
        check(awaitCondition({ !controller.isScanning }, 2_000), "stopScan 后 isScanning 应变为 false")

        var errCount = 0
        try { controller.stopScan() } catch (e: Exception) { errCount++; log("stopScan 抛异常 ${e.javaClass.simpleName}") }
        try { controller.startScan(3_000) } catch (e: Exception) { errCount++; log("startScan 抛异常 ${e.javaClass.simpleName}") }
        try { controller.startScan(3_000) } catch (e: Exception) { errCount++; log("startScan 抛异常 ${e.javaClass.simpleName}") }
        try { controller.stopScan() } catch (e: Exception) { errCount++; log("stopScan 抛异常 ${e.javaClass.simpleName}") }
        try { controller.stopScan() } catch (e: Exception) { errCount++; log("stopScan 抛异常 ${e.javaClass.simpleName}") }
        check(errCount == 0, "重复 startScan/stopScan 不应抛异常（异常数=$errCount）")
        check(!controller.isScanning, "最终 isScanning 应为 false")
        "idempotent"
    },

    // CTRL-FUNC-014：扫描进行中 scanOnce 一次性取当前设备列表，回调返回且不崩溃。
    auto(
        "CTRL-FUNC-014", "扫描中 scanOnce 一次性返回当前设备列表", CHAPTER, Priority.P1,
        30_000,
    ) {
        val latch = CountDownLatch(1)
        val devices = CopyOnWriteArrayList<BleDevice>()
        check(controller.startScan(1_000), "startScan 返回 false")
        delay(1_500)
        controller.scanOnce(3_000) { list ->
            devices.addAll(list)
            latch.countDown()
        }
        val called = latch.await(10, TimeUnit.SECONDS)
        controller.stopScan()
        log("scanOnce 回调 called=$called，返回 ${devices.size} 台设备")
        check(called, "扫描中 scanOnce 应回调一次当前设备列表")
        "scanOnce devices=${devices.size}"
    },

    // CTRL-FUNC-015：onScanStateChange 在 startScan/stopScan 时分别回调 true/false。
    auto(
        "CTRL-FUNC-015", "onScanStateChange 起停扫描分别回调 true/false", CHAPTER, Priority.P1,
        30_000,
    ) {
        val states = CopyOnWriteArrayList<Boolean>()
        controller.setOnScanStateChangeListener { scanning, _ -> states.add(scanning) }
        check(controller.startScan(1_000), "startScan 返回 false")
        delay(1_500)
        controller.stopScan()
        delay(1_000)
        log("onScanStateChange 回调序列 = $states")
        check(states.contains(true), "startScan 应回调 scanning=true，实际=$states")
        check(states.contains(false), "stopScan 应回调 scanning=false，实际=$states")
        "states=$states"
    },

    // CTRL-FUNC-016：getCapiVersion 返回正整数（native capi 版本）。
    auto("CTRL-FUNC-016", "getCapiVersion 返回正整数", CHAPTER, Priority.P1) {
        val version = SensorController.getCapiVersion()
        log("getCapiVersion = $version（getVersion = ${controller.version}）")
        check(version > 0, "getCapiVersion 应为正整数，实际=$version")
        "capiVersion=$version"
    },
)
