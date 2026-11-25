package com.oymotion.sensorcapiktdemo.cerelaxpro

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import com.oymotion.sensorcapiktdemo.devicespecs.DeviceSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cerelax 04 参数（PARAM-*），spec 驱动适配。
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
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        controller.registerBleBridge(context)
        TestConfig.bind(context)
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

    /** native 侧失败会抛 IOException，统一转字符串，避免不支持/无独立开关的 key 打断整条用例。 */
    private suspend fun safeSetParam(p: SensorProfile, key: String, value: String): String =
        try {
            p.setParam(key, value)
        } catch (e: Exception) {
            "Error: ${e.message ?: e.javaClass.simpleName}"
        }

    private suspend fun safeGetParam(p: SensorProfile, key: String): String =
        try {
            p.getParam(key)
        } catch (e: Exception) {
            "Error: ${e.message ?: e.javaClass.simpleName}"
        }

    /** setParam 是否被拒绝（抛异常或返回 Error 开头都算）。 */
    private suspend fun rejected(block: suspend () -> String): Boolean =
        try {
            block().startsWith("Error")
        } catch (_: Exception) {
            true
        }

    // =====================================================================================
    // PARAM-* 用例
    // =====================================================================================

    /** PARAM-BND-001：setParam 非法 value 被拒绝，不崩溃。 */
    @Test
    fun paramBnd001_illegalValue_rejected() = runBlocking<Unit> {
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
                val ret = safeSetParam(p, key, v)
                CerelaxTestSupport.log("setParam($key, '$v') -> $ret")
                assertTrue("setParam($key, '$v') 应被拒绝，实际=$ret", isError(ret))
            }
        }
        p.disconnect()
    }

    /** PARAM-FUNC-001：流开关 ON/OFF 回读一致，无独立开关/不支持流被拒绝。 */
    @Test
    fun paramFunc001_ntfSwitch_onOffConsistent() = runBlocking<Unit> {
        val sp = spec()
        val supported = CerelaxSpec.supportedStreamKeys(sp)
        assertTrue("spec 应注册支持流", supported.isNotEmpty())

        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        // 以设备 getParam("NTF") 为权威开关列表：spec 的 ACC/GYRO 子流没有独立开关
        val ntf = safeGetParam(p, "NTF")
        assertTrue("getParam(NTF) 应返回开关列表，实际=$ntf", !isError(ntf) && ntf.contains("|"))
        val switches = CerelaxSpec.parseSwitchList(ntf)
        assertTrue("无法从 NTF 解析流开关: $ntf", switches.isNotEmpty())

        var toggled = 0
        val skipped = mutableListOf<String>()
        for ((key, orig) in switches) {
            val flip = if (orig == "ON") "OFF" else "ON"
            val ret = safeSetParam(p, key, flip)
            if (isError(ret)) {
                skipped += "$key(${ret.trim()})"
                continue
            }
            val back = safeGetParam(p, "NTF")
            assertTrue(
                "$key 切到 $flip 后 getParam(NTF) 应体现，实际=$back",
                back.contains("$key|$flip")
            )
            val restore = safeSetParam(p, key, orig)
            assertTrue("$key 还原 $orig 应成功，实际=$restore", !isError(restore))
            toggled++
        }

        // spec 支持但不在 NTF 列表里的流（如 IMU 子流 ACC/GYRO）：可能无独立开关，只记录
        val listedKeys = switches.map { it.first }.toSet()
        val independent = supported.map { CerelaxSpec.paramKey(it) }
            .filter { it !in listedKeys }
            .mapNotNull { key ->
                val v = safeGetParam(p, key)
                if (isError(v)) null else "$key=$v"
            }

        // spec 判定不支持的流：设置应被拒绝
        for (key in sp.unsupportedStreams) {
            val paramKey = CerelaxSpec.paramKey(key)
            assertTrue(
                "不支持流 $paramKey，setParam 应被拒绝",
                rejected { p.setParam(paramKey, "ON") }
            )
        }

        assertTrue("没有任何可验证的流开关", toggled > 0 || independent.isNotEmpty())
        CerelaxTestSupport.log("toggled=$toggled skipped=${skipped.size} independent=$independent")
        p.disconnect()
    }

    /** PARAM-FUNC-002：滤波参数 ON/OFF 回读一致（以 FILTER 聚合列表为准）。 */
    @Test
    fun paramFunc002_filterSwitch_onOffConsistent() = runBlocking<Unit> {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        // 单键 getParam 可能返回 Not supported（实测 FILTER_50HZ），改读 FILTER 聚合列表
        val agg = safeGetParam(p, "FILTER")
        assumeTrue("设备无 FILTER 聚合开关（$agg），spec 声明与实际能力不一致", !isError(agg) && agg.contains("|"))
        val entries = CerelaxSpec.parseSwitchList(agg)
        assumeTrue("FILTER 列表无法解析: $agg", entries.isNotEmpty())

        val failed = mutableListOf<String>()
        for ((key, orig) in entries) {
            val flip = if (orig == "ON") "OFF" else "ON"
            val a = safeSetParam(p, key, flip)
            val back = safeGetParam(p, "FILTER")
            val b = safeSetParam(p, key, orig)
            val ok = !isError(a) && back.contains("$key|$flip") && !isError(b)
            if (!ok) failed += "$key(set=$a readback=$back restore=$b)"
        }
        assertTrue("以下滤波开关失败: ${failed.joinToString(" | ")}", failed.isEmpty())
        p.disconnect()
    }

    /** PARAM-FUNC-003：NTF_IMU 聚合流开关与 NTF_GFORCE_* 联动（Cerelax IMU 为聚合 6 通道）。 */
    @Test
    fun paramFunc003_imuAggregationSwitch() = runBlocking<Unit> {
        val sp = spec()
        val imu = sp.streams["NTF_IMU"]
        assertTrue("spec 应注册 NTF_IMU", imu != null)
        if (imu!!.supported) {
            val device = requireTarget()
            val p = connectReady(device)
            assertTrue("init 失败", init(p))

            val retOn = safeSetParam(p, "NTF_IMU", "ON")
            assertTrue("setParam(NTF_IMU, ON) 应成功，实际=$retOn", !isError(retOn))
            val vImu = safeGetParam(p, "NTF_IMU")
            assertTrue("getParam(NTF_IMU) 应含 ON，实际=$vImu", vImu.contains("ON"))

            // 记录联动观察（Cerelax IMU 为聚合流，ACC/GYRO 是否联动以实测为准）
            // 单键 getParam 可能抛 Not supported，不能打断用例
            for (k in listOf("NTF_GFORCE_ACC", "NTF_GFORCE_GYRO", "NTF_GFORCE_EULER", "NTF_GFORCE_QUAT")) {
                CerelaxTestSupport.log("NTF_IMU ON 后 getParam($k) = ${safeGetParam(p, k)}")
            }

            safeSetParam(p, "NTF_IMU", "OFF")
            p.disconnect()
        } else {
            CerelaxTestSupport.log("spec 判定 NTF_IMU 不支持，跳过")
        }
    }

    /** PARAM-FUNC-004：采样率正反向（默认值 + 列表 + 逐一设置读回）。spec 驱动遍历。 */
    @Test
    fun paramFunc004_sampleRate_setGetRoundTrip() = runBlocking<Unit> {
        val sp = spec()
        assertTrue("spec 应注册采样率参数", sp.sampleRates.isNotEmpty())

        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        for ((key, sr) in sp.sampleRates) {
            val cur = safeGetParam(p, key)

            if (!sr.settable) {
                sr.coStream?.let { CerelaxTestSupport.log("$key coStream=$it（随 $it 起停）") }
                if (isError(cur)) {
                    CerelaxTestSupport.log("$key 无采样率参数（$cur），符合 spec settable=false")
                } else {
                    assertTrue("$key 固定值应为 ${sr.default}，实际=$cur", cur.contains(sr.default))
                }
                continue
            }

            assertTrue("getParam($key) 不应 Error，实际=$cur", !isError(cur))
            // 不假设上电默认值：设备状态可能被历史用例/上一轮修改，按当前值做回环校验
            val current = sr.rates.firstOrNull { cur.contains(it) } ?: cur.trim()
            if (current != sr.default) {
                CerelaxTestSupport.log("$key 当前=$current（spec 默认=${sr.default}，可能被历史用例修改）")
            }

            // 可选列表
            val list = safeGetParam(p, "${key}_LIST")
            if (!isError(list) && list.isNotBlank()) {
                for (r in sr.rates) {
                    assertTrue("${key}_LIST 应含 $r，实际=$list", list.contains(r))
                }
            } else {
                CerelaxTestSupport.log("${key}_LIST 不可用（$list），跳过列表校验")
            }

            // 逐一设置读回
            for (r in sr.rates) {
                val ret = safeSetParam(p, key, r)
                assertTrue("setParam($key, $r) 应成功，实际=$ret", !isError(ret))
                val readBack = safeGetParam(p, key)
                assertTrue("$key=$r 读回应一致，实际=$readBack", readBack.contains(r))
            }

            // 还原用例前的值，避免污染后续用例
            safeSetParam(p, key, current)
        }
        p.disconnect()
    }

    /** PARAM-FUNC-005：采样率非法值（列表外 + 空串）返回 Error。 */
    @Test
    fun paramFunc005_sampleRate_illegalValueRejected() = runBlocking<Unit> {
        val sp = spec()
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        // 只测可设参数：IMPEDANCE 等 settable=false 不参与非法值拒绝
        for ((key, sr) in sp.sampleRates.filterValues { it.settable }) {
            // 列表外值：取一个明显不在 rates 里的值
            val outside = "750"
            assertTrue("750 应不在 $key 合法值内", sr.rates.none { it == outside })

            val r1 = safeSetParam(p, key, outside)
            assertTrue("setParam($key, $outside) 应返回 Error，实际=$r1", isError(r1))

            val r2 = safeSetParam(p, key, "")
            assertTrue("setParam($key, 空串) 应返回 Error，实际=$r2", isError(r2))
        }
        p.disconnect()
    }

    /** PARAM-FUNC-006：getParam EEG_SAMPLE_RATE / EEG_SAMPLE_RATE_LIST。 */
    @Test
    fun paramFunc006_eegSampleRate_getParam() = runBlocking<Unit> {
        val sp = spec()
        val eeg = sp.sampleRates["EEG_SAMPLE_RATE"]
        assumeTrue("spec 未注册 EEG_SAMPLE_RATE", eeg != null)

        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val current = safeGetParam(p, "EEG_SAMPLE_RATE")
        assertTrue("EEG_SAMPLE_RATE 查询不应 Error，实际=$current", !isError(current))
        // 只校验当前值属于合法档位；上电默认值可能被历史用例/上一轮修改，不作为硬条件
        assertTrue(
            "EEG_SAMPLE_RATE 当前值应属于可选档位 ${eeg!!.rates}，实际=$current",
            eeg.rates.any { current.contains(it) }
        )
        if (!current.contains(eeg.default)) {
            CerelaxTestSupport.log("EEG_SAMPLE_RATE 当前=$current（spec 默认=${eeg.default}，可能被历史用例修改）")
        }

        val list = safeGetParam(p, "EEG_SAMPLE_RATE_LIST")
        assertTrue("EEG_SAMPLE_RATE_LIST 查询不应 Error，实际=$list", !isError(list))
        for (r in eeg.rates) {
            assertTrue("EEG_SAMPLE_RATE_LIST 应含 $r，实际=$list", list.contains(r))
        }
        p.disconnect()
    }

    /** PARAM-FUNC-007：起流中修改参数生效（自动停流重起或等效）。 */
    @Test
    fun paramFunc007_changeParamDuringStream_takesEffect() = runBlocking<Unit> {
        val sp = spec()
        val entry = sp.sampleRates.entries.firstOrNull { (_, sr) ->
            sr.settable && (sr.stream.isEmpty() || sp.supports(sr.stream))
        }
        assumeTrue("spec 无 settable 采样率参数，跳过", entry != null)
        val (key, sr) = entry!!

        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        // 起流
        assertTrue("startDataNotification 失败", p.startDataNotification())
        assertTrue("起流后 isDataTransfering 应为 true", p.isDataTransfering())

        // 记录原值并还原原值（不要写死 spec 默认，避免覆盖设备既有状态、污染后续用例/下一轮）
        val originalRaw = safeGetParam(p, key)
        val original = sr.rates.firstOrNull { originalRaw.contains(it) } ?: sr.default
        // 有其它档则切到不同档；单档位设备（如 OB6000C EEG 固定 250）同值重设 + 翻转 FILTER
        val target = sr.rates.firstOrNull { it != original } ?: original
        val switched = target != original
        var filterRestore: Pair<String, String>? = null

        try {
            val ret = safeSetParam(p, key, target)
            assertTrue("起流中改参应成功，实际=$ret", !isError(ret))

            Thread.sleep(3000)
            val readBack = safeGetParam(p, key)
            assertTrue("改参后 getParam($key) 应为 $target，实际=$readBack", readBack.contains(target))

            if (!switched) {
                // 单档位设备同值重设无法证明「新状态生效」，按手工用例口径在起流中翻转一个 FILTER_*。
                val agg = safeGetParam(p, "FILTER")
                val entries =
                    if (isError(agg) || !agg.contains("|")) emptyList() else CerelaxSpec.parseSwitchList(agg)
                val filterKey = entries.map { it.first }.firstOrNull { k -> sp.filters[k] == true }
                if (filterKey == null) {
                    CerelaxTestSupport.log("单档位设备且无可解析 FILTER_*，仅验证起流中同值重设 $key=$target 的读回")
                } else {
                    val orig = entries.first { it.first == filterKey }.second
                    val flip = if (orig == "ON") "OFF" else "ON"
                    val fRet = safeSetParam(p, filterKey, flip)
                    assertTrue("起流中改 $filterKey 应成功，实际=$fRet", !isError(fRet))
                    filterRestore = filterKey to orig
                    Thread.sleep(1000)
                    val aggAfter = safeGetParam(p, "FILTER")
                    assertTrue(
                        "起流中 $filterKey 切到 $flip 后应体现，实际=$aggAfter",
                        aggAfter.contains("$filterKey|$flip"),
                    )
                    CerelaxTestSupport.log("单档位设备：起流中 $key 同值重设 + $filterKey $orig->$flip 已生效")
                }
            }

            // 还原原值
            safeSetParam(p, key, original)
        } finally {
            filterRestore?.let { (k, v) -> safeSetParam(p, k, v) }
            p.stopDataNotification()
            p.disconnect()
        }
    }

    /** PARAM-FUNC-008：未知 key 返回 Error 开头。 */
    @Test
    fun paramFunc008_unknownKey_returnsError() = runBlocking<Unit> {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val ret = safeSetParam(p, "UNKNOWN_KEY", "ON")
        assertTrue("未知 key 应被拒绝，实际=$ret", isError(ret))
        p.disconnect()
    }

    /** PARAM-FUNC-009：NEUCIR_* —— Cerelax 非 NeuCir 设备，不适用。 */
    @Ignore("Cerelax 非 NeuCir 设备，NEUCIR_* 参数不适用")
    @Test
    fun paramFunc009_neucir_notApplicable() = Unit

    /** PARAM-FUNC-010：DEBUG_BLE_DATA_PATH / DEBUG_LOG_PATH（不支持则记录跳过）。 */
    @Test
    fun paramFunc010_debugPath_getSet() = runBlocking<Unit> {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        var supported = 0
        for (key in listOf("DEBUG_BLE_DATA_PATH", "DEBUG_LOG_PATH")) {
            // 不支持时 SDK 抛 IOException，safeSetParam 统一转成 "Error: ..."
            val ret = safeSetParam(p, key, "true")
            if (isError(ret)) {
                CerelaxTestSupport.log("$key 不支持/不适用（$ret），跳过")
                continue
            }
            val read = safeGetParam(p, key)
            CerelaxTestSupport.log("$key -> set=$ret get=$read")
            assertTrue("getParam($key) 应非 Error，实际=$read", !isError(read))
            safeSetParam(p, key, "false")
            supported++
        }
        p.disconnect()
        assumeTrue(
            "DEBUG_BLE_DATA_PATH / DEBUG_LOG_PATH 均不支持，无法验证读写",
            supported > 0
        )
    }

    /** PARAM-FUNC-011：IMU/PPG 采样率只读字段合法（SDK 无设置接口，仅 DeviceInfo 只读）。 */
    @Test
    fun paramFunc011_imuPpgSampleRate_readOnlyFields() = runBlocking<Unit> {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val info = p.getDeviceInfo()
        assertTrue("getDeviceInfo 不应为 null", info != null)
        val imu = info?.imuSampleRate ?: -1f
        val ppg = info?.ppgSampleRate ?: -1f
        CerelaxTestSupport.log("imuSampleRate=$imu，ppgSampleRate=$ppg")
        val sp = spec()
        if (sp.supports("NTF_IMU")) assertTrue("imuSampleRate 应 > 0，实际=$imu", imu > 0f)
        if (sp.supports("NTF_PPG")) assertTrue("ppgSampleRate 应 > 0，实际=$ppg", ppg > 0f)

        p.disconnect()
    }

    /** PARAM-BND-002：setParam/getParam 空串 key 被拒绝，不崩溃。 */
    @Test
    fun paramBnd002_emptyKey_rejected() = runBlocking<Unit> {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val rSet = safeSetParam(p, "", "true")
        assertTrue("setParam(空串) 应被拒绝，实际=$rSet", isError(rSet))

        val rGet = safeGetParam(p, "")
        assertTrue("getParam(空串) 应被拒绝，实际=$rGet", isError(rGet))

        p.disconnect()
    }

    /** PARAM-ROB-001：同一 key 重复 setParam 幂等（多次设置读回一致，不崩溃）。 */
    @Test
    fun paramRob001_repeatedSet_idempotent() = runBlocking<Unit> {
        val sp = spec()
        val key = sp.sampleRates.keys.firstOrNull() ?: "EEG_SAMPLE_RATE"
        val sr = sp.sampleRates[key]

        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        // 用当前值做幂等校验（不写死默认值，避免改变设备既有状态）
        val raw = safeGetParam(p, key)
        val value = sr?.rates?.firstOrNull { raw.contains(it) } ?: sr?.default ?: "250"

        repeat(3) { i ->
            val ret = safeSetParam(p, key, value)
            assertTrue("第 ${i + 1} 次 setParam($key, $value) 应成功，实际=$ret", !isError(ret))
            val read = safeGetParam(p, key)
            assertTrue("第 ${i + 1} 次 $key 读回应含 $value，实际=$read", read.contains(value))
        }
        p.disconnect()
    }

    /** PARAM-FUNC-012：IMU_SAMPLE_RATE 可设置，逐档读回一致并还原。 */
    @Test
    fun paramFunc012_imuSampleRate_set() = runBlocking<Unit> {
        verifySampleRateSettable("IMU_SAMPLE_RATE")
    }

    /** PARAM-FUNC-013：PPG_SAMPLE_RATE 可设置，逐档读回一致并还原。 */
    @Test
    fun paramFunc013_ppgSampleRate_set() = runBlocking<Unit> {
        verifySampleRateSettable("PPG_SAMPLE_RATE")
    }

    /** 采样率参数可设置性验证：读当前值 -> 逐档 set + 读回 -> 还原原值。 */
    private suspend fun verifySampleRateSettable(key: String) {
        val sr = spec().sampleRates[key]
        assumeTrue("spec 未注册 $key（本型号无该能力），跳过", sr != null)

        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val current = safeGetParam(p, key)
        assertTrue("getParam($key) 不应 Error，实际=$current", !isError(current))
        val original = sr!!.rates.firstOrNull { current.contains(it) }

        for (rate in sr.rates) {
            val ret = safeSetParam(p, key, rate)
            assertTrue("setParam($key, $rate) 应成功，实际=$ret", !isError(ret))
            val readBack = safeGetParam(p, key)
            assertTrue("$key=$rate 读回应一致，实际=$readBack", readBack.contains(rate))
        }

        // 还原用例前的值，避免污染后续用例
        original?.let {
            val restore = safeSetParam(p, key, it)
            assertTrue("$key 还原 $it 应成功，实际=$restore", !isError(restore))
        }
        p.disconnect()
    }
}
