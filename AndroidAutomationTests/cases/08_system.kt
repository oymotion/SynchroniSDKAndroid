package com.oymotion.sensorcapiktdemo.automation

import android.app.KeyguardManager
import android.content.Context
import android.net.wifi.WifiManager
import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 与 02 章 DEV-SM-* 一致：先把状态回调挂到屏幕日志，再连接，
 * 这样「起流」过程中能看到 CONNECTING → CONNECTED → READY。
 */
private suspend fun TestContext.connectedWithStateLog(): SensorProfile {
    val profile = requireTarget()
    profile.setOnStateChangeListener { _, state ->
        log("  onStateChanged -> ${DeviceState.nameOf(state)}")
    }
    return prepare(profile)
}

/** 轮询设备状态，超时返回最终状态是否命中 [states] 之一。 */
private suspend fun SensorProfile.awaitState(timeoutMs: Long, vararg states: Int): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val current = runCatching { getDeviceState() }.getOrNull()
        if (current != null && current in states) return true
        delay(200)
    }
    val current = runCatching { getDeviceState() }.getOrNull()
    return current != null && current in states
}

/**
 * 关/开蓝牙用例的收尾：显式断开蓝牙恢复后自动重连上的连接，让设备重新广播。
 *
 * 不做这步时，蓝牙恢复后 SDK 自动重连会占住设备，设备停止广播，后续用例
 * 扫描不到目标（表现为「SYS-ROB-002 之后都连不上」）。
 */
private suspend fun TestContext.releaseAfterBluetoothToggle(p: SensorProfile) {
    p.awaitState(
        10_000,
        DeviceState.READY, DeviceState.CONNECTED, DeviceState.DISCONNECTED, DeviceState.INVALID,
    )
    val state = runCatching { p.getDeviceState() }.getOrNull()
    if (state == DeviceState.READY || state == DeviceState.CONNECTED) {
        log("蓝牙恢复后仍处于 ${DeviceState.nameOf(state)}，主动断开以恢复设备广播")
        withTimeoutOrNull(5_000) { p.disconnect() }
    }
    if (!p.awaitState(10_000, DeviceState.DISCONNECTED)) {
        val final = runCatching { DeviceState.nameOf(p.getDeviceState()) }.getOrDefault("?")
        log("警告：蓝牙恢复后未回到 DISCONNECTED（当前 $final），下一用例若扫描失败请先重启 App")
    }
}

/**
 * 弹窗等待用户执行动作期间，并发轮询 [detect]，返回是否观测到动作。
 *
 * 轮询跑在主线程：Activity 的屏幕方向 / 多窗口状态只应在主线程读取。
 * 用户没做动作时返回 false，由调用方记 SKIP（而不是 PASS/FAIL），
 * 避免「人没做」或「ROM 不支持该动作」被误判成通过或缺陷。
 */
private suspend fun TestContext.observeActionWhilePrompting(
    prompt: String,
    detect: () -> Boolean,
): Boolean = coroutineScope {
    val hit = AtomicBoolean(false)
    val watcher = launch(Dispatchers.Main) {
        while (isActive && !hit.get()) {
            if (detect()) {
                hit.set(true)
                return@launch
            }
            delay(120)
        }
    }
    try {
        promptManual(prompt)
    } finally {
        watcher.cancel()
    }
    hit.get()
}

/**
 * 起流后执行人工动作，再断言同一 profile 在动作后窗口内仍有新数据到达。
 *
 * 这是 semi-auto 场景（蓝牙重开 / WiFi / 锁屏 / 旋转 / 后台）的真正观测点：
 * 必须在上一次采样之后继续收到样本，而不是重新连一次取首包。
 *
 * [detectAction] 非空时先校验人工动作确实发生，未检测到动作记 SKIP，
 * 避免用户直接点「继续」没做动作也 PASS 的假通过。
 */
