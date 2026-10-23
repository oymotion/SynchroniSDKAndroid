package com.oymotion.sensorcapiktdemo.cerelaxpro

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Cerelax 03 数据流（DATA-*），由 Python gForceUltra/03_Dataflow 1:1 迁移，spec 驱动适配。
 *
 * 型号差异适配（spec 驱动）：
 *   - 正向流覆盖走 [CerelaxSpec.supportedStreamKeys]（Cerelax = EEG/PPG/SpO2/IMU/ACC/GYRO/Imp 共 7 流），
 *     而非 gForceUltra 的 EMG/GEST；通道数硬断言来自 spec `channels`。
 *   - gForceUltra 专属用例（DATA-FUNC-011 EMG/GEST 互斥）在 Cerelax 无能力，标 @Ignore。
 *
 * Android 与 Python 差异：
 *   - Python 子进程崩溃隔离（DATA-BND-*）-> Android 直接调用 + @Test(timeout=...)；native 崩溃即测试进程 fail。
 *   - Python SensorData.channelSamples -> Android 无此批量属性，用 getData/getChannelSample 单点遍历替代。
 *   - Python SensorData 可变（改副本验证 clone）-> Android SensorData 不可变无 setter，
 *     DATA-FUNC-012 退化为「clone 返回独立对象且内容一致」。
 *   - SensorData 元数据以属性访问（deviceMac/dataType/sampleRate/channelCount/sampleCount/channelMask/
 *     lostPackageCount/startSampleIndex），isDataValid()/isChannelEnabled()/isLost()/getData()/getChannelSample()/
 *     getSampleIndex() 为方法（与 README/MainActivity 一致）。
 */
@RunWith(AndroidJUnit4::class)
class CerelaxDataflowTest {

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

    /** 断开再重连，保证 init 每次状态干净（对应 Python 子进程间间隙）。 */
    private suspend fun reconnect(p: SensorProfile) {
        try { p.disconnect() } catch (_: Exception) {}
        assertTrue("重连 connect 应成功", p.connect())
        assertTrue("重连未到达 Ready", CerelaxTestSupport.waitForReady(p))
    }

    // =====================================================================================
    // DATA-BND-*：init 参数边界
    // =====================================================================================

    /** DATA-BND-001：init(packageSampleCount) 6 点边界。 */
    @Test(timeout = 300000)
    fun dataBnd001_initPackageSampleCount_boundary() = runBlocking {
        val device = requireTarget()
        val p = controller.requireSensor(device.mac)!!

        val max = Int.MAX_VALUE.toLong()
        val values = listOf(1L, 2L, max - 1, max, 0L, max + 1)
        val legal = setOf(1L, 2L, max - 1, max)

        for (v in values) {
            val pkg = v.toInt() // MAX+1 溢出为 Int.MIN_VALUE（模拟越界入参）
            reconnect(p)
            val ret: Boolean? = try {
                p.init(pkg, TestConfig.INIT_TIMEOUT_MS, TestConfig.POWER_REFRESH_INTERVAL_MS)
            } catch (e: Exception) {
                CerelaxTestSupport.log("init($v) 抛异常 ${e.javaClass.simpleName}")
                null
            }
            if (v in legal) {
                assertFalse("合法值 $v 不应抛异常", ret == null)
                if (ret == false) {
                    CerelaxTestSupport.log("init($v) 返回 false（可能超出 SDK 实际上限，需确认，不判 FAIL）")
                }
            } else {
                assertTrue("非法值 $v 应被拒绝（返回 false 或抛异常），实际=$ret", ret != true)
            }
        }
        try { p.disconnect() } catch (_: Exception) {}
    }

