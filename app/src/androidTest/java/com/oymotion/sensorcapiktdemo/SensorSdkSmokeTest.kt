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
 * 运行前把 TARGET_MAC 改成你的设备 MAC（大写、含冒号）。
 * 本骨架面向 Android 12+（API 31+）真机；若在 Android 8~11 上跑，扫描还需要
 * ACCESS_FINE_LOCATION，请在下方 GrantPermissionRule 里补上该权限。
 */
@RunWith(AndroidJUnit4::class)
class SensorSdkSmokeTest {

    companion object {
        // TODO: 改成你的设备 MAC（大写、含冒号）
        private const val TARGET_MAC = "78:1C:9D:E4:6C:6B"
        private const val SCAN_TIMEOUT_MS = 8000
        private const val READY_TIMEOUT_MS = 15000L
        private const val DATA_TIMEOUT_SEC = 10L
    }

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    @Test
    fun scanConnectStream_receivesData() = runBlocking {
        val controller = SensorController.getInstance()
        controller.registerBleBridge(ApplicationProvider.getApplicationContext())

        // 1) scan，按 MAC 匹配
        val devices = controller.scan(SCAN_TIMEOUT_MS)
        val device = devices.firstOrNull { it.mac.equals(TARGET_MAC, ignoreCase = true) }
        assertNotNull("未扫描到目标设备 $TARGET_MAC，请确认设备开机且在范围内", device)

        // 2) requireSensor + connect + 等 Ready
        val profile = controller.requireSensor(device!!.mac)
        assertNotNull("requireSensor 返回 null", profile)
        assertTrue("connect 失败", profile!!.connect())
        assertTrue("未到达 Ready", waitForReady(profile, READY_TIMEOUT_MS))

        // 3) init
        assertTrue("init 失败", profile.init(32, 10000, 60000))

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

        val received = latch.await(DATA_TIMEOUT_SEC, TimeUnit.SECONDS)
        assertTrue("${DATA_TIMEOUT_SEC}s 内未收到任何数据", received)
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
