package com.oymotion.sensorcapiktdemo.cerelax

import android.Manifest
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

/**
 * Cerelax 05 多设备同步（对应 Manual Cases 的 MULTI-*）。
 *
 * 骨架：扫描并连接所有命中的目标设备（上限 2 台），批量起流/停流。
 * 需多台设备同时在范围；单台也能跑通（multi 接口兼容单台）。
 */
@RunWith(AndroidJUnit4::class)
class CerelaxMultiDeviceTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    private lateinit var controller: SensorController

    @Before
    fun setUp() {
        controller = SensorController.getInstance()
        controller.registerBleBridge(ApplicationProvider.getApplicationContext())
    }

    /** MULTI-*：连接到的目标设备批量起流/停流均成功。 */
    @Test
    fun multiStartStop_onConnectedDevices() {
        runBlocking {
            val devices = controller.scan(TestConfig.SCAN_TIMEOUT_MS).filter { TestConfig.matchesTarget(it) }
            assertTrue("至少扫描到一台目标设备", devices.isNotEmpty())

            val profiles = devices.take(2).mapNotNull { CerelaxTestSupport.connectReady(controller, it) }
            assertTrue("至少一台设备连接就绪", profiles.isNotEmpty())

            profiles.forEach { it.init(TestConfig.PACKAGE_SAMPLE_COUNT, 5000, 60000) }

            val startResults = controller.multiStartDataNotification(profiles, 30000, 5, 3)
            assertTrue("multiStart 结果不应为空", startResults.isNotEmpty())
            startResults.values.forEach { assertTrue("multiStart 应成功", it) }

            val stopResults = controller.multiStopDataNotification(profiles, 10000)
            assertTrue("multiStop 结果不应为空", stopResults.isNotEmpty())
            stopResults.values.forEach { assertTrue("multiStop 应成功", it) }

            profiles.forEach { it.disconnect() }
        }
    }
}
