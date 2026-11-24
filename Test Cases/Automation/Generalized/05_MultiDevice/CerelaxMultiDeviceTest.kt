package com.oymotion.sensorcapiktdemo.cerelaxpro

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cerelax 05 多设备同步（MULTI-*），spec 驱动适配。
 *
 * Android 与 Python 差异适配：
 *   - Python `multiStartDataNotification/multiStopDataNotification` 返回 {mac: bool}；
 *     Android 为异步回调 `(List<SensorProfile>, ...) -> onMultiResult(Map<mac,bool>, Map<mac,err>)`，
 *     这里用 [multiStart]/[multiStop] 挂起包装，等待回调返回结果。
 *   - Python 用子进程 + SENSOR_SDK_BLE_BACKEND 切换 bumble/bleak 后端；Android 只有原生 BLE 后端，
 *     MULTI-FUNC-008 标 @Ignore（semi-auto，需人工切换 dongle，Android 不适用）。
 *   - 目标设备匹配走 TestConfig.DEVICES 清单（mac 精确 > 广播名后四位 identity），
 *     而非单一 TARGET_IDENTITY；缺设备用 Assume 跳过，不判失败。
 *   - Python startTimeStamp 为 uint32 ms；Android getStartTimeStamp()/getDelay() 返回 int，
 *     散布按 (x & 0xFFFFFFFF) 归一化后求差。
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
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        controller.registerBleBridge(context)
        TestConfig.bind(context)
    }

    // =====================================================================================
    // 内部辅助
    // =====================================================================================

    /** 命中 TestConfig.DEVICES 清单（mac 精确 > 广播名后四位 identity），覆盖全部清单设备。 */
    private fun matchesAnyConfigured(device: BleDevice): Boolean = TestConfig.matchesKnown(device)

    /** 多轮扫描收集命中清单的设备，按 mac 去重，返回前 [required] 台。 */
    private suspend fun scanAllTargets(required: Int): List<BleDevice> {
        val seen = LinkedHashMap<String, BleDevice>()
        for (attempt in 0..3) {
            controller.scan(TestConfig.SCAN_TIMEOUT_MS).forEach { d ->
                if (matchesAnyConfigured(d)) seen[d.mac.uppercase()] = d
            }
            if (seen.size >= required) break
            if (attempt < 3) {
                CerelaxTestSupport.log("已匹配 ${seen.size}/$required 台，${TestConfig.SCAN_RETRY_INTERVAL_MS}ms 后重扫（${attempt + 1}/3）...")
                delay(TestConfig.SCAN_RETRY_INTERVAL_MS)
            }
        }
        return seen.values.toList()
    }

    /** requireSensor + connect + 等 Ready + init，任一步失败跳过该台。 */
    private suspend fun connectInitAll(devices: List<BleDevice>): List<SensorProfile> {
        val out = mutableListOf<SensorProfile>()
        for (d in devices) {
            val p = controller.requireSensor(d.mac) ?: continue
            if (!p.connect()) continue
            if (!CerelaxTestSupport.waitForReady(p)) continue
            if (!p.init(
                    TestConfig.PACKAGE_SAMPLE_COUNT,
                    TestConfig.INIT_TIMEOUT_MS,
                    TestConfig.POWER_REFRESH_INTERVAL_MS
                )
            ) continue
            out.add(p)
        }
        return out
    }

    /** 挂起等待 multiStart 回调结果。 */
    private suspend fun multiStart(
        profiles: List<SensorProfile>,
        timeoutMs: Int,
        maxDelayDispersionMs: Int,
        maxAttempts: Int
    ): Pair<Map<String, Boolean>, Map<String, String>> {
        val deferred = CompletableDeferred<Pair<Map<String, Boolean>, Map<String, String>>>()
        controller.multiStartDataNotification(profiles, timeoutMs, maxDelayDispersionMs, maxAttempts) { results, errors ->
            deferred.complete(results to errors)
        }
        // 回调不来时不能无限挂起：超时后按失败抛出，避免整轮 instrumentation 卡死
        return withTimeout(timeoutMs.toLong() + 5_000) { deferred.await() }
    }

    /** 挂起等待 multiStop 回调结果。 */
    private suspend fun multiStop(
        profiles: List<SensorProfile>,
        timeoutMs: Int
    ): Pair<Map<String, Boolean>, Map<String, String>> {
        val deferred = CompletableDeferred<Pair<Map<String, Boolean>, Map<String, String>>>()
        controller.multiStopDataNotification(profiles, timeoutMs) { results, errors ->
            deferred.complete(results to errors)
        }
        // 同上：回调丢失时超时退出，避免挂死
        return withTimeout(timeoutMs.toLong() + 5_000) { deferred.await() }
    }

    /** 从 multi 结果里按 mac（忽略大小写）取单台结果。 */
    private fun resultOf(results: Map<String, Boolean>, mac: String): Boolean? =
        results.entries.firstOrNull { it.key.equals(mac, ignoreCase = true) }?.value

    /** 断开前先停流，忽略清理异常。 */
    private suspend fun cleanup(profiles: List<SensorProfile>) {
        if (profiles.isNotEmpty()) {
            try { multiStop(profiles, TestConfig.SCAN_TIMEOUT_MS) } catch (_: Exception) {}
        }
        profiles.forEach { try { it.disconnect() } catch (_: Exception) {} }
    }

    private fun mac(p: SensorProfile): String = p.device.mac.uppercase()

    /** 多设备首包收集器：记录每台设备的 startTimeStamp 与 delay，并计算跨设备散布。 */
    private class FirstBatchCollector {
        private val firstStamp = java.util.concurrent.ConcurrentHashMap<String, Int>()
        private val delays = java.util.concurrent.ConcurrentHashMap<String, Int>()

        fun onData(profile: SensorProfile, dataList: List<SensorData>) {
            val mac = profile.device.mac.uppercase()
            if (firstStamp.containsKey(mac)) return
            val d = dataList.firstOrNull() ?: return
            firstStamp.putIfAbsent(mac, d.startTimeStamp)
            delays.putIfAbsent(mac, d.delay)
        }

        fun stampOf(mac: String): Int? = firstStamp[mac.uppercase()]
        fun delayOf(mac: String): Int? = delays[mac.uppercase()]

        /** 跨设备 startTimeStamp 散布（ms）；不足 2 台返回 null。 */
        fun spreadMs(): Int? {
            if (firstStamp.size < 2) return null
            val longs = firstStamp.values.map { it.toLong() and 0xFFFFFFFFL }
            val max = longs.maxOrNull()!!
            val min = longs.minOrNull()!!
            return (max - min).toInt()
        }
    }

    // =====================================================================================
    // MULTI-FUNC-* 用例
    // =====================================================================================

    /** MULTI-FUNC-001：两台同时起流。 */
    @Test
    fun multiFunc001_twoSimultaneousStart() = runBlocking<Unit> {
        val devices = scanAllTargets(2)
        assumeTrue("需 ≥2 台目标设备，实际 ${devices.size} 台", devices.size >= 2)
        val profiles = connectInitAll(devices.take(2))
        assumeTrue("需 ≥2 台连接就绪，实际 ${profiles.size} 台", profiles.size >= 2)
        try {
            val (results, errors) = multiStart(profiles, 30000, 5, 3)
            assertTrue("multiStart 应覆盖两台，实际=$results", results.size == 2)
            results.values.forEach { assertTrue("每台 multiStart 应成功（errors=$errors）", it) }
            profiles.forEach { assertTrue("${mac(it)} 应处于传输中", it.isDataTransfering()) }
        } finally {
            cleanup(profiles)
        }
    }

    /** MULTI-FUNC-002：两台同时停流。 */
    @Test
    fun multiFunc002_twoSimultaneousStop() = runBlocking<Unit> {
        val devices = scanAllTargets(2)
        assumeTrue("需 ≥2 台目标设备，实际 ${devices.size} 台", devices.size >= 2)
        val profiles = connectInitAll(devices.take(2))
        assumeTrue("需 ≥2 台连接就绪，实际 ${profiles.size} 台", profiles.size >= 2)
        try {
            val (startResults, _) = multiStart(profiles, 30000, 5, 3)
            assumeTrue("起流失败，跳过停流断言", startResults.values.all { it })
            val (stopResults, stopErrors) = multiStop(profiles, 10000)
            assertTrue("multiStop 应覆盖两台，实际=$stopResults", stopResults.size == 2)
            stopResults.values.forEach { assertTrue("每台 multiStop 应成功（errors=$stopErrors）", it) }
            profiles.forEach { assertFalse("${mac(it)} 应已停流", it.isDataTransfering()) }
        } finally {
            cleanup(profiles)
        }
    }

    /** MULTI-FUNC-003：某台未 Ready（未 init）不影响其余起流。 */
    @Test
    fun multiFunc003_notReadyDoesNotAffectOthers() = runBlocking<Unit> {
        val devices = scanAllTargets(2)
        assumeTrue("需 ≥2 台目标设备，实际 ${devices.size} 台", devices.size >= 2)

        val r1 = controller.requireSensor(devices[0].mac)
        val r2 = controller.requireSensor(devices[1].mac)
        assumeTrue("两台均需 requireSensor 成功", r1 != null && r2 != null)
        val p1 = r1!!
        val p2 = r2!!
        assumeTrue("两台均需 connect 成功", p1.connect() && p2.connect())
        assumeTrue(
            "两台均需到达 Ready",
            CerelaxTestSupport.waitForReady(p1) && CerelaxTestSupport.waitForReady(p2)
        )
        assumeTrue(
            "p1 init 失败",
            p1.init(TestConfig.PACKAGE_SAMPLE_COUNT, TestConfig.INIT_TIMEOUT_MS, TestConfig.POWER_REFRESH_INTERVAL_MS)
        )
        // p2 已连接 Ready 但不 init
        try {
            val (results, _) = multiStart(listOf(p1, p2), 30000, 5, 3)
            val mac1 = p1.device.mac
            val mac2 = p2.device.mac
            assertTrue("已 init 台应起流成功（results=$results）", resultOf(results, mac1) == true && p1.isDataTransfering())
            assertTrue("未 init 台应起流失败（results=$results）", resultOf(results, mac2) != true)
        } finally {
            cleanup(listOf(p1, p2))
        }
    }

    /** MULTI-FUNC-004：首包时差 ≤ maxDelayDispersionMs（默认 5ms，SDK 内部自动重试）。 */
    @Test
    fun multiFunc004_firstPacketDispersionWithinMax() = runBlocking<Unit> {
        val devices = scanAllTargets(2)
        assumeTrue("需 ≥2 台目标设备，实际 ${devices.size} 台", devices.size >= 2)
        val profiles = connectInitAll(devices.take(2))
        assumeTrue("需 ≥2 台连接就绪，实际 ${profiles.size} 台", profiles.size >= 2)

        val collector = FirstBatchCollector()
        profiles.forEach { it.setOnDataListener(collector::onData) }
        try {
            val (results, errors) = multiStart(profiles, 30000, 5, 3)
            assertTrue("两台均起流（results=$results errors=$errors）", results.values.all { it })

            delay(TestConfig.COLLECT_SECONDS * 1000L)

            profiles.forEach {
                val m = mac(it)
                assertTrue("设备 $m 应收到带 startTimeStamp 的首包", collector.stampOf(m) != null)
            }
            val spread = collector.spreadMs()
            if (spread != null) {
                CerelaxTestSupport.log("跨设备 startTimeStamp 散布 = $spread ms")
                assertTrue("散布应 ≤ 5ms，实际 $spread ms", spread <= 5)
            }
        } finally {
            cleanup(profiles)
        }
    }

    /** MULTI-FUNC-005：maxDelayDispersionMs=-1 跳过时差校验。 */
    @Test
    fun multiFunc005_skipDispersionCheck() = runBlocking<Unit> {
        val devices = scanAllTargets(2)
        assumeTrue("需 ≥2 台目标设备，实际 ${devices.size} 台", devices.size >= 2)
        val profiles = connectInitAll(devices.take(2))
        assumeTrue("需 ≥2 台连接就绪，实际 ${profiles.size} 台", profiles.size >= 2)
        try {
            val (results, errors) = multiStart(profiles, 30000, -1, 3)
            assertTrue("两台均起流（results=$results errors=$errors）", results.values.all { it })
        } finally {
            cleanup(profiles)
        }
    }

    /** MULTI-FUNC-006：混合型号放宽参数（需 Cerelax + 其它型号；无混合型号则跳过）。 */
    @Test
    fun multiFunc006_mixedModelRelaxedParams() = runBlocking<Unit> {
        val devices = scanAllTargets(2)
        assumeTrue("需 ≥2 台目标设备，实际 ${devices.size} 台", devices.size >= 2)
        val profiles = connectInitAll(devices.take(2))
        assumeTrue("需 ≥2 台连接就绪，实际 ${profiles.size} 台", profiles.size >= 2)

        val models = profiles.map { it.getDeviceInfo()?.modelName }.toSet()
        assumeTrue("需混合型号（实际 $models），否则跳过", models.size >= 2 && null !in models)

        try {
            val (results, errors) = multiStart(profiles, 60000, -1, 5)
            assertTrue("混合型号两台均起流（results=$results errors=$errors）", results.values.all { it })
        } finally {
            cleanup(profiles)
        }
    }

    /** MULTI-FUNC-007：已起流设备再次 multiStart（restart 语义）。 */
    @Test
    fun multiFunc007_restartSemantics() = runBlocking<Unit> {
        val devices = scanAllTargets(2)
        assumeTrue("需 ≥2 台目标设备，实际 ${devices.size} 台", devices.size >= 2)
        val profiles = connectInitAll(devices.take(2))
        assumeTrue("需 ≥2 台连接就绪，实际 ${profiles.size} 台", profiles.size >= 2)

        val p1 = profiles[0]
        val p2 = profiles[1]
        try {
            assertTrue("p1 单独起流失败", p1.startDataNotification())
            delay(2000)

            val (results, errors) = multiStart(profiles, 30000, 5, 3)
            assertTrue("已起流 p1 应 restart 成功", resultOf(results, p1.device.mac) == true)
            assertTrue("空闲 p2 应起流成功", resultOf(results, p2.device.mac) == true)
            CerelaxTestSupport.log("multiStart restart -> results=$results errors=$errors")
        } finally {
            cleanup(profiles)
        }
    }

    /** MULTI-FUNC-008：两后端各测对齐下发（semi-auto，Android 仅原生 BLE 后端，不适用）。 */
    @Ignore("Android 无 bumble/bleak 多后端切换能力（对应 Python 子进程 SENSOR_SDK_BLE_BACKEND）")
    @Test
    fun multiFunc008_twoBackends() = runBlocking<Unit> {
        // 无操作：Android 侧无法切换到 bumble dongle 后端。
    }

    /** MULTI-FUNC-009：跨设备 startTimeStamp 散布与 getDelay 符合预期。 */
    @Test
    fun multiFunc009_timestampSpreadAndDelay() = runBlocking<Unit> {
        val devices = scanAllTargets(2)
        assumeTrue("需 ≥2 台目标设备，实际 ${devices.size} 台", devices.size >= 2)
        val profiles = connectInitAll(devices.take(2))
        assumeTrue("需 ≥2 台连接就绪，实际 ${profiles.size} 台", profiles.size >= 2)

        val collector = FirstBatchCollector()
        profiles.forEach { it.setOnDataListener(collector::onData) }
        try {
            val (results, errors) = multiStart(profiles, 30000, 5, 3)
            assertTrue("两台均起流（results=$results errors=$errors）", results.values.all { it })

            delay(TestConfig.COLLECT_SECONDS * 1000L)

            profiles.forEach {
                val m = mac(it)
                val ts = collector.stampOf(m)
                val dl = collector.delayOf(m)
                CerelaxTestSupport.log("设备 $m: startTimeStamp=$ts delay=$dl")
                assertTrue("设备 $m startTimeStamp 应非空", ts != null)
                assertTrue("设备 $m delay 应 ≥ 0", dl != null && dl >= 0)
            }
            val spread = collector.spreadMs()
            if (spread != null) {
                CerelaxTestSupport.log("跨设备 startTimeStamp 散布 = $spread ms")
                assertTrue("散布应 ≤ 5ms，实际 $spread ms", spread <= 5)
            }
        } finally {
            cleanup(profiles)
        }
    }

    /** MULTI-BND-001：multiStart/multiStop 空集合 / 单台 / 非法参数不崩溃。 */
    @Test
    fun multiBnd001_emptyAndIllegalArgs_noCrash() = runBlocking<Unit> {
        // 空集合：直接异步调用，验证不抛同步异常
        try {
            controller.multiStartDataNotification(emptyList(), 30000, 5, 3) { _, _ -> }
            controller.multiStopDataNotification(emptyList(), 10000) { _, _ -> }
        } catch (t: Throwable) {
            fail("空集合 multiStart/multiStop 不应崩溃: $t")
        }

        // 单台 + 非法参数：需至少一台设备
        val devices = scanAllTargets(1)
        assumeTrue("需 ≥1 台目标设备，实际 ${devices.size} 台", devices.isNotEmpty())
        val connected = connectInitAll(listOf(devices[0])).firstOrNull()
        assumeTrue("需 1 台连接就绪", connected != null)
        val profile = connected!!

        try {
            // 单台不崩溃（成功/失败均可）
            val (singleResults, _) = multiStart(listOf(profile), 30000, 5, 3)
            CerelaxTestSupport.log("单台 multiStart -> $singleResults")

            // 非法参数不抛同步异常
            try {
                controller.multiStartDataNotification(listOf(profile), 0, 5, 3) { _, _ -> }
                controller.multiStartDataNotification(listOf(profile), 30000, -2, 3) { _, _ -> }
                controller.multiStartDataNotification(listOf(profile), 30000, 5, 0) { _, _ -> }
                controller.multiStopDataNotification(listOf(profile), 0) { _, _ -> }
            } catch (t: Throwable) {
                fail("非法参数 multiStart/multiStop 不应崩溃: $t")
            }
        } finally {
            cleanup(listOf(profile))
        }
    }

    /** MULTI-ROB-001：multiStop 重复调用幂等。 */
    @Test
    fun multiRob001_multiStopIdempotent() = runBlocking<Unit> {
        val devices = scanAllTargets(2)
        assumeTrue("需 ≥2 台目标设备，实际 ${devices.size} 台", devices.size >= 2)
        val profiles = connectInitAll(devices.take(2))
        assumeTrue("需 ≥2 台连接就绪，实际 ${profiles.size} 台", profiles.size >= 2)
        try {
            val (startResults, _) = multiStart(profiles, 30000, 5, 3)
            assumeTrue("起流失败，跳过幂等断言", startResults.values.all { it })

            val (stop1, err1) = multiStop(profiles, 10000)
            val (stop2, err2) = multiStop(profiles, 10000)
            CerelaxTestSupport.log("multiStop#1 -> $stop1 ($err1)；multiStop#2 -> $stop2 ($err2)")
            profiles.forEach { assertFalse("${mac(it)} 应保持停流", it.isDataTransfering()) }
        } finally {
            cleanup(profiles)
        }
    }
}