private suspend fun TestContext.verifyStreamContinuesAfter(
    actionPrompt: String,
    observeSeconds: Long = 10,
    beforeObserve: suspend (SensorProfile) -> Unit = {},
    detectAction: (() -> Boolean)? = null,
): String {
    val p = connectedWithStateLog()
    check(p.startDataNotification(), "startDataNotification 应成功")
    check(p.isDataTransfering(), "起流后 isDataTransfering 应为 true")

    val samples = AtomicInteger(0)
    p.setOnDataListener { _, list ->
        val n = list.sumOf { it.sampleCount }
        if (n > 0) samples.addAndGet(n)
    }

    // 动作前必须先有数据，否则"动作后仍有数据"没有意义
    waitFor({ samples.get() > 0 }, TestConfig.DATA_TIMEOUT_MS, "动作前首批数据")

    beforeObserve(p)
    val acted = if (detectAction != null) {
        observeActionWhilePrompting(actionPrompt, detectAction)
    } else {
        promptManual(actionPrompt)
        true
    }
    if (!acted) skip("未检测到人工动作（$actionPrompt），本条按未执行处理")

    val baseline = samples.get()
    val deadline = System.currentTimeMillis() + observeSeconds * 1000L
    var recovered = false
    while (System.currentTimeMillis() < deadline) {
        if (samples.get() > baseline) {
            recovered = true
            break
        }
        delay(500)
    }
    check(
        recovered,
        "人工动作后 ${observeSeconds}s 内未再收到数据（动作前=$baseline，动作后=${samples.get()}）",
    )
    runCatching { p.stopDataNotification() }
    return "samples=$baseline->${samples.get()}"
}

