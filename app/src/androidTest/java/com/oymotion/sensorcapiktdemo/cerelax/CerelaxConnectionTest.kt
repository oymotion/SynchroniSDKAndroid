package com.oymotion.sensorcapiktdemo.cerelax

import android.Manifest
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

/**
 * Cerelax 02 连接与状态机（对应 Manual Cases 的 DEV-SM-*）。
 *
 * 已覆盖骨架：DEV-SM-001（connect→Ready）、DEV-SM-004（disconnect→Disconnected）。
 * 待补：DEV-SM-002（关机/超范围 connect 失败，semi-auto）、DEV-SM-003（不误连其它设备）、
 *       DEV-SM-005（起流中 disconnect 自动先停流）。
 */
@RunWith(AndroidJUnit4::class)
class CerelaxConnectionTest {

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

    /** DEV-SM-001：connect 成功，状态迁移最终 Ready。 */
    @Test
    fun connect_reachesReady() {
        runBlocking {
            val device = CerelaxTestSupport.scanTarget(controller)
            assertNotNull("未扫描到目标设备", device)

            val profile: SensorProfile = controller.requireSensor(device!!.mac)!!
            val states = mutableListOf<Int>()
            profile.setOnStateChangeListener { _, state -> states.add(state) }

            assertTrue("connect 失败", profile.connect())
            assertTrue("未到达 Ready", CerelaxTestSupport.waitForReady(profile))
            assertTrue("deviceState 应为 READY", profile.getDeviceState() == DeviceState.READY)
            assertTrue("应记录到状态迁移", states.isNotEmpty())

            profile.disconnect()
        }
    }

    /** DEV-SM-004：disconnect 成功，最终 Disconnected。 */
    @Test
    fun disconnect_returnsToDisconnected() {
        runBlocking {
            val device = CerelaxTestSupport.scanTarget(controller)
            assertNotNull("未扫描到目标设备", device)

            val profile = CerelaxTestSupport.connectReady(controller, device!!)
            assertNotNull("连接未就绪", profile)
            val p = profile!!

            assertTrue("disconnect 失败", p.disconnect())
            val deadline = System.currentTimeMillis() + TestConfig.READY_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && p.getDeviceState() != DeviceState.DISCONNECTED) {
                Thread.sleep(100)
            }
            assertTrue("最终状态应为 DISCONNECTED", p.getDeviceState() == DeviceState.DISCONNECTED)
        }
    }
}
