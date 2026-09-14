package com.oymotion.sensorcapiktdemo.cerelax

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cerelax 10 底层接口与边界补充（对应 Manual Cases 的 MISC-*）。
 *
 * 骨架：非法 MAC、未连接时的 getDeviceInfo / init 边界行为。
 */
@RunWith(AndroidJUnit4::class)
class CerelaxBoundaryTest {

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

    /** MISC-*：getSensor 对空/非法 MAC 返回 null。 */
    @Test
    fun getSensor_invalidMac_returnsNull() {
        assertNull("空 MAC 应返回 null", controller.getSensor(""))
        assertNull("非法 MAC 应返回 null", controller.getSensor("00:00:00:00:00:00"))
    }

    /** MISC-*：未连接时 getDeviceInfo 返回 null。 */
    @Test
    fun getDeviceInfo_beforeConnect_returnsNull() {
        runBlocking {
            val device = CerelaxTestSupport.scanTarget(controller)
            assertNotNull("未扫描到目标设备", device)
            val profile = controller.requireSensor(device!!.mac)!!
            assertNull("未连接时 getDeviceInfo 应为 null", profile.getDeviceInfo())
        }
    }

    /** MISC-*：未连接时 init 返回 false。 */
    @Test
    fun init_beforeConnect_returnsFalse() {
        runBlocking {
            val device = CerelaxTestSupport.scanTarget(controller)
            assertNotNull("未扫描到目标设备", device)
            val profile = controller.requireSensor(device!!.mac)!!
            assertTrue("未连接时 init 应返回 false", !profile.init(TestConfig.PACKAGE_SAMPLE_COUNT, 5000, 60000))
        }
    }
}
