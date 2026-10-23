package com.oymotion.sensorcapiktdemo.cerelaxpro

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cerelax 压力测试（对应 Python gForceUltra/stress_connect_stream.py 1:1 迁移）：
 * 反复「连接 → 长时间起流 → 断开」，尝试复现"长时间起流后设备状态异常导致下次起流失败"。
 *
 * 判定口径（与 Python 一致）：任一轮 `startDataNotification` 返回 false，或起流期间
 * `deviceState` 离开 Ready，即判该轮失败并停止。
 *
 * Android 与 Python 差异：
 *   - Python `bumble` USB dongle / `checkSetupDongle` -> Android 无后端切换，不迁移。
 *   - Python `onPowerChanged`/`onErrorCallback` -> Android `setOnPowerListener`/`setOnErrorListener`。
 *   - Python `setLogPath`/`setDebugEnabled` -> Android `controller.setParam("LOG_PATH"/"DEBUG_ENABLED")`。
 *
 * 参数为便于单次跑通已调小（Python 为 50 轮 × 90s）；正式压力可调回。
 */
@RunWith(AndroidJUnit4::class)
class CerelaxStressTest {

    companion object {
        private const val MAX_ROUNDS = 3       // Python: 50
        private const val STREAM_SECONDS = 10  // Python: 90
        private const val CHECK_INTERVAL = 5   // Python: 30
        private const val ROUND_REST_MS = 2000L
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
        controller.setParam("LOG_PATH", logDir.absolutePath)
        controller.setParam("DEBUG_ENABLED", "True")
    }

    /** 执行一轮「连接-起流-断开」。返回 null 表示成功，否则返回失败原因。 */
    private suspend fun oneRound(round: Int): String? {
        CerelaxTestSupport.log("---- 第 $round/$MAX_ROUNDS 轮 ----")

        // scan -> requireSensor
        val device = CerelaxTestSupport.scanTargetWithRetry(controller)
            ?: return "未匹配到目标设备（TARGET_IDENTITY=${TestConfig.TARGET_IDENTITY}）"
        val p = controller.requireSensor(device.mac) ?: return "requireSensor 返回 null"

        p.setOnErrorListener { _, reason -> CerelaxTestSupport.log("  [onError] $reason") }

        // connect -> Ready -> init
        if (!p.connect()) return "connect 返回 false"
        if (!CerelaxTestSupport.waitForReady(p)) {
            try { p.disconnect() } catch (_: Exception) {}
            return "未到达 Ready"
        }
        if (!p.init(TestConfig.PACKAGE_SAMPLE_COUNT, TestConfig.INIT_TIMEOUT_MS, TestConfig.POWER_REFRESH_INTERVAL_MS)) {
            try { p.disconnect() } catch (_: Exception) {}
            return "init 返回 false"
        }

        // 回调：电量（命令通道心跳）+ 数据（数据流心跳）
        val powerCount = AtomicInteger(0)
        val latestLevel = AtomicInteger(-1)
        p.setOnPowerListener { _, level ->
            powerCount.incrementAndGet()
            latestLevel.set(level)
        }
        val dataHeartbeat = AtomicBoolean(false)
        p.setOnDataListener { _, dataList ->
            if (dataList.isNotEmpty()) dataHeartbeat.set(true)
        }

        // 开启 bin 录制（仅用于出错定位，不参与判定）
        try { p.setParam("DEBUG_BLE_DATA_PATH", "True") } catch (e: Exception) {
            CerelaxTestSupport.log("  [bin] setParam 抛异常 ${e.javaClass.simpleName}")
        }

        // 起流；返回 false 立即判失败（对应 Python 第 7 步）
        val started: Boolean = try {
            p.startDataNotification()
        } catch (e: Exception) {
            CerelaxTestSupport.log("  [起流] 抛异常 ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        if (!started) {
            try { p.disconnect() } catch (_: Exception) {}
            return "startDataNotification 返回 false"
        }

        // 长时间起流：定期输出电量，并监测 deviceState 是否离开 Ready
        CerelaxTestSupport.log("  [起流] 持续 ${STREAM_SECONDS}s，每 ${CHECK_INTERVAL}s 输出电量 ...")
        val start = System.currentTimeMillis()
        var lastCheck = start
        while (System.currentTimeMillis() - start < STREAM_SECONDS * 1000L) {
            if (System.currentTimeMillis() - lastCheck >= CHECK_INTERVAL * 1000L) {
                lastCheck = System.currentTimeMillis()
                val elapsed = (System.currentTimeMillis() - start) / 1000
                CerelaxTestSupport.log(
                    "  [电量] ${elapsed}s 回调=${powerCount.get()} 当前电量=${latestLevel.get()}"
                )
            }
            // 一旦离开 Ready（复位/断连）立即记录本轮失败
            if (p.getDeviceState() != DeviceState.READY) {
                val state = p.getDeviceState()
                try { p.stopDataNotification() } catch (_: Exception) {}
                try { p.disconnect() } catch (_: Exception) {}
                return "起流期间 deviceState 离开 Ready（state=$state）"
            }
            Thread.sleep(1000)
        }

        // 停流 + 断开
        try { p.stopDataNotification() } catch (_: Exception) {}
        try { p.disconnect() } catch (_: Exception) {}

        CerelaxTestSupport.log(
            "  [OK] 第 $round 轮完成（电量回调=${powerCount.get()}，数据心跳=${dataHeartbeat.get()}）"
        )
        return null
    }

    @Test(timeout = 600000)
    fun stress_connectStreamRepeatedly() = runBlocking {
        assertTrue("前置条件不满足：蓝牙未开启（isEnable == false）", controller.isEnable())

        CerelaxTestSupport.log("压力测试开始：$MAX_ROUNDS 轮 × ${STREAM_SECONDS}s/轮")

        var failRound: Int? = null
        var failReason: String? = null
        for (round in 1..MAX_ROUNDS) {
            val err = oneRound(round)
            if (err != null) {
                failRound = round
                failReason = err
                break
            }
            Thread.sleep(ROUND_REST_MS)
        }

        controller.setParam("DEBUG_ENABLED", "False")
        if (failRound != null) {
            CerelaxTestSupport.log("状态异常发生在第 $failRound 轮: $failReason")
        } else {
            CerelaxTestSupport.log("全部 $MAX_ROUNDS 轮通过，未复现异常")
        }
        assertTrue("压力测试在第 $failRound 轮失败: $failReason", failRound == null)
    }
}