    /** DATA-BND-002：init(_, powerRefreshInterval) 6 点边界。 */
    @Test(timeout = 300000)
    fun dataBnd002_initPowerRefreshInterval_boundary() = runBlocking {
        val device = requireTarget()
        val p = controller.requireSensor(device.mac)!!

        val max = Int.MAX_VALUE.toLong()
        val values = listOf(0L, 1L, max - 1, max, -1L, max + 1)
        val legal = setOf(0L, 1L, max - 1, max)

        for (v in values) {
            val power = v.toInt()
            reconnect(p)
            val ret: Boolean? = try {
                p.init(TestConfig.PACKAGE_SAMPLE_COUNT, TestConfig.INIT_TIMEOUT_MS, power)
            } catch (e: Exception) {
                CerelaxTestSupport.log("init(_, $v) 抛异常 ${e.javaClass.simpleName}")
                null
            }
            if (v in legal) {
                assertFalse("合法值 $v 不应抛异常", ret == null)
                if (ret == false) {
                    CerelaxTestSupport.log("init(_, $v) 返回 false（可能超出 SDK 实际上限，需确认，不判 FAIL）")
                }
            } else {
                assertTrue("非法值 $v 应被拒绝（返回 false 或抛异常），实际=$ret", ret != true)
            }
        }
        try { p.disconnect() } catch (_: Exception) {}
    }

    // =====================================================================================
    // DATA-FUNC-*：起流/停流/数据访问
    // =====================================================================================

    /** DATA-FUNC-001：init 成功，hasInited==True。 */
    @Test
    fun dataFunc001_initSuccess_hasInitedTrue() = runBlocking {
        val device = requireTarget()
        val p = connectReady(device)
        assertFalse("init 前 hasInited 应为 false", p.hasInited())
        assertTrue("init 应成功", init(p))
        assertTrue("init 后 hasInited 应为 true", p.hasInited())
        p.disconnect()
    }

    /** DATA-FUNC-002：未 Ready 前 init/startDataNotification 失败。 */
    @Test(timeout = 60000)
    fun dataFunc002_beforeReady_initStartRejected() = runBlocking {
        val device = requireTarget()
        val p = controller.requireSensor(device.mac)!!
        assertTrue("前置：未连接 deviceState 应非 READY", p.getDeviceState() != DeviceState.READY)
        assertFalse("未 Ready init 应返回 false", init(p))
        assertFalse("未 Ready startDataNotification 应返回 false", p.startDataNotification())
    }

    /** DATA-FUNC-003：startDataNotification 后 isDataTransfering==True，回调按批触发。 */
    @Test
    fun dataFunc003_startNotification_receivesData() = runBlocking {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val latch = CountDownLatch(1)
        val batches = AtomicInteger(0)
        p.setOnDataListener { _, dataList ->
            if (dataList.isNotEmpty()) {
                batches.incrementAndGet()
                latch.countDown()
            }
        }

        assertTrue("startDataNotification 失败", p.startDataNotification())
        assertTrue("起流后 isDataTransfering 应为 true", p.isDataTransfering())
        assertTrue("${TestConfig.DATA_TIMEOUT_SEC}s 内未收到数据", latch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS))
        assertTrue("回调批次数应 > 0", batches.get() > 0)

