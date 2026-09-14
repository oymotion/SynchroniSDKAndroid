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
import java.util.concurrent.TimeUnit

/**
 * Cerelax 09 异步接口（对应 Manual Cases 的 ASYNC-*）。
 *
 * 已覆盖骨架：connectAsync/disconnectAsync（CompletableFuture）、fetchDeviceInfo（suspend）。
 * 其余 suspend 扩展（connect/init/startDataNotification/stopDataNotification/setParam/getParam）
 * 已在其它模块的 runBlocking 中直接使用。
 */
@RunWith(AndroidJUnit4::class)
class CerelaxAsyncTest {

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

    /** ASYNC-*：connectAsync / disconnectAsync 返回的 Future 正常完成。 */
    @Test
    fun connectAsync_futureCompletes() {
        runBlocking {
            val device = CerelaxTestSupport.scanTarget(controller)
            assertNotNull("未扫描到目标设备", device)

            val profile = controller.requireSensor(device!!.mac)!!
            val ok = profile.connectAsync().get(TestConfig.READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            assertTrue("connectAsync 应返回 true", ok)
            assertTrue("未到达 Ready", CerelaxTestSupport.waitForReady(profile))

            profile.disconnectAsync().get(TestConfig.READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** ASYNC-*：fetchDeviceInfo suspend 返回非空 DeviceInfo。 */
    @Test
    fun fetchDeviceInfo_suspend() {
        runBlocking {
            val device = CerelaxTestSupport.scanTarget(controller)
            assertNotNull("未扫描到目标设备", device)
            val profile = CerelaxTestSupport.connectReady(controller, device!!)
            assertNotNull("连接未就绪", profile)
            val p = profile!!

            val info = p.fetchDeviceInfo()
            assertTrue("deviceName 应非空", info.deviceName.isNotEmpty())

            p.disconnect()
        }
    }
}
