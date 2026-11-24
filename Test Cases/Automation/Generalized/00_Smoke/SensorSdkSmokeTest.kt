package com.oymotion.sensorcapiktdemo

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.oymotion.sensor.capi.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android SDK 最小冒烟骨架：
 * scan -> requireSensor -> connect -> Ready -> init -> startDataNotification -> 收到数据 -> 清理。
 *
 * 目标设备由 `Test Cases/Automation/config/config.json` 的 targetIdentity 决定，
 * 匹配走 [TestConfig.matchesTarget]（mac 精确 > 广播名后四位 identity），不写死 MAC。
 * 本骨架面向 Android 12+（API 31+）真机；若在 Android 8~11 上跑，扫描还需要
 * ACCESS_FINE_LOCATION，请在下方 GrantPermissionRule 里补上该权限。
 */
@RunWith(AndroidJUnit4::class)
class SensorSdkSmokeTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    @Test
    fun scanConnectStream_receivesData() = runBlocking<Unit> {
        val controller = SensorController.getInstance()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        controller.registerBleBridge(context)
        TestConfig.bind(context)

        // 1) scan，按 config 中的目标设备匹配
        val devices = controller.scan(TestConfig.SCAN_TIMEOUT_MS)
        val device = devices.firstOrNull { TestConfig.matchesTarget(it) }
        assertNotNull(
            "未扫描到目标设备 TARGET_IDENTITY=${TestConfig.TARGET_IDENTITY}，请确认设备开机且在范围内",
            device
        )

        // 2) requireSensor + connect + 等 Ready
        val profile = controller.requireSensor(device!!.mac)
        assertNotNull("requireSensor 返回 null", profile)
        assertTrue("connect 失败", profile!!.connect())
        assertTrue("未到达 Ready", waitForReady(profile, TestConfig.READY_TIMEOUT_MS))

        // 3) init
        assertTrue(
            "init 失败",
            profile.init(
                TestConfig.PACKAGE_SAMPLE_COUNT,
                TestConfig.INIT_TIMEOUT_MS,
                TestConfig.POWER_REFRESH_INTERVAL_MS
            )
        )

        // 4) 起流 + 等数据
        val latch = CountDownLatch(1)
        val totalSamples = AtomicInteger(0)
        profile.setOnDataListener { _, dataList ->
            if (dataList.isNotEmpty()) {
                totalSamples.addAndGet(dataList.sumOf { it.sampleCount })
                latch.countDown()
            }
        }
        assertTrue("startDataNotification 失败", profile.startDataNotification())
        assertTrue("isDataTransfering 应为 true", profile.isDataTransfering())

        val received = latch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS)
        assertTrue("${TestConfig.DATA_TIMEOUT_SEC}s 内未收到任何数据", received)
        assertTrue("收到数据但样本数为 0", totalSamples.get() > 0)

        // 5) 清理
        profile.stopDataNotification()
        profile.disconnect()
        Unit   // 让 @Test 方法返回 Unit（void），避免因 disconnect() 返回 Boolean 而类型不符
    }

    private fun waitForReady(profile: SensorProfile, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (profile.isReady()) return true
            Thread.sleep(100)
        }
        return profile.isReady()
    }
}
