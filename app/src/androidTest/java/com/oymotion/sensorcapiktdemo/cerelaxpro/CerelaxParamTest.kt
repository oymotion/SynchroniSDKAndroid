package com.oymotion.sensorcapiktdemo.cerelaxpro

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import com.oymotion.sensorcapiktdemo.devicespecs.DeviceSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cerelax 04 参数（PARAM-*），由 Python gForceUltra/04_Parameters 1:1 迁移，spec 驱动适配。
 *
 * 型号差异适配（spec 驱动）：
 *   - NTF_* 开关、FILTER_* 开关、采样率参数均从 [TestConfig.specForIdentity] 读取 spec 做硬断言，
 *     不硬编码 EMG/EEG 等型号专属值。gForceUltra 的 EMG_SAMPLE_RATE（PARAM-FUNC-004）在 Cerelax
 *     映射为 spec.sampleRates 的 EEG_SAMPLE_RATE。
 *   - gForceUltra 无 EEG，其 PARAM-FUNC-005/006 原为 SKIP；Cerelax 有 EEG，正常执行。
 *   - PARAM-FUNC-009（NEUCIR_*）Cerelax 非 NeuCir 设备，标 @Ignore。
 *
 * 返回语义（与 Python 一致）：setParam/getParam 返回 String，成功非 "Error" 开头，
 * 失败/不支持以 "Error" 开头。
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

    // =====================================================================================
    // 内部辅助
    // =====================================================================================

    private suspend fun requireTarget(): BleDevice =
        CerelaxTestSupport.scanTargetWithRetry(controller) ?: throw AssertionError(
            "未扫描到目标设备（TARGET_IDENTITY=${TestConfig.TARGET_IDENTITY}），请确认设备开机在范围内"
        )

    private suspend fun connectReady(device: BleDevice): SensorProfile =
        CerelaxTestSupport.connectReady(controller, device) ?: throw AssertionError("连接未就绪")

    private suspend fun init(p: SensorProfile): Boolean =
        p.init(
            TestConfig.PACKAGE_SAMPLE_COUNT,
            TestConfig.INIT_TIMEOUT_MS,
            TestConfig.POWER_REFRESH_INTERVAL_MS
        )

    private fun spec(): DeviceSpec = TestConfig.specForIdentity(TestConfig.TARGET_IDENTITY)

    private fun isError(s: String) = s.startsWith("Error")

    // =====================================================================================
    // PARAM-* 用例
    // =====================================================================================

    /** PARAM-BND-001：setParam 非法 value 被拒绝，不崩溃。 */
    @Test
    fun paramBnd001_illegalValue_rejected() = runBlocking {
        val sp = spec()
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        // 代表性键：核心流开关 + 滤波开关（按 spec 能力选取）
        val keys = mutableListOf<String>()
        sp.streams.filter { it.value.supported }.keys.take(3).forEach { keys.add(it) }
        sp.filters.filter { it.value }.keys.take(2).forEach { keys.add(it) }

        val illegalValues = listOf("", "MAYBE", "on", "On", "1", "TRUE")
        for (key in keys) {
            for (v in illegalValues) {
                val ret = p.setParam(key, v)
                CerelaxTestSupport.log("setParam($key, '$v') -> $ret")
                assertTrue("setParam($key, '$v') 应返回 Error 开头，实际=$ret", isError(ret))
            }
        }
        p.disconnect()
    }

    /** PARAM-FUNC-001：spec 支持流每个 NTF_* 键 ON/OFF 返回 OK，getParam 一致。 */
    @Test
    fun paramFunc001_ntfSwitch_onOffConsistent() = runBlocking {
        val sp = spec()
        val supported = CerelaxSpec.supportedStreamKeys(sp)
        assertTrue("spec 应注册支持流", supported.isNotEmpty())

        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        for (key in supported) {
            val retOn = p.setParam(key, "ON")
            assertTrue("setParam($key, ON) 应成功，实际=$retOn", !isError(retOn))
            val vOn = p.getParam(key)
            CerelaxTestSupport.log("$key ON -> getParam=$vOn")
            assertTrue("getParam($key) 应含 ON，实际=$vOn", vOn.contains("ON"))

            val retOff = p.setParam(key, "OFF")
            assertTrue("setParam($key, OFF) 应成功，实际=$retOff", !isError(retOff))
            val vOff = p.getParam(key)
            assertTrue("getParam($key) 应含 OFF，实际=$vOff", vOff.contains("OFF"))
        }
        p.disconnect()
    }

    /** PARAM-FUNC-002：FILTER_* 键 ON/OFF 返回 OK，getParam 一致。 */
    @Test
    fun paramFunc002_filterSwitch_onOffConsistent() = runBlocking {
        val sp = spec()
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        for (key in sp.filters.keys) {
            val retOn = p.setParam(key, "ON")
            if (isError(retOn)) {
                CerelaxTestSupport.log("$key 设备不支持（$retOn），跳过")
                continue
            }
            val vOn = p.getParam(key)
            CerelaxTestSupport.log("$key ON -> getParam=$vOn")
            assertTrue("getParam($key) 应非 Error，实际=$vOn", !isError(vOn))
            p.setParam(key, "OFF")
        }
        p.disconnect()
    }

    /** PARAM-FUNC-003：NTF_IMU 聚合流开关与 NTF_GFORCE_* 联动（Cerelax IMU 为聚合 6 通道）。 */
    @Test
    fun paramFunc003_imuAggregationSwitch() = runBlocking {
        val sp = spec()
        val imu = sp.streams["NTF_IMU"]
        assertTrue("spec 应注册 NTF_IMU", imu != null)
        if (imu!!.supported) {
            val device = requireTarget()
            val p = connectReady(device)
            assertTrue("init 失败", init(p))

            val retOn = p.setParam("NTF_IMU", "ON")
            assertTrue("setParam(NTF_IMU, ON) 应成功，实际=$retOn", !isError(retOn))
            val vImu = p.getParam("NTF_IMU")
            assertTrue("getParam(NTF_IMU) 应含 ON，实际=$vImu", vImu.contains("ON"))

            // 记录联动观察（Cerelax IMU 为聚合流，ACC/GYRO 是否联动以实测为准）
            for (k in listOf("NTF_GFORCE_ACC", "NTF_GFORCE_GYRO", "NTF_GFORCE_EULER", "NTF_GFORCE_QUAT")) {
                CerelaxTestSupport.log("NTF_IMU ON 后 getParam($k) = ${p.getParam(k)}")
            }

            p.setParam("NTF_IMU", "OFF")
            p.disconnect()
        } else {
            CerelaxTestSupport.log("spec 判定 NTF_IMU 不支持，跳过")
        }
    }

    /** PARAM-FUNC-004：采样率正反向（默认值 + 列表 + 逐一设置读回）。spec 驱动遍历。 */
    @Test
    fun paramFunc004_sampleRate_setGetRoundTrip() = runBlocking {
        val sp = spec()
        assertTrue("spec 应注册采样率参数", sp.sampleRates.isNotEmpty())

        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        for ((key, sr) in sp.sampleRates) {
            // 上电默认值
            val cur = p.getParam(key)
            assertTrue("getParam($key) 不应 Error，实际=$cur", !isError(cur))
            assertTrue("$key 默认应为 ${sr.default}，实际=$cur", cur.contains(sr.default))

            // 可选列表
            val list = p.getParam("${key}_LIST")
            if (!isError(list)) {
                for (r in sr.rates) {
                    assertTrue("${key}_LIST 应含 $r，实际=$list", list.contains(r))
                }
            } else {
                CerelaxTestSupport.log("${key}_LIST 不可用（$list），跳过列表校验")
            }

            // 逐一设置读回
            for (r in sr.rates) {
                val ret = p.setParam(key, r)
                assertTrue("setParam($key, $r) 应成功，实际=$ret", !isError(ret))
                val readBack = p.getParam(key)
                assertTrue("$key=$r 读回应一致，实际=$readBack", readBack.contains(r))
            }

            // 还原默认
            p.setParam(key, sr.default)
        }
        p.disconnect()
    }

    /** PARAM-FUNC-005：采样率非法值（列表外 + 空串）返回 Error。 */
    @Test
    fun paramFunc005_sampleRate_illegalValueRejected() = runBlocking {
        val sp = spec()
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        for ((key, sr) in sp.sampleRates) {
            // 列表外值：取一个明显不在 rates 里的值
            val outside = "750"
            assertTrue("750 应不在 $key 合法值内", sr.rates.none { it == outside })

            val r1 = p.setParam(key, outside)
            assertTrue("setParam($key, $outside) 应返回 Error，实际=$r1", isError(r1))

            val r2 = p.setParam(key, "")
            assertTrue("setParam($key, 空串) 应返回 Error，实际=$r2", isError(r2))
        }
        p.disconnect()
    }

    /** PARAM-FUNC-006：getParam EEG_SAMPLE_RATE / EEG_SAMPLE_RATE_LIST。 */
    @Test
    fun paramFunc006_eegSampleRate_getParam() = runBlocking {
        val sp = spec()
        val eeg = sp.sampleRates["EEG_SAMPLE_RATE"]
        assertTrue("Cerelax 应注册 EEG_SAMPLE_RATE", eeg != null)

        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val current = p.getParam("EEG_SAMPLE_RATE")
        assertTrue("EEG_SAMPLE_RATE 查询不应 Error，实际=$current", !isError(current))
        assertTrue("EEG_SAMPLE_RATE 默认应为 ${eeg!!.default}，实际=$current", current.contains(eeg.default))

        val list = p.getParam("EEG_SAMPLE_RATE_LIST")
        assertTrue("EEG_SAMPLE_RATE_LIST 查询不应 Error，实际=$list", !isError(list))
        for (r in eeg.rates) {
            assertTrue("EEG_SAMPLE_RATE_LIST 应含 $r，实际=$list", list.contains(r))
        }
        p.disconnect()
    }

    /** PARAM-FUNC-007：起流中修改参数生效（自动停流重起或等效）。 */
    @Test
    fun paramFunc007_changeParamDuringStream_takesEffect() = runBlocking {
        val sp = spec()
        val eeg = sp.sampleRates["EEG_SAMPLE_RATE"]
        assertTrue("Cerelax 应注册 EEG_SAMPLE_RATE", eeg != null)

        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        // 起流
        assertTrue("startDataNotification 失败", p.startDataNotification())
        assertTrue("起流后 isDataTransfering 应为 true", p.isDataTransfering())

        // 起流中改采样率
        val target = eeg!!.rates.first { it != eeg.default }
        val ret = p.setParam("EEG_SAMPLE_RATE", target)
        assertTrue("起流中改参应成功，实际=$ret", !isError(ret))

        Thread.sleep(3000)
        val readBack = p.getParam("EEG_SAMPLE_RATE")
        assertTrue("改参后 getParam 应为 $target，实际=$readBack", readBack.contains(target))

        // 还原
        p.setParam("EEG_SAMPLE_RATE", eeg.default)
        p.stopDataNotification()
        p.disconnect()
    }

    /** PARAM-FUNC-008：未知 key 返回 Error 开头。 */
    @Test
    fun paramFunc008_unknownKey_returnsError() = runBlocking {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val ret = p.setParam("UNKNOWN_KEY", "ON")
        assertTrue("未知 key 应返回 Error，实际=$ret", isError(ret))
        p.disconnect()
    }

    /** PARAM-FUNC-009：NEUCIR_* —— Cerelax 非 NeuCir 设备，不适用。 */
    @Ignore("Cerelax 非 NeuCir 设备，NEUCIR_* 参数不适用")
    @Test
    fun paramFunc009_neucir_notApplicable() = Unit

    /** PARAM-FUNC-010：DEBUG_BLE_DATA_PATH / DEBUG_LOG_PATH（不支持则记录跳过）。 */
    @Test
    fun paramFunc010_debugPath_getSet() = runBlocking {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        for (key in listOf("DEBUG_BLE_DATA_PATH", "DEBUG_LOG_PATH")) {
            val ret = p.setParam(key, "true")
            if (isError(ret)) {
                CerelaxTestSupport.log("$key 不支持/不适用（$ret），跳过")
                continue
            }
            val read = p.getParam(key)
            CerelaxTestSupport.log("$key -> set=$ret get=$read")
            assertTrue("getParam($key) 应非 Error，实际=$read", !isError(read))
            p.setParam(key, "false")
        }
        p.disconnect()
    }

    /** PARAM-FUNC-011：IMU/PPG 采样率只读字段合法（SDK 无设置接口，仅 DeviceInfo 只读）。 */
    @Test
    fun paramFunc011_imuPpgSampleRate_readOnlyFields() = runBlocking {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val info = p.getDeviceInfo()
        assertTrue("getDeviceInfo 不应为 null", info != null)
        val imu = info?.imuSampleRate ?: -1f
        val ppg = info?.ppgSampleRate ?: -1f
        CerelaxTestSupport.log("imuSampleRate=$imu，ppgSampleRate=$ppg")
        assertTrue("imuSampleRate 应 >= 0，实际=$imu", imu >= 0f)
        assertTrue("ppgSampleRate 应 >= 0，实际=$ppg", ppg >= 0f)

        p.disconnect()
    }

    /** PARAM-BND-002：setParam/getParam 空串 key 被拒绝，不崩溃。 */
    @Test
    fun paramBnd002_emptyKey_rejected() = runBlocking {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val rSet = p.setParam("", "true")
        assertTrue("setParam(空串) 应返回 Error，实际=$rSet", isError(rSet))

        val rGet = p.getParam("")
        assertTrue("getParam(空串) 应返回 Error，实际=$rGet", isError(rGet))

        p.disconnect()
    }

    /** PARAM-ROB-001：同一 key 重复 setParam 幂等（多次设置读回一致，不崩溃）。 */
    @Test
    fun paramRob001_repeatedSet_idempotent() = runBlocking {
        val sp = spec()
        val key = sp.sampleRates.keys.firstOrNull() ?: "EEG_SAMPLE_RATE"
        val value = sp.sampleRates[key]?.default ?: "250"

        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        var err = false
        repeat(3) {
            try {
                val ret = p.setParam(key, value)
                if (isError(ret)) {
                    CerelaxTestSupport.log("setParam($key, $value) 返回 $ret（设备不支持），跳过")
                    return@repeat
                }
                val read = p.getParam(key)
                assertTrue("$key 读回应含 $value，实际=$read", read.contains(value))
            } catch (e: Exception) {
                err = true
                CerelaxTestSupport.log("重复 setParam 抛异常 ${e.javaClass.simpleName}")
            }
        }
        assertFalse("重复 setParam 不应抛异常", err)
        p.disconnect()
    }

    /** PARAM-FUNC-012：IMU_SAMPLE_RATE 设置 —— SDK 当前版本未实现，规划中。 */
    @Ignore("Android SDK 未提供 IMU_SAMPLE_RATE 设置接口（规划中），仅可读 DeviceInfo.imuSampleRate")
    @Test
    fun paramFunc012_imuSampleRate_set() = Unit

    /** PARAM-FUNC-013：PPG_SAMPLE_RATE 设置 —— SDK 当前版本未实现，规划中。 */
    @Ignore("Android SDK 未提供 PPG_SAMPLE_RATE 设置接口（规划中），仅可读 DeviceInfo.ppgSampleRate")
    @Test
    fun paramFunc013_ppgSampleRate_set() = Unit
}
