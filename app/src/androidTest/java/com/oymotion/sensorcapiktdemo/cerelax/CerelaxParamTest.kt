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
 * Cerelax 04 参数（对应 Manual Cases 的 PARAM-*）。
 *
 * 已覆盖骨架：PARAM-FUNC-004/005/006（EEG_SAMPLE_RATE 查询/设置/非法值逆向）、
 *            PARAM-FUNC-008（未知 key 返回 Error）。
 * 待补：PARAM-FUNC-001/002（NTF 与 FILTER 开关）、PARAM-FUNC-003（IMU 联动）、
 *       PARAM-FUNC-007（起流中改参）、PARAM-FUNC-010（DEBUG 路径）。
 */
@RunWith(AndroidJUnit4::class)
class CerelaxParamTest {

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

    /** PARAM-FUNC-006：getParam EEG_SAMPLE_RATE / EEG_SAMPLE_RATE_LIST。 */
    @Test
    fun eegSampleRate_getParam() {
        runBlocking {
            val device = CerelaxTestSupport.scanTarget(controller)
            assertNotNull("未扫描到目标设备", device)
            val profile = CerelaxTestSupport.connectReady(controller, device!!)
            assertNotNull("连接未就绪", profile)
            val p = profile!!

            val current = p.getParam("EEG_SAMPLE_RATE")
            assertTrue("EEG_SAMPLE_RATE 查询不应返回 Error", !current.startsWith("Error"))

            val list = p.getParam("EEG_SAMPLE_RATE_LIST")
            assertTrue("EEG_SAMPLE_RATE_LIST 查询不应返回 Error", !list.startsWith("Error"))
            assertTrue("EEG_SAMPLE_RATE_LIST 应含 250", list.contains("250"))
            assertTrue("EEG_SAMPLE_RATE_LIST 应含 500", list.contains("500"))
            assertTrue("EEG_SAMPLE_RATE_LIST 应含 1000", list.contains("1000"))

            p.disconnect()
        }
    }

    /** PARAM-FUNC-004：setParam EEG_SAMPLE_RATE 合法值，getParam 一致。 */
    @Test
    fun eegSampleRate_setValidValue() {
        runBlocking {
            val device = CerelaxTestSupport.scanTarget(controller)
            assertNotNull("未扫描到目标设备", device)
            val profile = CerelaxTestSupport.connectReady(controller, device!!)
            assertNotNull("连接未就绪", profile)
            val p = profile!!

            val result = p.setParam("EEG_SAMPLE_RATE", "500")
            assertTrue("设置 500 应成功（返回非 Error）：$result", !result.startsWith("Error"))
            val current = p.getParam("EEG_SAMPLE_RATE")
            assertTrue("设置后应为 500，实际：$current", current.contains("500"))

            // 还原默认
            p.setParam("EEG_SAMPLE_RATE", "1000")
            p.disconnect()
        }
    }

    /** PARAM-FUNC-004/005：非法值 750 返回 Error。 */
    @Test
    fun eegSampleRate_illegalValueRejected() {
        runBlocking {
            val device = CerelaxTestSupport.scanTarget(controller)
            assertNotNull("未扫描到目标设备", device)
            val profile = CerelaxTestSupport.connectReady(controller, device!!)
            assertNotNull("连接未就绪", profile)
            val p = profile!!

            val result = p.setParam("EEG_SAMPLE_RATE", "750")
            assertTrue("非法值 750 应返回 Error：$result", result.startsWith("Error"))

            p.disconnect()
        }
    }

    /** PARAM-FUNC-008：未知 key 返回 Error。 */
    @Test
    fun unknownKey_returnsError() {
        runBlocking {
            val device = CerelaxTestSupport.scanTarget(controller)
            assertNotNull("未扫描到目标设备", device)
            val profile = CerelaxTestSupport.connectReady(controller, device!!)
            assertNotNull("连接未就绪", profile)
            val p = profile!!

            val result = p.setParam("UNKNOWN_KEY", "ON")
            assertTrue("未知 key 应返回 Error：$result", result.startsWith("Error"))

            p.disconnect()
        }
    }
}