        p.stopDataNotification()
        p.disconnect()
    }

    /** DATA-FUNC-004：stopDataNotification 后停止。 */
    @Test
    fun dataFunc004_stopNotification_stops() = runBlocking {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val latch = CountDownLatch(1)
        val batches = AtomicInteger(0)
        p.setOnDataListener { _, dataList ->
            if (dataList.isNotEmpty()) {
                batches.incrementAndGet()
                latch.countDown()
            }
        }

        assertTrue("startDataNotification 失败", p.startDataNotification())
        assertTrue("未收到数据", latch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS))
        assertTrue("stopDataNotification 失败", p.stopDataNotification())
        assertTrue("停流后 isDataTransfering 应为 false", !p.isDataTransfering())

        val before = batches.get()
        Thread.sleep(2000)
        val after = batches.get()
        assertTrue("停流后回调应停止（before=$before after=$after）", after == before)

        p.disconnect()
    }

    /** DATA-FUNC-005：spec 支持流逐一收到对应 SensorData，通道数硬断言。 */
    @Test(timeout = 300000)
    fun dataFunc005_supportedStreams_eachYieldsData() = runBlocking {
        val spec = TestConfig.specForIdentity(TestConfig.TARGET_IDENTITY)
        val supported = CerelaxSpec.supportedStreamKeys(spec)
        assertTrue("spec 应注册支持流", supported.isNotEmpty())

        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        for (key in supported) {
            val expectedType = CerelaxSpec.KEY_TO_DATA_TYPE[key] ?: continue
            val expectedCh = CerelaxSpec.expectedChannels(spec, key) ?: continue

            // 先关所有支持流，再单开当前流，避免串扰
            for (k in supported) { try { p.setParam(k, "OFF") } catch (_: Exception) {} }
            p.setParam(key, "ON")

            val latch = CountDownLatch(1)
            val gotCh = AtomicInteger(-1)
            p.setOnDataListener { _, dataList ->
                val d = dataList.firstOrNull { it.dataType == expectedType }
                if (d != null) {
                    gotCh.set(d.channelCount)
                    latch.countDown()
                }
            }

            assertTrue("startDataNotification 失败", p.startDataNotification())
            val ok = latch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS)
            assertTrue("流 $key 未在 ${TestConfig.DATA_TIMEOUT_SEC}s 内收到数据", ok)
            assertTrue(
                "流 $key 通道数应 = $expectedCh，实际=${gotCh.get()}",
                gotCh.get() == expectedCh
            )
            p.stopDataNotification()
        }
        p.disconnect()
    }

    /** DATA-FUNC-006：SensorData 元数据合法。 */
    @Test
    fun dataFunc006_metadataValid() = runBlocking {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val latch = CountDownLatch(1)
        val first = AtomicReference<SensorData?>(null)
        p.setOnDataListener { _, dataList ->
            val d = dataList.firstOrNull { it.getDataType() == DataType.NTF_EEG }
            if (d != null) {
                first.compareAndSet(null, d.clone())
                latch.countDown()
            }
        }

        assertTrue("startDataNotification 失败", p.startDataNotification())
        assertTrue("未收到 EEG 数据", latch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS))

        val d = first.get()
        assertTrue("EEG SensorData 不应为 null", d != null)
        val sd = d!!
        assertTrue("deviceMac 应为目标设备", sd.deviceMac.equals(device.mac, ignoreCase = true))
        assertTrue("channelCount 应 > 0（Cerelax EEG 预期 2）", sd.channelCount > 0)
        assertTrue("sampleRate 应 > 0", sd.sampleRate > 0f)
        assertTrue("sampleCount 应 > 0", sd.sampleCount > 0)
        assertTrue("channelMask 应非 0", sd.channelMask != 0L)
        assertTrue("lostPackageCount 应 >= 0", sd.lostPackageCount >= 0)
        assertTrue("startSampleIndex 应 >= 0", sd.startSampleIndex >= 0)
        assertTrue("isDataValid 应为 true", sd.isDataValid())

        p.stopDataNotification()
        p.disconnect()
    }

    /** DATA-FUNC-007：getChannelSample/单点访问器一致，sampleIndex 单调。 */
    @Test
    fun dataFunc007_channelSampleConsistent_sampleIndexMonotonic() = runBlocking {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val latch = CountDownLatch(1)
        val consistent = AtomicBoolean(true)
        val monotonic = AtomicBoolean(true)
        val lastByChannel = ConcurrentHashMap<Int, AtomicLong>()

        p.setOnDataListener { _, dataList ->
            for (d in dataList) {
                for (ch in 0 until d.channelCount) {
                    val last = lastByChannel.getOrPut(ch) { AtomicLong(-1) }
                    for (i in 0 until d.sampleCount) {
                        val s = d.getChannelSample(ch, i)
                        if (s != null) {
                            if (s.sampleIndex != d.getSampleIndex(ch, i)) consistent.set(false)
                            if (s.data != d.getData(ch, i)) consistent.set(false)
                            val idx = d.getSampleIndex(ch, i).toLong()
                            if (idx <= last.get()) monotonic.set(false)
                            last.set(idx)
                        }
                    }
                }
                latch.countDown()
            }
        }

        assertTrue("startDataNotification 失败", p.startDataNotification())
        assertTrue("未收到数据", latch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS))
        Thread.sleep(TestConfig.COLLECT_SECONDS * 1000L)
        p.stopDataNotification()

        assertTrue("getChannelSample 与单点访问器应一致", consistent.get())
        assertTrue("sampleIndex 应单调递增", monotonic.get())
        p.disconnect()
    }

    /** DATA-FUNC-008：isLost 与 lostPackageCount 一致。 */
    @Test
    fun dataFunc008_isLostConsistentWithLostPackageCount() = runBlocking {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val latch = CountDownLatch(1)
        val lostPoints = AtomicInteger(0)
        val lostPackages = AtomicLong(0)

        p.setOnDataListener { _, dataList ->
            for (d in dataList) {
                lostPackages.addAndGet(d.lostPackageCount.toLong())
                for (ch in 0 until d.channelCount) {
                    for (i in 0 until d.sampleCount) {
                        if (d.isLost(ch, i)) lostPoints.incrementAndGet()
                    }
                }
                latch.countDown()
            }
        }

        assertTrue("startDataNotification 失败", p.startDataNotification())
        assertTrue("未收到数据", latch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS))
        Thread.sleep(TestConfig.COLLECT_SECONDS * 1000L)
        p.stopDataNotification()

        val lp = lostPackages.get()
        val pts = lostPoints.get()
        CerelaxTestSupport.log("lostPackageCount=$lp, isLost 点数=$pts")
        assertTrue(
            "isLost 标记与 lostPackageCount 应同零或同非零（lp=$lp pts=$pts）",
            (lp == 0L && pts == 0) || (lp > 0L && pts > 0)
        )
        p.disconnect()
    }

    /** DATA-FUNC-009：每批样本数 ≈ packageSampleCount。 */
    @Test
    fun dataFunc009_batchSizeApproxPackageCount() = runBlocking {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val latch = CountDownLatch(1)
        val batchSizes = CopyOnWriteArrayList<Int>()
        p.setOnDataListener { _, dataList ->
            dataList.forEach { batchSizes.add(it.sampleCount) }
            if (dataList.isNotEmpty()) latch.countDown()
        }

        assertTrue("startDataNotification 失败", p.startDataNotification())
        assertTrue("未收到数据", latch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS))
        Thread.sleep(TestConfig.COLLECT_SECONDS * 1000L)
        p.stopDataNotification()

        assertTrue("应至少收到一批数据", batchSizes.isNotEmpty())
        val packageCount = TestConfig.PACKAGE_SAMPLE_COUNT
        val close = batchSizes.count { abs(it - packageCount) <= packageCount * 0.3 }
        CerelaxTestSupport.log("批大小样本=$batchSizes（package=$packageCount）")
        assertTrue(
            "多数批应≈$packageCount（实际=$batchSizes）",
            close >= batchSizes.size * 0.7
        )
        p.disconnect()
    }

    /** DATA-FUNC-010：NTF_IMU 聚合流 6 通道布局。 */
    @Test
    fun dataFunc010_imuAggregated6Channels() = runBlocking {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        p.setParam("NTF_IMU", "ON")
        val latch = CountDownLatch(1)
        val gotCh = AtomicInteger(-1)
        p.setOnDataListener { _, dataList ->
            val d = dataList.firstOrNull { it.dataType == DataType.NTF_IMU }
            if (d != null) {
                gotCh.set(d.channelCount)
                latch.countDown()
            }
        }

        assertTrue("startDataNotification 失败", p.startDataNotification())
        assertTrue("未收到 IMU 数据", latch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS))
        assertTrue("NTF_IMU 应为聚合 6 通道，实际=${gotCh.get()}", gotCh.get() == 6)

        p.stopDataNotification()
        p.disconnect()
    }

    /** DATA-FUNC-011：gForceUltra 传统 EMG 设备 NTF_GEST 与 NTF_EMG 互斥 —— Cerelax 无 EMG/GEST，跳过。 */
    @Ignore("gForceUltra 传统 EMG 设备专属；Cerelax spec 不支持 EMG/GEST，无对应能力")
    @Test
    fun dataFunc011_gestEmgMutualExclusion() = Unit

    /** DATA-FUNC-012：SensorData.clone 深拷贝（Android 不可变，验证独立对象 + 内容一致）。 */
    @Test
    fun dataFunc012_cloneDeepCopy() = runBlocking {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val latch = CountDownLatch(1)
        val original = AtomicReference<SensorData?>(null)
        val cloned = AtomicReference<SensorData?>(null)
        p.setOnDataListener { _, dataList ->
            val d = dataList.firstOrNull { it.getDataType() == DataType.NTF_EEG }
            if (d != null) {
                original.compareAndSet(null, d)
                cloned.compareAndSet(null, d.clone())
                latch.countDown()
            }
        }

        assertTrue("startDataNotification 失败", p.startDataNotification())
        assertTrue("未收到 EEG 数据", latch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS))

        val o = original.get()
        val c = cloned.get()
        assertTrue("原始/克隆对象均不应为 null", o != null && c != null)
        val oo = o!!
        val cc = c!!
        assertTrue("clone 应为独立对象（非同一引用）", cc !== oo)
        assertTrue("clone 内容应一致（dataType）", cc.getDataType() == oo.getDataType())
        assertTrue("clone 内容应一致（channelCount）", cc.channelCount == oo.channelCount)
        assertTrue("clone 内容应一致（sampleCount）", cc.sampleCount == oo.sampleCount)
        assertTrue(
            "clone 首点数据应一致",
            cc.getData(0, 0) == oo.getData(0, 0)
        )

        p.stopDataNotification()
        p.disconnect()
    }

    /** DATA-FUNC-013：Sample 新增字段与单点访问器一致。 */
    @Test
    fun dataFunc013_sampleFieldsConsistent() = runBlocking {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val latch = CountDownLatch(1)
        val consistent = AtomicBoolean(true)
        p.setOnDataListener { _, dataList ->
            for (d in dataList) {
                for (ch in 0 until d.channelCount) {
                    for (i in 0 until d.sampleCount) {
                        val s = d.getChannelSample(ch, i)
                        if (s != null) {
                            if (s.channelIndex != ch) consistent.set(false)
                            if (s.sampleIndex != d.getSampleIndex(ch, i)) consistent.set(false)
                            if (s.data != d.getData(ch, i)) consistent.set(false)
                            if (s.rawData != d.getRawData(ch, i)) consistent.set(false)
                            if (s.impedance != d.getImpedance(ch, i)) consistent.set(false)
                            if (s.saturation != d.getSaturation(ch, i)) consistent.set(false)
                        }
                    }
                }
                latch.countDown()
            }
        }

        assertTrue("startDataNotification 失败", p.startDataNotification())
        assertTrue("未收到数据", latch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS))
        Thread.sleep(TestConfig.COLLECT_SECONDS * 1000L)
        p.stopDataNotification()

        assertTrue("Sample 新增字段应与单点访问器一致", consistent.get())
        p.disconnect()
    }

    // =====================================================================================
    // DATA-PERF-*：采样率/长稳
    // =====================================================================================

    /** DATA-PERF-001：实测采样率与标称偏差 ≤ 容差。 */
    @Test(timeout = 120000)
    fun dataPerf001_measuredRate_withinTolerance() = runBlocking {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val latch = CountDownLatch(1)
        val startIdx = AtomicLong(-1)
        val endIdx = AtomicLong(-1)
        val nominal = AtomicReference(0f)
        p.setOnDataListener { _, dataList ->
            val d = dataList.firstOrNull { it.getDataType() == DataType.NTF_EEG }
            if (d != null) {
                nominal.set(d.sampleRate)
                val s = d.startSampleIndex.toLong()
                if (startIdx.get() < 0) startIdx.set(s)
                endIdx.set(s + d.sampleCount - 1)
                latch.countDown()
            }
        }

        assertTrue("startDataNotification 失败", p.startDataNotification())
        assertTrue("未收到 EEG 数据", latch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS))
        Thread.sleep(TestConfig.PERF_MEASURE_SECONDS * 1000L)
        p.stopDataNotification()

        val span = endIdx.get() - startIdx.get() + 1
        val measured = span.toDouble() / TestConfig.PERF_MEASURE_SECONDS
        val nom = nominal.get().toDouble()
        assertTrue("标称采样率应 > 0", nom > 0)
        val ratio = measured / nom
        CerelaxTestSupport.log("实测采样率=$measured，标称=$nom，ratio=$ratio")
        assertTrue(
            "实测采样率偏差应 ≤ ${TestConfig.SAMPLE_RATE_TOLERANCE * 100}%（ratio=$ratio）",
            abs(ratio - 1.0) <= TestConfig.SAMPLE_RATE_TOLERANCE
        )
        p.disconnect()
    }

    /** DATA-PERF-002：连续起流无泄漏/掉速（时长可配置，正式长稳调 LONGEVITY_STREAM_SECONDS=1800）。 */
    @Test(timeout = 120000)
    fun dataPerf002_longStream_noLeakNoSlowdown() = runBlocking {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val firstLatch = CountDownLatch(1)
        val firstStart = AtomicLong(-1)
        val firstEnd = AtomicLong(-1)
        p.setOnDataListener { _, dataList ->
            val d = dataList.firstOrNull { it.getDataType() == DataType.NTF_EEG }
            if (d != null) {
                val s = d.startSampleIndex.toLong()
                if (firstStart.get() < 0) firstStart.set(s)
                firstEnd.set(s + d.sampleCount - 1)
                firstLatch.countDown()
            }
        }

        assertTrue("startDataNotification 失败", p.startDataNotification())
        assertTrue("未收到数据", firstLatch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS))

        // 分段观察：前段 vs 后段采样率，判断是否掉速
        val seg1Start = firstStart.get()
        Thread.sleep(TestConfig.LONGEVITY_STREAM_SECONDS / 2 * 1000L)
        val seg1End = firstEnd.get()
        Thread.sleep(TestConfig.LONGEVITY_STREAM_SECONDS / 2 * 1000L)
        val seg2End = firstEnd.get()

        assertTrue("长稳期间应持续有数据", seg2End > seg1End)
        val rate1 = (seg1End - seg1Start + 1).toDouble() / (TestConfig.LONGEVITY_STREAM_SECONDS / 2)
        val rate2 = (seg2End - seg1End).toDouble() / (TestConfig.LONGEVITY_STREAM_SECONDS / 2)
        CerelaxTestSupport.log("前段采样率=$rate1，后段采样率=$rate2")
        assertTrue(
            "长稳不应掉速（后段/前段应 >= ${1 - TestConfig.SAMPLE_RATE_TOLERANCE}）",
            rate2 >= rate1 * (1 - TestConfig.SAMPLE_RATE_TOLERANCE)
        )

        assertTrue("stopDataNotification 失败", p.stopDataNotification())
        p.disconnect()
    }

    /** DATA-PERF-003：SensorData.sampleRate 标称值与 DeviceInfo 采样率一致。 */
    @Test(timeout = 120000)
    fun dataPerf003_sampleRateNominal_matchesDeviceInfo() = runBlocking {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val info = p.getDeviceInfo()
        assertTrue("getDeviceInfo 不应为 null", info != null)

        val latch = CountDownLatch(1)
        val nominal = AtomicReference(0f)
        p.setOnDataListener { _, dataList ->
            val d = dataList.firstOrNull { it.getDataType() == DataType.NTF_EEG }
            if (d != null) {
                nominal.set(d.sampleRate)
                latch.countDown()
            }
        }
        assertTrue("startDataNotification 失败", p.startDataNotification())
        assertTrue("未收到 EEG 数据", latch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS))
        p.stopDataNotification()

        assertTrue("SensorData.sampleRate 应 > 0", nominal.get() > 0f)
        val infoRate = info?.eegSampleRate ?: 0f
        CerelaxTestSupport.log("SensorData.sampleRate=${nominal.get()}，DeviceInfo.eegSampleRate=$infoRate")
        if (infoRate > 0f) {
            assertTrue(
                "SensorData 标称采样率(${nominal.get()}) 应与 DeviceInfo($infoRate) 一致",
                nominal.get() == infoRate
            )
        }
        p.disconnect()
    }

    // =====================================================================================
    // DATA-ROB-*：健壮性 / 幂等
    // =====================================================================================

    /** DATA-ROB-001：init 重复调用幂等（不崩溃，不破坏已初始化状态）。 */
    @Test
    fun dataRob001_initRepeated_idempotent() = runBlocking {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("首次 init 应成功", init(p))

        var err = false
        var ret = false
        try { ret = init(p) } catch (e: Exception) {
            err = true
            CerelaxTestSupport.log("重复 init 抛异常 ${e.javaClass.simpleName}")
        }
        assertFalse("重复 init 不应抛异常", err)
        CerelaxTestSupport.log("重复 init 返回 $ret（幂等，不崩溃即通过）")
        p.disconnect()
    }

    /** DATA-ROB-002：startDataNotification/stopDataNotification 重复调用幂等。 */
    @Test
    fun dataRob002_startStopRepeated_idempotent() = runBlocking {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        assertTrue("首次 startDataNotification 应成功", p.startDataNotification())
        var err = false
        try { p.startDataNotification() } catch (e: Exception) {
            err = true
            CerelaxTestSupport.log("重复 startDataNotification 抛异常 ${e.javaClass.simpleName}")
        }
        assertFalse("重复 startDataNotification 不应抛异常", err)

        assertTrue("首次 stopDataNotification 应成功", p.stopDataNotification())
        try { p.stopDataNotification() } catch (e: Exception) {
            err = true
            CerelaxTestSupport.log("重复 stopDataNotification 抛异常 ${e.javaClass.simpleName}")
        }
        assertFalse("重复 stopDataNotification 不应抛异常", err)
        assertTrue("停流后 isDataTransfering 应为 false", !p.isDataTransfering())

        p.disconnect()
    }

    /** DATA-FUNC-014：IMU/PPG 采样率变更后 data rate 变化 —— SDK 当前版本未实现设置接口，规划中。 */
    @Ignore("Android SDK 未提供 IMU_SAMPLE_RATE/PPG_SAMPLE_RATE 设置接口（规划中），仅可读 DeviceInfo 只读字段")
    @Test
    fun dataFunc014_imuPpgSampleRateChange() = Unit

    /** DATA-FUNC-015：EEG 数据真实性（样本非全零、幅值合理）—— 需人工佩戴头环，semi-auto。 */
    @Ignore("需人工佩戴头环，semi-auto；非全零/幅值合理性依赖真实脑电信号，仪器环境无法可靠触发")
    @Test
    fun dataFunc015_eegDataAuthenticity() = Unit
}
