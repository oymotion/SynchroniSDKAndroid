package com.oymotion.sensorcapiktdemo.cerelaxpro

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cerelax 长稳测试：
 * 长时间起流 + bin 落盘 + debug log，验证长稳期间数据流不中断、结束后 bin 可解析。
 *
 * 退出条件（与 Python 一致，满足其一即停）：
 *   1) 电量 < [LOW_BATTERY_THRESHOLD]
 *   2) 运行时长 >= [STREAM_SECONDS]
 *   3) 数据流/设备状态异常，且 [RECONNECT_GRACE_SECONDS] 宽限期内未恢复
 *   4) 连续 [STALL_CHECK_CYCLES] 个周期 batches 无增长
 *
 * Android 与 Python 差异：
 *   - Python bumble USB dongle / `checkSetupDongle` -> Android 无后端切换，不迁移。
 *   - Python `onPowerChanged`/`onErrorCallback`/`onAutoReconnect` ->
 *     Android `setOnPowerListener`/`setOnErrorListener`/`setOnAutoReconnectListener` + `setAutoReconnect(true)`。
 *   - 时长用 [TestConfig.LONGEVITY_STREAM_SECONDS]（默认 30s），正式长稳可调大。
 */
@RunWith(AndroidJUnit4::class)
class CerelaxLongevityTest {

    companion object {
        private val STREAM_SECONDS = TestConfig.LONGEVITY_STREAM_SECONDS  // Python: 5 小时
        private const val CHECK_INTERVAL = 10L                                  // Python: 60s
        private const val LOW_BATTERY_THRESHOLD = 5
        private const val RECONNECT_GRACE_SECONDS = 30L
        private const val STALL_CHECK_CYCLES = 3
    }

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    private lateinit var controller: SensorController
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val logDir: File by lazy { File(context.cacheDir, "sdklog").apply { mkdirs() } }

    @Before
    fun setUp() {
        controller = SensorController.getInstance()
        controller.registerBleBridge(context)
        TestConfig.bind(context)
        runBlocking {
            controller.setParam("LOG_PATH", logDir.absolutePath)
            controller.setParam("DEBUG_ENABLED", "True")
        }
    }

    private fun findBins(): List<File> =
        logDir.listFiles()?.filter { it.name.lowercase().endsWith(".bin") }?.sortedBy { it.lastModified() }
            ?: emptyList()

