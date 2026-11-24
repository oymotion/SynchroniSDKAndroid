package com.oymotion.sensorcapiktdemo.cerelaxpro

import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Cerelax 测试公共辅助，对应 Python 侧测试框架的公共 fixture：
 * 扫描目标设备、连接并等待 Ready、轮询状态，以及 semi-auto 用例的人工操作等待窗口。
 *
 * 说明（Android 与 Python 的差异）：
 *   - Python 用 input() 让测试人员"按回车继续"；Android instrumentation 无 stdin，
 *     因此 semi-auto 用例用 [pauseForManual]：打印人工操作提示 -> 睡眠固定窗口，
 *     测试人员在窗口内完成开关机/切换蓝牙等操作。
 *   - 输出统一走 System.out（logcat 的 System.out 标签），用 `adb logcat -s System.out` 查看。
 */
object CerelaxTestSupport {

    /**
     * Android `parseBinToCsv(binPath, csvPath, OnParamListener)` 是回调式接口，
     * 这里包成挂起函数返回结果字符串（与 Python 同步返回语义对齐）。
     *
     * 回调语义（AAR 1.4.x）：`onParam(result, error)`，error 非空即失败，否则 result 有效；
     * suspend 版 `parseBinToCsvAsync` 失败时直接抛 IOException。这里统一成不抛异常：
     * 成功返回结果串，失败返回 "Error: ..."，便于用例用字符串断言。
     */
    suspend fun parseBinToCsv(controller: SensorController, binPath: String, csvPath: String): String =
        withTimeout(60_000) {
            suspendCancellableCoroutine { cont ->
                controller.parseBinToCsv(binPath, csvPath) { result, error ->
                    if (cont.isActive) {
                        val ok = result.orEmpty()
                        val text = when {
                            !error.isNullOrEmpty() -> "Error: $error"
                            ok.startsWith("Error") || ok.startsWith("ERROR:") -> ok
                            else -> ok
                        }
                        cont.resume(text)
                    }
                }
            }
        }

    /** 单次扫描，返回第一个命中本轮目标的设备（mac 精确 > 广播名后四位 identity）。 */
    suspend fun scanTarget(controller: SensorController): BleDevice? =
        controller.scan(TestConfig.SCAN_TIMEOUT_MS).firstOrNull { TestConfig.matchesTarget(it) }

    /**
     * 扫描目标设备，失败时按固定间隔重试（对应 Python common.scan_and_match）。
     * @param retries 额外重试次数（总共最多 retries+1 次扫描）。
     */
    suspend fun scanTargetWithRetry(
        controller: SensorController,
        retries: Int = 3,
        intervalMs: Long = TestConfig.SCAN_RETRY_INTERVAL_MS
    ): BleDevice? {
        repeat(retries + 1) { attempt ->
            val hit = scanTarget(controller)
            if (hit != null) return hit
            if (attempt < retries) {
                log("未匹配到目标设备，${intervalMs}ms 后重扫（${attempt + 1}/$retries）...")
                kotlinx.coroutines.delay(intervalMs)
            }
        }
        return null
    }

    /** 轮询等待设备进入 Ready。 */
    fun waitForReady(profile: SensorProfile, timeoutMs: Long = TestConfig.READY_TIMEOUT_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (profile.isReady()) return true
            Thread.sleep(100)
        }
        return profile.isReady()
    }

    /** 轮询等待设备进入指定状态（对应 Python 的状态等待，避免固定 sleep 错过）。 */
    fun waitForState(
        profile: SensorProfile,
        target: Int,
        timeoutMs: Long = TestConfig.READY_TIMEOUT_MS
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (profile.getDeviceState() == target) return true
            Thread.sleep(100)
        }
        return profile.getDeviceState() == target
    }

    /** requireSensor + connect + 等 Ready；任一步失败返回 null。 */
    suspend fun connectReady(controller: SensorController, device: BleDevice): SensorProfile? {
        val profile = controller.requireSensor(device.mac) ?: return null
        if (!profile.connect()) return null
        return if (waitForReady(profile)) profile else null
    }

    /**
     * semi-auto 用例的人工操作等待窗口：打印操作提示 -> 睡眠 [seconds] 秒。
     * 测试人员需在窗口内完成人工操作（如开关蓝牙/开关机），测试随后继续断言。
     */
    fun pauseForManual(message: String, seconds: Long = TestConfig.MANUAL_PAUSE_SECONDS) {
        val line = "=".repeat(60)
        log(line)
        log("[人工操作] $message")
        log("请在 ${seconds}s 内完成上述操作，测试将在 ${seconds}s 后继续 ...")
        log(line)
        Thread.sleep(seconds * 1000)
    }

    /** 统一日志出口（System.out -> logcat 的 System.out 标签）。 */
    fun log(message: String) = println(message)
}
