package com.oymotion.sensorcapiktdemo.automation

import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * 起流后执行人工动作，再断言同一 profile 在动作后窗口内仍有新数据到达。
 *
 * 这是 semi-auto 场景（蓝牙重开 / WiFi / 锁屏 / 旋转 / 后台）的真正观测点：
 * 必须在上一次采样之后继续收到样本，而不是重新连一次取首包。
 */
private suspend fun TestContext.verifyStreamContinuesAfter(
    actionPrompt: String,
    observeSeconds: Long = 10,
    beforeObserve: suspend () -> Unit = {},
): String {
    val p = connected()
    check(p.startDataNotification(), "startDataNotification 应成功")
    check(p.isDataTransfering(), "起流后 isDataTransfering 应为 true")

    val samples = AtomicInteger(0)
    p.setOnDataListener { _, list ->
        val n = list.sumOf { it.sampleCount }
        if (n > 0) samples.addAndGet(n)
    }

    // 动作前必须先有数据，否则"动作后仍有数据"没有意义
    waitFor({ samples.get() > 0 }, TestConfig.DATA_TIMEOUT_MS, "动作前首批数据")

    beforeObserve()
    promptManual(actionPrompt)

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
        semi("SYS-ROB-001", "扫描/连接中关闭蓝牙", chapter, Priority.P0,
            listOf("关闭系统蓝牙。", "点击验证。")) {
            delay(1_000)
            check(!controller.isEnable, "isEnable 仍为 true")
            "bluetooth disabled"
        },
        semi("SYS-ROB-002", "起流中关闭蓝牙后停止", chapter, Priority.P0,
            listOf("程序会连接并起流。", "关闭系统蓝牙后点击验证，检查起流是否停止。"),
            timeoutMs = 120_000) {
            promptManual("① 程序即将连接并起流。请确认待测设备【已开机】且在范围内，点「我已执行，继续」。")
            val p = connected()
            check(p.startDataNotification(), "startDataNotification 应成功")
            check(p.isDataTransfering(), "起流后 isDataTransfering 应为 true")

            promptManual("② 请【关闭系统蓝牙】，完成后点「我已执行，继续」。")
            waitFor({ !p.isDataTransfering() }, 60_000, "蓝牙关闭后应停止起流")
            "stream stopped"
        },
        semi("SYS-ROB-003", "蓝牙重开后自动恢复", chapter, Priority.P0,
            listOf("起流后关闭再开启系统蓝牙。", "等待设备恢复后点击验证。"), 180_000) {
            verifyStreamContinuesAfter(
                "请【关闭系统蓝牙】再【重新开启】，等待设备恢复广播后点「我已执行，继续」。",
                observeSeconds = 60,
            )
        },
        semi("SYS-ROB-004", "WiFi 开关不影响 BLE 数据", chapter,
            steps = listOf("起流后反复开关 WiFi。", "完成后点击验证。")) {
            verifyStreamContinuesAfter(
                "起流中请反复开关 WiFi，完成后点「我已执行，继续」。",
            )
        },
        semi("SYS-ROB-005", "App 后台/前台与 onSuspend", chapter, Priority.P0,
            listOf("起流中切后台再回前台。", "点击验证。")) {
            verifyStreamContinuesAfter(
                "请将 App 切到后台再回到前台，完成后点「我已执行，继续」。",
                beforeObserve = { controller.onSuspend() },
            )
        },
        semi("SYS-ROB-006", "锁屏/解锁后数据恢复", chapter,
            steps = listOf("锁屏数秒后解锁。", "点击验证。")) {
            verifyStreamContinuesAfter(
                "请锁屏数秒后解锁，完成后点「我已执行，继续」。",
            )
        },
        semi("SYS-ROB-007", "旋转/分屏后数据恢复", chapter,
            steps = listOf("旋转屏幕或进入分屏再切回。", "点击验证。")) {
            verifyStreamContinuesAfter(
                "请旋转屏幕或进入分屏再切回，完成后点「我已执行，继续」。",
            )
        },
        manual("SYS-ROB-008", "权限撤销与恢复", chapter, Priority.P0,
            listOf("撤销 BLUETOOTH_SCAN/BLUETOOTH_CONNECT。", "确认扫描/连接被拒绝且 App 不崩溃。", "重新授权并确认恢复。")),
        manual("SYS-ROB-009", "来电/通知/系统弹窗打断", chapter, Priority.P2,
            listOf("起流中制造来电、通知横幅或系统弹窗。", "确认数据持续或恢复，App 无崩溃。")),
    )
}
