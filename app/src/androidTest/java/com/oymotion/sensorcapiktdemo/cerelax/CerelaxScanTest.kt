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
 * Cerelax 01 控制器与扫描（对应 Manual Cases 的 CTRL-*）。
 *
 * 骨架示例：scan 发现目标 Cerelax、requireSensor/getSensor 命中/未命中、
 * getVersion 非空、startScan/stopScan 状态翻转。
 * 其余 CTRL-*（isEnable 蓝牙开关、onScanResult 周期回调、边界/幂等）按同模式补 @Test 方法即可。
 */
@RunWith(AndroidJUnit4::class)
class CerelaxScanTest {

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

    /** CTRL-FUNC-004：单次扫描能发现本轮目标 Cerelax 设备。 */
    @Test
    fun scan_findsTargetCerelax() {
        runBlocking {
            val devices: List<BleDevice> = controller.scan(TestConfig.SCAN_TIMEOUT_MS)
            val hit = devices.firstOrNull { TestConfig.matchesTarget(it) }
            assertNotNull(
                "未扫描到目标设备（TARGET_IDENTITY=${TestConfig.TARGET_IDENTITY}），请确认设备开机在范围内",
                hit
            )
        }
    }

    /** CTRL-FUNC-007/008：requireSensor / getSensor 按 MAC 命中与未命中。 */
    @Test
    fun requireSensor_getSensor_byMac() {
        runBlocking {
            val devices: List<BleDevice> = controller.scan(TestConfig.SCAN_TIMEOUT_MS)
            val target = devices.firstOrNull { TestConfig.matchesTarget(it) }
            assertNotNull("未扫描到目标设备", target)

            val profile: SensorProfile? = controller.requireSensor(target!!.mac)
            assertNotNull("requireSensor 返回 null", profile)

            val same: SensorProfile? = controller.getSensor(target.mac)
            assertNotNull("getSensor 应命中已创建 profile", same)

            val missing: SensorProfile? = controller.getSensor("00:00:00:00:00:00")
            assertNull("错误 MAC 不应命中", missing)
        }
    }

    /** CTRL-FUNC-012：getVersion 返回非空。 */
    @Test
    fun getVersion_nonEmpty() {
        val version = controller.getVersion()
        assertTrue("getVersion 应返回非空", version.isNotEmpty())
    }

    /** CTRL-FUNC-003：startScan / stopScan 状态翻转。 */
    @Test
    fun startStopScan_stateFlips() {
        val started = controller.startScan(TestConfig.SCAN_TIMEOUT_MS)
        assertTrue("startScan 应返回 True", started)
        assertTrue("isScanning 应为 true", controller.isScanning)
        controller.stopScan()
        assertTrue("stopScan 后 isScanning 应为 false", !controller.isScanning)
    }
}
