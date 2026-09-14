package com.oymotion.sensorcapiktdemo.cerelax

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.oymotion.sensor.capi.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cerelax 07 电量/日志/调试（对应 Manual Cases 的 BATT 与 LOG）。
 *
 * 已覆盖骨架：getBatteryLevel 电量范围、log 接口可调用。
 * 待补：DEBUG_BLE_DATA_PATH / DEBUG_LOG_PATH 路径开关、SDK 日志落地核对。
 */
@RunWith(AndroidJUnit4::class)
class CerelaxBatteryLogTest {

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

    /** BATT-*：连接就绪后读取电量应在 0..100。 */
    @Test
    fun getBatteryLevel_validRange() {
        runBlocking {
            val device = CerelaxTestSupport.scanTarget(controller)
            assertNotNull("未扫描到目标设备", device)
            val profile = CerelaxTestSupport.connectReady(controller, device!!)
            assertNotNull("连接未就绪", profile)
            val p = profile!!

            val level = p.getBatteryLevel()
            assertTrue("电量应在 0..100：$level", level in 0..100)

            p.disconnect()
        }
    }

    /** LOG-*：SDK 日志接口可调用、不抛异常。 */
    @Test
    fun log_doesNotThrow() {
        controller.log("CerelaxBatteryLogTest skeleton log", "I")
        // 不抛异常即通过
    }
}
