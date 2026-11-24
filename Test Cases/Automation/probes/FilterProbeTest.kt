package com.oymotion.sensorcapiktdemo.automation.probes

import android.Manifest
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import com.oymotion.sensorcapiktdemo.cerelaxpro.CerelaxSpec
import com.oymotion.sensorcapiktdemo.cerelaxpro.CerelaxTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 一次性诊断探针：定位 FILTER_* “写 OK / 单键读 Not supported” 的原因。
 *
 * 只打印、不断言：
 *   1. FILTER 聚合值是否能读；
 *   2. 每个 FILTER_* 单键的 getParam 是否可用；
 *   3. setParam(ON/OFF) 的返回值，以及写后单键读 / 聚合读是否变化。
 *
 * 运行：
 *   .\gradlew.bat :app:connectedDebugAndroidTest `
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.oymotion.sensorcapiktdemo.automation.probes.FilterProbeTest
 * 日志：
 *   adb logcat -d -s FilterProbe:I
 */
@RunWith(AndroidJUnit4::class)
class FilterProbeTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    private val tag = "FilterProbe"

    private val filterKeys = listOf("FILTER_50HZ", "FILTER_60HZ", "FILTER_HPF", "FILTER_LPF")

    private fun log(msg: String) {
        Log.i(tag, msg)
        CerelaxTestSupport.log("[$tag] $msg")
    }

    private suspend fun safeGet(p: SensorProfile, key: String): String =
        try {
            p.getParam(key)
        } catch (e: Exception) {
            "THROW ${e.javaClass.simpleName}: ${e.message}"
        }

    private suspend fun safeSet(p: SensorProfile, key: String, value: String): String =
        try {
            p.setParam(key, value)
        } catch (e: Exception) {
            "THROW ${e.javaClass.simpleName}: ${e.message}"
        }

    @Test
    fun probeFilter() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val controller = SensorController.getInstance()
        controller.registerBleBridge(context)
        TestConfig.bind(context)

        val scanned = controller.scan(TestConfig.SCAN_TIMEOUT_MS)
        log("本轮扫描 ${scanned.size} 台: " + scanned.joinToString { "${it.name}(${it.mac},rssi=${it.rssi})" })
        val device = CerelaxTestSupport.scanTargetWithRetry(controller)
            ?: error("未扫描到目标设备（TARGET_IDENTITY=${TestConfig.TARGET_IDENTITY}），可见设备见上一条日志")
        val p = CerelaxTestSupport.connectReady(controller, device)
            ?: error("连接未就绪: ${device.mac}")
        check(
            p.init(
                TestConfig.PACKAGE_SAMPLE_COUNT,
                TestConfig.INIT_TIMEOUT_MS,
                TestConfig.POWER_REFRESH_INTERVAL_MS
            )
        ) { "init 失败" }

        log("device=${device.mac} model=${p.getDeviceInfo()?.modelName}")

        val agg0 = safeGet(p, "FILTER")
        log("初始 FILTER 聚合 = $agg0")
        log("初始聚合可解析 = ${CerelaxSpec.parseSwitchList(agg0)}")

        for (key in filterKeys) {
            log("初始单键读 $key = ${safeGet(p, key)}")
        }

        for (key in filterKeys) {
            val beforeAgg = safeGet(p, "FILTER")
            val before = CerelaxSpec.parseSwitchList(beforeAgg)
                .firstOrNull { it.first == key }?.second
            log("==== $key before=$before ====")

            for (v in listOf("ON", "OFF")) {
                val ret = safeSet(p, key, v)
                val single = safeGet(p, key)
                val agg = safeGet(p, "FILTER")
                val inAgg = CerelaxSpec.parseSwitchList(agg).firstOrNull { it.first == key }?.second
                log("set($key=$v) -> ret=$ret")
                log("  单键读=$single")
                log("  聚合读=$agg（聚合内状态=$inAgg）")
            }

            if (before != null) {
                val restore = safeSet(p, key, before)
                log("restore $key=$before -> $restore | 聚合=${safeGet(p, "FILTER")}")
            }
        }

        log("结束前 FILTER 聚合 = ${safeGet(p, "FILTER")}")
        p.disconnect()
    }
}