val systemCases = run {
    val chapter = "08_系统异常"
    listOf(
        // 与 01/02 章一致：每个动作前单独弹窗提示，最后一动作负责把蓝牙恢复，
        // 避免本用例把蓝牙留在关闭态、拖垮后续需要扫描的用例。
        semi("SYS-ROB-001", "扫描/连接中关闭蓝牙", chapter, Priority.P0,
            listOf(
                "确认系统蓝牙已开启、待测设备已开机且在范围内。",
                "程序先真实扫描并连接设备（到 READY 才算连接完成）。",
                "连接完成后请【关闭手机/平板】蓝牙（不是待测设备）。",
                "请【开启手机/平板】蓝牙以恢复，程序会自动断开并释放设备。",
            ),
            timeoutMs = 180_000) {
            promptManual(
                "① 请确认【系统蓝牙已开启】、待测设备【已开机】且在范围内，完成后点「我已执行，继续」。" +
                    "程序会先真实扫描并连接设备。",
            )
            // 前置断言：动作前蓝牙必须是开启态。否则后面的 waitFor(!isEnable) 会瞬间满足，
            // 用例在完全没有「扫描/连接中关蓝牙」的情况下假 PASS。
            check(controller.isEnable, "动作前系统蓝牙应开启（isEnable=false），无法构造本场景")
            // 真实 scan + connect + init，保证「连接中」确实发生过
            val p = connectedWithStateLog()

            promptManual("② 设备已连接（READY），请【关闭手机/平板】蓝牙（不是待测设备）。关闭后点「我已执行，继续」。")
            // 系统蓝牙关闭后 isEnable 异步回传（约 1~2s），轮询等待而不是固定延时
            waitFor({ !controller.isEnable }, 15_000, "关闭蓝牙后 isEnable=false")

            promptManual("③ 请【开启手机/平板】蓝牙以恢复。开启后点「我已执行，继续」。")
            waitFor({ controller.isEnable }, 15_000, "恢复后 isEnable=true")
            // 恢复后主动断开自动重连，避免设备停止广播导致后续用例扫描不到
            releaseAfterBluetoothToggle(p)
            "isEnable=true->false->true"
        },
        semi("SYS-ROB-002", "起流中关闭蓝牙后停止", chapter, Priority.P0,
            listOf(
                "程序会连接并起流。",
                "关闭系统蓝牙后点击验证，检查起流是否停止。",
                "随后必须重新开启系统蓝牙，避免后续用例无法扫描。",
            ),
            timeoutMs = 180_000) {
            promptManual("① 程序即将连接并起流。请确认【系统蓝牙已开启】、待测设备【已开机】且在范围内，点「我已执行，继续」。")
            val p = connectedWithStateLog()
            check(p.startDataNotification(), "startDataNotification 应成功")
            check(p.isDataTransfering(), "起流后 isDataTransfering 应为 true")

            val states = CopyOnWriteArrayList<Int>()
            val errors = CopyOnWriteArrayList<String>()
            p.setOnStateChangeListener { _, state ->
                states.add(state)
                log("  onStateChanged -> ${DeviceState.nameOf(state)}")
            }
            p.setOnErrorListener { _, error ->
                errors.add(error)
                log("  onError -> $error")
            }

            promptManual("② 请【关闭手机/平板】蓝牙（不是待测设备），完成后点「我已执行，继续」。程序最多等 60s 起流停止。")
            waitFor({ !p.isDataTransfering() }, 60_000, "关闭蓝牙后应停止起流（若未真正关闭蓝牙，本条判失败）")
            // 因果证据：流停止必须是「关蓝牙」引起的。不同设备回调行为不同，以下任一即可：
            // 系统蓝牙已关闭 / 收到 DISCONNECTED / 收到错误回调。
            val deadline = System.currentTimeMillis() + 5_000
            while (controller.isEnable && System.currentTimeMillis() < deadline) delay(200)
            val evidence = !controller.isEnable ||
                states.contains(DeviceState.DISCONNECTED) || errors.isNotEmpty()
            val evidenceText = if (!controller.isEnable) "isEnable=false" else "state/error"
            // 先恢复蓝牙再断言：即使下面判定失败，也不能把系统蓝牙留在关闭态拖垮后续用例。
            promptManual(
                "③ 请【开启手机/平板】蓝牙以恢复（不恢复会导致后续用例无法扫描），完成后点「我已执行，继续」。",
            )
            waitFor({ controller.isEnable }, 15_000, "恢复后 isEnable=true")
            releaseAfterBluetoothToggle(p)

            check(
                evidence,
                "起流已停止，但未观测到蓝牙关闭/断连/错误证据" +
                    "（states=${states.map { DeviceState.nameOf(it) }} errors=$errors）",
            )
            "stream stopped, evidence=$evidenceText"
        },
        semi("SYS-ROB-003", "蓝牙重开后自动恢复", chapter, Priority.P0,
            steps = listOf(
                "确认待测设备已开机且在范围内；并确认系统蓝牙已开启（若上一条异常中断可能仍为关闭态）。",
                "程序自动连接、init 并起流。",
                "关闭系统蓝牙（数据中断属正常），确认流已停止。",
                "重新开启系统蓝牙，等待设备恢复。",
                "程序观察 60s 内是否恢复出数。",
            ),
            timeoutMs = 180_000) {
            // 提示必须在连接前给出：若上一条被中断、蓝牙仍关闭，requireTarget 扫描会直接失败、
            // 后续弹窗根本不会出现。
            promptManual("① 请确认【系统蓝牙已开启】（若上一条异常中断可能仍为关闭态）、待测设备【已开机】且在范围内。完成后点「我已执行，继续」，程序会自动连接、init 并起流。")
            verifyStreamContinuesAfter(
                "③ 请【重新开启系统蓝牙】，待蓝牙开启且设备恢复后点「我已执行，继续」，程序将观察 60s 内是否恢复出数。",
                observeSeconds = 60,
                beforeObserve = { p ->
                    promptManual("② 请【关闭系统蓝牙】。关闭期间数据中断属正常现象，关闭后点「我已执行，继续」。")
                    waitFor({ !p.isDataTransfering() }, 60_000, "关闭蓝牙后应停止出数")
                },
            )
        },
        semi("SYS-ROB-004", "WiFi 开关不影响 BLE 数据", chapter,
            steps = listOf(
                "起流后反复开关 WiFi（至少切换一次状态）。",
                "完成后点击验证；未检测到 WiFi 状态切换则记 SKIP。",
            )) {
            val wifi = androidContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: skip("设备无 WiFi 服务，无法执行本用例")
            // 读 WiFi 状态需要 ACCESS_WIFI_STATE；缺失时记 SKIP 而不是 FAIL
            val initialWifi = runCatching { wifi.isWifiEnabled }.getOrElse {
                skip("无法读取 WiFi 状态（${it.javaClass.simpleName}: ${it.message}），无法判定动作")
            }
            verifyStreamContinuesAfter(
                "起流中请反复开关 WiFi（至少切换一次状态），完成后点「我已执行，继续」。程序会自动检测是否真的切换过。",
                detectAction = { runCatching { wifi.isWifiEnabled }.getOrDefault(initialWifi) != initialWifi },
            )
        },
        semi("SYS-ROB-005", "App 后台/前台与 onSuspend", chapter, Priority.P0,
            listOf(
                "起流中切到后台（桌面或其他 App）再回前台。",
                "分屏不算切后台；未检测到 onStop 则记 SKIP。",
            )) {
            // 只认「宿主 Activity 真的走了 onStop」：切分屏不算切后台，避免动作判定失真
            val stopsBefore = hostSignals.backgroundStops()
            verifyStreamContinuesAfter(
                "请将 App 切到后台（回到桌面或切到其他 App，分屏不算）再回到前台，完成后点「我已执行，继续」。",
                beforeObserve = { _ -> controller.onSuspend() },
                detectAction = { hostSignals.backgroundStops() > stopsBefore },
            )
        },
        semi("SYS-ROB-006", "锁屏/解锁后数据恢复", chapter,
            steps = listOf(
                "锁屏数秒后解锁（锁屏期间数据中断属正常）。",
                "完成后点击验证；未检测到锁屏则记 SKIP。",
            )) {
            val keyguard = androidContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                ?: skip("设备无 KeyguardManager，无法执行本用例")
            verifyStreamContinuesAfter(
                "请锁屏数秒后解锁（程序会自动检测是否真的进入过锁屏），完成后点「我已执行，继续」。",
                detectAction = { keyguard.isKeyguardLocked },
            )
        },
        semi("SYS-ROB-007", "旋转/分屏后数据恢复", chapter,
            steps = listOf(
                "旋转屏幕或进入分屏再切回（系统关闭自动旋转时请直接进分屏）。",
                "完成后点击验证；未检测到方向/分屏变化则记 SKIP。",
            )) {
            // 旋转（屏幕方向变化）或进入/退出分屏（多窗口状态变化）任一发生即算动作
            val startOrientation = hostSignals.orientation()
            val startMultiWindow = hostSignals.inMultiWindow()
            verifyStreamContinuesAfter(
                "请旋转屏幕或进入分屏再切回（若系统关闭了自动旋转，请直接进分屏），完成后点「我已执行，继续」。",
                detectAction = {
                    hostSignals.orientation() != startOrientation ||
                        hostSignals.inMultiWindow() != startMultiWindow
                },
            )
        },
        manual("SYS-ROB-008", "权限撤销与恢复", chapter, Priority.P0,
            listOf("撤销 BLUETOOTH_SCAN/BLUETOOTH_CONNECT。", "确认扫描/连接被拒绝且 App 不崩溃。", "重新授权并确认恢复。")),
        manual("SYS-ROB-009", "来电/通知/系统弹窗打断", chapter, Priority.P2,
            listOf("起流中制造来电、通知横幅或系统弹窗。", "确认数据持续或恢复，App 无崩溃。")),
    )
}