    @Test(timeout = 300000)
    fun longevity_longStream_binAndLog() = runBlocking<Unit> {
        assertTrue("前置条件不满足：蓝牙未开启（isEnable == false）", controller.isEnable())
        val binsBefore = findBins().map { it.absolutePath }.toSet()

        // scan -> requireSensor
        val device = CerelaxTestSupport.scanTargetWithRetry(controller) ?: throw AssertionError(
            "未匹配到目标设备（TARGET_IDENTITY=${TestConfig.TARGET_IDENTITY}）"
        )
        val p = controller.requireSensor(device.mac) ?: throw AssertionError("requireSensor 返回 null")

        // 自动重连（异常断连后交给 SDK 默认恢复流程）
        p.setAutoReconnect(true)
        p.setOnAutoReconnectListener { _, restore, answer ->
            CerelaxTestSupport.log("[重连] restore=$restore")
            answer.answer(false) // 不接管恢复，让 SDK 走默认流程
        }
        p.setOnErrorListener { _, reason -> CerelaxTestSupport.log("[ERROR] $reason") }

        // connect -> Ready -> init
        assertTrue("connect 失败", p.connect())
        assertTrue("未到达 Ready", CerelaxTestSupport.waitForReady(p))
        assertTrue(
            "init 失败",
            p.init(TestConfig.PACKAGE_SAMPLE_COUNT, TestConfig.INIT_TIMEOUT_MS, TestConfig.POWER_REFRESH_INTERVAL_MS)
        )
        assertTrue("init 后 hasInited 应为 true", p.hasInited())

        // 采样率信息（默认采样率，本测试不设置）
        val info = p.getDeviceInfo()
        if (info != null) {
            CerelaxTestSupport.log(
                "[采样率] eeg=${info.eegSampleRate} ppg=${info.ppgSampleRate} imu=${info.imuSampleRate}"
            )
        }
        CerelaxTestSupport.log("[采样率] EEG_SAMPLE_RATE=${p.getParam("EEG_SAMPLE_RATE")}")

        // 开启 profile 日志与 bin 导出
        try { p.setParam("DEBUG_LOG_PATH", "True") } catch (_: Exception) {}
        try { p.setParam("DEBUG_BLE_DATA_PATH", "True") } catch (_: Exception) {}

        // 回调：数据计数
        val batches = AtomicInteger(0)
        val samples = AtomicInteger(0)
        val calls = AtomicInteger(0)
        val lost = AtomicInteger(0)
        p.setOnDataListener { _, dataList ->
            calls.incrementAndGet()
            for (d in dataList) {
                batches.incrementAndGet()
                samples.addAndGet(d.sampleCount)
                if (d.lostPackageCount > 0) lost.addAndGet(d.lostPackageCount)
            }
        }

        // 起流
        assertTrue("startDataNotification 失败", p.startDataNotification())
        assertTrue("起流后 isDataTransfering 应为 true", p.isDataTransfering())

        // ---- 长稳循环 ----
        val start = System.currentTimeMillis()
        var exitReason = ""
        var lastBatches = -1
        var stallCycles = 0
        var dropStartedAt: Long? = null

        while (true) {
            val elapsed = (System.currentTimeMillis() - start) / 1000
            val power = try { p.getBatteryLevel() } catch (_: Exception) { -1 }

            // 退出 1：低电量
            if (power in 0 until LOW_BATTERY_THRESHOLD) {
                exitReason = "电量 $power% < $LOW_BATTERY_THRESHOLD%"
                break
            }
            // 退出 2：达到最小运行时长
            if (elapsed >= STREAM_SECONDS) {
                exitReason = "达到最小运行时长 ${STREAM_SECONDS}s"
                break
            }

            // 退出 3：数据流/设备状态异常（宽限期等待自动重连）
            val transferring = p.isDataTransfering()
            val ready = p.getDeviceState() == DeviceState.READY
            if (!transferring || !ready) {
                if (dropStartedAt == null) dropStartedAt = System.currentTimeMillis()
                val graceElapsed = (System.currentTimeMillis() - dropStartedAt!!) / 1000
                if (graceElapsed >= RECONNECT_GRACE_SECONDS) {
                    exitReason = "数据流中断且 ${RECONNECT_GRACE_SECONDS}s 内未恢复"
                    break
                }
                Thread.sleep(5000)
                continue
            }

            // 退出 4：数据停滞
            val b = batches.get()
            if (lastBatches >= 0 && b == lastBatches) {
                stallCycles++
                if (stallCycles >= STALL_CHECK_CYCLES) {
                    exitReason = "数据停滞：连续 $STALL_CHECK_CYCLES 个周期 batches 无增长（batches=$b）"
                    break
                }
            } else {
                stallCycles = 0
            }
            lastBatches = b

            CerelaxTestSupport.log(
                "[状态] ${elapsed}s 电量=$power 数据=${b}批/${samples.get()}样本 回调=${calls.get()}次 丢包=${lost.get()}包"
            )
            Thread.sleep(CHECK_INTERVAL * 1000L)
        }

        CerelaxTestSupport.log("[退出] 原因: $exitReason  总运行时长 ${(System.currentTimeMillis() - start) / 1000}s")

        // ---- 收尾 ----
        try { p.stopDataNotification() } catch (_: Exception) {}
        try { p.disconnect() } catch (_: Exception) {}

        val deadline = System.currentTimeMillis() + 3000
        var bin: String? = null
        while (System.currentTimeMillis() < deadline) {
            bin = findBins().lastOrNull { it.absolutePath !in binsBefore }?.absolutePath
            if (bin != null) break
            Thread.sleep(200)
        }

        try { p.setParam("DEBUG_BLE_DATA_PATH", "False") } catch (_: Exception) {}
        try { p.setParam("DEBUG_LOG_PATH", "False") } catch (_: Exception) {}
        controller.setParam("DEBUG_ENABLED", "False")

        // ---- 退出原因判定 ----
        // 只有"跑满时长"算通过；低电量无法完成长稳记 SKIP；断流/停滞必须 FAIL。
        if (exitReason.startsWith("电量")) {
            org.junit.Assume.assumeTrue("设备电量过低，长稳无法完成：$exitReason", false)
        }
        assertTrue(
            "长稳未跑满 ${STREAM_SECONDS}s 即退出：$exitReason",
            exitReason == "达到最小运行时长 ${STREAM_SECONDS}s",
        )

        // ---- 判定（对应 Python verdict：bin 存在 + 有效 + 数据已收）----
        assertNotNull("长稳起流后应生成 .bin 文件", bin)
        assertTrue("生成的 bin 文件应存在: $bin", File(bin!!).exists())
        val binInfo = controller.getBinFileInfo(bin)
        assertNotNull("bin 应可解析（getBinFileInfo 非 null）", binInfo)
        assertTrue("bin valid 应为 true", binInfo!!.valid)
        assertTrue("长稳期间应收到数据（样本数 > 0）", samples.get() > 0)

        CerelaxTestSupport.log(
            "Longevity PASS：exitReason=$exitReason  bin=$bin  durationSec=${binInfo.durationSec}"
        )
    }
}
