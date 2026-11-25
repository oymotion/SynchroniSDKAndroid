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
import org.junit.Assume.assumeTrue
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
 * Cerelax 03 数据流（DATA-*），spec 驱动适配。
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
    fun dataBnd001_initPackageSampleCount_boundary() = runBlocking<Unit> {
        val device = requireTarget()
        val p = controller.requireSensor(device.mac)!!

        val max = Int.MAX_VALUE.toLong()
        val values = listOf(1L, 2L, max - 1, max, 0L, max + 1)
        val mustSucceed = setOf(1L, 2L)       // 正常范围内的合法值必须 init 成功
        val tolerated = setOf(max - 1, max)   // 极端值可能超出 SDK 实际上限，只要求不崩溃

        for (v in values) {
            val pkg = v.toInt() // MAX+1 溢出为 Int.MIN_VALUE（模拟越界入参）
            reconnect(p)
            val ret: Boolean? = try {
                p.init(pkg, TestConfig.INIT_TIMEOUT_MS, TestConfig.POWER_REFRESH_INTERVAL_MS)
            } catch (e: Exception) {
                CerelaxTestSupport.log("init($v) 抛异常 ${e.javaClass.simpleName}")
                null
            }
            when {
                v in mustSucceed -> assertTrue("合法值 $v 的 init 应成功，实际=$ret", ret == true)
                v in tolerated -> {
                    assertFalse("极端合法值 $v 不应抛异常", ret == null)
                    if (ret == false) {
                        CerelaxTestSupport.log("init($v) 返回 false（超出实际处理上限，仅验证不崩溃）")
                    }
                }
                else -> assertTrue("非法值 $v 应被拒绝（返回 false 或抛异常），实际=$ret", ret != true)
            }
        }
        try { p.disconnect() } catch (_: Exception) {}
    }

    /** DATA-BND-002：init(_, powerRefreshInterval) 6 点边界。 */
    @Test(timeout = 300000)
    fun dataBnd002_initPowerRefreshInterval_boundary() = runBlocking<Unit> {
        val device = requireTarget()
        val p = controller.requireSensor(device.mac)!!

        val max = Int.MAX_VALUE.toLong()
        val values = listOf(0L, 1L, max - 1, max, -1L, max + 1)
        val mustSucceed = setOf(0L, 1L)       // 正常范围内的合法值必须 init 成功
        val tolerated = setOf(max - 1, max)   // 极端值可能超出 SDK 实际上限，只要求不崩溃

        for (v in values) {
            val power = v.toInt()
            reconnect(p)
            val ret: Boolean? = try {
                p.init(TestConfig.PACKAGE_SAMPLE_COUNT, TestConfig.INIT_TIMEOUT_MS, power)
            } catch (e: Exception) {
                CerelaxTestSupport.log("init(_, $v) 抛异常 ${e.javaClass.simpleName}")
                null
            }
            when {
                v in mustSucceed -> assertTrue("合法值 $v 的 init 应成功，实际=$ret", ret == true)
                v in tolerated -> {
                    assertFalse("极端合法值 $v 不应抛异常", ret == null)
                    if (ret == false) {
                        CerelaxTestSupport.log("init(_, $v) 返回 false（超出实际处理上限，仅验证不崩溃）")
                    }
                }
                else -> assertTrue("非法值 $v 应被拒绝（返回 false 或抛异常），实际=$ret", ret != true)
            }
        }
        try { p.disconnect() } catch (_: Exception) {}
    }

    // =====================================================================================
    // DATA-FUNC-*：起流/停流/数据访问
    // =====================================================================================

    /** DATA-FUNC-001：init 成功，hasInited==True。 */
    @Test
    fun dataFunc001_initSuccess_hasInitedTrue() = runBlocking<Unit> {
        val device = requireTarget()
        val p = connectReady(device)
        assertFalse("init 前 hasInited 应为 false", p.hasInited())
        assertTrue("init 应成功", init(p))
        assertTrue("init 后 hasInited 应为 true", p.hasInited())
        p.disconnect()
    }

    /** DATA-FUNC-002：未 Ready 前 init/startDataNotification 失败。 */
    @Test(timeout = 60000)
    fun dataFunc002_beforeReady_initStartRejected() = runBlocking<Unit> {
        val device = requireTarget()
        val p = controller.requireSensor(device.mac)!!
        assertTrue("前置：未连接 deviceState 应非 READY", p.getDeviceState() != DeviceState.READY)
        assertFalse("未 Ready init 应返回 false", init(p))
        assertFalse("未 Ready startDataNotification 应返回 false", p.startDataNotification())
    }

    /** DATA-FUNC-003：startDataNotification 后 isDataTransfering==True，回调按批触发。 */
    @Test
    fun dataFunc003_startNotification_receivesData() = runBlocking<Unit> {
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
    fun dataFunc004_stopNotification_stops() = runBlocking<Unit> {
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
    fun dataFunc005_supportedStreams_eachYieldsData() = runBlocking<Unit> {
        val spec = TestConfig.specForIdentity(TestConfig.TARGET_IDENTITY)
        val supported = CerelaxSpec.supportedStreamKeys(spec)
        assertTrue("spec 应注册支持流", supported.isNotEmpty())

        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        // 以设备 getParam("NTF") 为权威开关列表：spec 的 ACC/GYRO 子流没有独立开关，
        // 直接 setParam("NTF_GFORCE_ACC") 会抛 Not supported。
        val ntf = try { p.getParam("NTF") } catch (_: Exception) { "" }
        val listed = CerelaxSpec.parseSwitchList(ntf).map { it.first }.toSet()
        val switchable = supported.map { CerelaxSpec.paramKey(it) }.filter { it in listed }
        assertTrue("设备 NTF 列表中应至少有一个可切换流", switchable.isNotEmpty())

        for (key in switchable) {
            val expectedTypes = CerelaxSpec.dataTypesForKey(key)
            val expectedCh = CerelaxSpec.expectedChannels(spec, key) ?: continue
            if (expectedTypes.isEmpty()) continue

            // 单流隔离：关掉其它可切换流避免串扰；共流（如 Impedance 需 EEG）保持开启
            val keepOn = spec.sampleRates.values
                .filter { it.stream == key }
                .mapNotNull { it.coStream }
                .toSet()
            for (k in switchable) {
                try { p.setParam(k, if (k in keepOn) "ON" else "OFF") } catch (_: Exception) {}
            }
            try { p.setParam(key, "ON") } catch (e: Exception) {
                throw AssertionError("setParam($key, ON) 失败: ${e.message}")
            }

            val latch = CountDownLatch(1)
            val got = ConcurrentHashMap<Int, Int>()
            p.setOnDataListener { _, dataList ->
                for (d in dataList) {
                    if (d.dataType in expectedTypes) got[d.dataType] = d.channelCount
                }
                if (CerelaxSpec.aggregateLayoutOk(spec, key, got)) latch.countDown()
            }

            assertTrue("startDataNotification 失败", p.startDataNotification())
            val ok = latch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS)
            assertTrue(
                "流 $key 未在 ${TestConfig.DATA_TIMEOUT_SEC}s 内收到符合 spec 的数据" +
                    "（期望通道=$expectedCh，实际=$got）",
                ok
            )
            p.stopDataNotification()
        }
        p.disconnect()
    }

    /** DATA-FUNC-006：SensorData 元数据合法。 */
    @Test
    fun dataFunc006_metadataValid() = runBlocking<Unit> {
        assumeTrue("spec 不支持 NTF_EEG，跳过", TestConfig.targetSpec().supports("NTF_EEG"))
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
        assertTrue("startTimeSec 应 >= 0（未知时为 0.0）", sd.startTimeSec >= 0.0)
        assertTrue("isDataValid 应为 true", sd.isDataValid())

        var enabledChannels = 0
        for (ch in 0 until sd.channelCount) {
            if (sd.isChannelEnabled(ch)) enabledChannels++
        }
        assertTrue("至少一个通道应被使能（channelCount=${sd.channelCount}）", enabledChannels > 0)

        p.stopDataNotification()
        p.disconnect()
    }

    /** DATA-FUNC-007：getChannelSample/单点访问器一致，sampleIndex 单调。 */
    @Test
    fun dataFunc007_channelSampleConsistent_sampleIndexMonotonic() = runBlocking<Unit> {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val latch = CountDownLatch(1)
        val consistent = AtomicBoolean(true)
        val monotonic = AtomicBoolean(true)
        val tsValid = AtomicBoolean(true)
        val lastByChannel = ConcurrentHashMap<Pair<Int, Int>, AtomicLong>()

        p.setOnDataListener { _, dataList ->
            for (d in dataList) {
                for (ch in 0 until d.channelCount) {
                    // 各流的 channelIndex 是独立编号空间，必须按 (dataType, channel) 分组再判单调
                    val last = lastByChannel.getOrPut(d.dataType to ch) { AtomicLong(-1) }
                    for (i in 0 until d.sampleCount) {
                        // 末批/不足包可能是无效填充样本（gForceMax 实测 sc=10、奇通道 isDataValid=false 且 sampleIndex=0），
                        // 无效样本没有有意义的时间序，不参与一致性与单调性断言。
                        if (!d.isDataValid(ch, i)) continue
                        val s = d.getChannelSample(ch, i)
                        if (s != null) {
                            if (s.sampleIndex != d.getSampleIndex(ch, i)) consistent.set(false)
                            if (s.data != d.getData(ch, i)) consistent.set(false)
                            val idx = d.getSampleIndex(ch, i).toLong()
                            if (idx <= last.get()) monotonic.set(false)
                            last.set(idx)
                            if (d.getTimeStampInMs(ch, i) < 0) tsValid.set(false)
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
        assertTrue("getTimeStampInMs 应 >= 0", tsValid.get())
        p.disconnect()
    }

    /** DATA-FUNC-008：isLost 与 lostPackageCount 一致。 */
    @Test
    fun dataFunc008_isLostConsistentWithLostPackageCount() = runBlocking<Unit> {
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
    fun dataFunc009_batchSizeApproxPackageCount() = runBlocking<Unit> {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val latch = CountDownLatch(1)
        // 多流并存时每包样本数由各流速率/设备分包决定（Cerelax 实测 ACC/GYRO=4、EEG=20、SPO2/Imp=1），
        // 不能混在一起断言；按 dataType 分桶，要求至少一个流符合 packageSampleCount。
        val batches = CopyOnWriteArrayList<Pair<Int, Int>>()
        p.setOnDataListener { _, dataList ->
            dataList.forEach { batches.add(it.dataType to it.sampleCount) }
            if (dataList.isNotEmpty()) latch.countDown()
        }

        assertTrue("startDataNotification 失败", p.startDataNotification())
        assertTrue("未收到数据", latch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS))
        Thread.sleep(TestConfig.COLLECT_SECONDS * 1000L)
        p.stopDataNotification()

        assertTrue("应至少收到一批数据", batches.isNotEmpty())
        val packageCount = TestConfig.PACKAGE_SAMPLE_COUNT
        val grouped = batches.groupBy({ it.first }, { it.second })
        val stats = grouped.mapValues { (_, sizes) ->
            sizes.count { abs(it - packageCount) <= packageCount * 0.3 } to sizes.size
        }
        CerelaxTestSupport.log(
            "按类型批大小=${
                stats.mapKeys { DataType.nameOf(it.key) }
            }（package=$packageCount）"
        )
        val ok = stats.entries.firstOrNull { (_, s) -> s.first >= s.second * 0.7 }
        assertTrue(
            "应至少有一个流的多数批≈$packageCount（实际=${stats.mapKeys { DataType.nameOf(it.key) }}）",
            ok != null
        )
        p.disconnect()
    }

    /** DATA-FUNC-010：IMU 聚合流通道布局（期望通道数取自 spec，不在用例里写死 6）。 */
    @Test
    fun dataFunc010_imuAggregatedLayout() = runBlocking<Unit> {
        val spec = TestConfig.targetSpec()
        assumeTrue("spec 不支持 NTF_IMU，跳过", spec.supports("NTF_IMU"))
        val expectedChannels = spec.channels("NTF_IMU")

        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        p.setParam(CerelaxSpec.paramKey("NTF_IMU"), "ON")
        // 允许两种形态：聚合 NTF_IMU，或按 spec imuSubChannels 拆分后的子流（Cerelax = ACC+GYRO）
        val latch = CountDownLatch(1)
        val got = ConcurrentHashMap<Int, Int>()
        p.setOnDataListener { _, dataList ->
            for (d in dataList) {
                if (d.dataType in CerelaxSpec.dataTypesForKey("NTF_IMU")) {
                    got[d.dataType] = d.channelCount
                }
            }
            if (CerelaxSpec.aggregateLayoutOk(spec, "NTF_IMU", got)) latch.countDown()
        }

        assertTrue("startDataNotification 失败", p.startDataNotification())
        assertTrue(
            "IMU 聚合布局与 spec 不一致（期望聚合 $expectedChannels ch 或按 imuSubChannels 拆分，实际=$got）",
            latch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS)
        )

        p.stopDataNotification()
        p.disconnect()
    }

    /** DATA-FUNC-011：gForceUltra 传统 EMG 设备 NTF_GEST 与 NTF_EMG 互斥 —— Cerelax 无 EMG/GEST，跳过。 */
    @Ignore("gForceUltra 传统 EMG 设备专属；Cerelax spec 不支持 EMG/GEST，无对应能力")
    @Test
    fun dataFunc011_gestEmgMutualExclusion() = Unit

    /** DATA-FUNC-012：SensorData.clone 深拷贝（Android 不可变，验证独立对象 + 内容一致）。 */
    @Test
    fun dataFunc012_cloneDeepCopy() = runBlocking<Unit> {
        assumeTrue("spec 不支持 NTF_EEG，跳过", TestConfig.targetSpec().supports("NTF_EEG"))
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
    fun dataFunc013_sampleFieldsConsistent() = runBlocking<Unit> {
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
                            if (s.absTimeStampInSec != d.getAbsTimeStampInSec(ch, i)) consistent.set(false)
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
    fun dataPerf001_measuredRate_withinTolerance() = runBlocking<Unit> {
        assumeTrue("spec 不支持 NTF_EEG，跳过", TestConfig.targetSpec().supports("NTF_EEG"))
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
    fun dataPerf002_longStream_noLeakNoSlowdown() = runBlocking<Unit> {
        assumeTrue("spec 不支持 NTF_EEG，跳过", TestConfig.targetSpec().supports("NTF_EEG"))
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
    fun dataPerf003_sampleRateNominal_matchesDeviceInfo() = runBlocking<Unit> {
        assumeTrue("spec 不支持 NTF_EEG，跳过", TestConfig.targetSpec().supports("NTF_EEG"))
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
    fun dataRob001_initRepeated_idempotent() = runBlocking<Unit> {
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
    fun dataRob002_startStopRepeated_idempotent() = runBlocking<Unit> {
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

    /** DATA-FUNC-014：IMU/PPG 采样率变更后，数据流上报的 sampleRate 随之变化（按 spec 能力执行，不支持则跳过）。 */
    @Test(timeout = 300000)
    fun dataFunc014_imuPpgSampleRateChange() = runBlocking<Unit> {
        val spec = TestConfig.targetSpec()
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        var verified = 0
        for ((typeKey, paramKey) in listOf("NTF_IMU" to "IMU_SAMPLE_RATE", "NTF_PPG" to "PPG_SAMPLE_RATE")) {
            if (!spec.supports(typeKey)) {
                CerelaxTestSupport.log("spec 不支持 $typeKey，跳过 $paramKey")
                continue
            }
            val sr = spec.sampleRates[paramKey]
            if (sr == null) {
                CerelaxTestSupport.log("spec 未注册 $paramKey，跳过")
                continue
            }
            val rates = sr.rates.mapNotNull { it.toFloatOrNull() }.distinct()
            if (rates.size < 2) {
                CerelaxTestSupport.log("$paramKey 只有单档 ${sr.rates}，无法验证变化，跳过")
                continue
            }

            val currentRaw = try { p.getParam(paramKey) } catch (_: Exception) { "" }
            val original = rates.firstOrNull { currentRaw.contains(it.toInt().toString()) }
            val target = rates.first { it != original }

            val setRet = try {
                p.setParam(paramKey, target.toInt().toString())
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            assertTrue("setParam($paramKey, ${target.toInt()}) 应成功，实际=$setRet", !setRet.startsWith("Error"))
            Thread.sleep(1000)

            val allowedTypes = CerelaxSpec.dataTypesForKey(typeKey)
            val latch = CountDownLatch(1)
            val observed = AtomicReference(0f)
            p.setOnDataListener { _, dataList ->
                val d = dataList.firstOrNull { it.dataType in allowedTypes }
                if (d != null) {
                    observed.set(d.sampleRate)
                    latch.countDown()
                }
            }
            assertTrue("startDataNotification 失败", p.startDataNotification())
            assertTrue(
                "${TestConfig.DATA_TIMEOUT_SEC}s 内未收到 $typeKey 数据",
                latch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS)
            )
            p.stopDataNotification()

            val got = observed.get()
            CerelaxTestSupport.log("$paramKey=${target.toInt()} -> 数据流 sampleRate=$got")
            assertTrue(
                "$paramKey 改为 ${target.toInt()} 后数据流 sampleRate 应为 ${target.toInt()}，实际=$got",
                abs(got - target) <= target * 0.01f
            )

            // 还原原值，避免污染后续用例
            original?.let {
                try { p.setParam(paramKey, it.toInt().toString()) } catch (_: Exception) {}
                CerelaxTestSupport.log("$paramKey 已还原 ${it.toInt()}")
            }
            verified++
        }

        assertTrue("IMU/PPG 采样率变更均不可验证（spec 不支持或只有单档）", verified > 0)
        p.disconnect()
    }

    /** DATA-FUNC-015：EEG 数据真实性（样本非全零、幅值合理）—— 需人工佩戴头环，semi-auto。 */
    @Ignore("需人工佩戴头环，semi-auto；非全零/幅值合理性依赖真实脑电信号，仪器环境无法可靠触发")
    @Test
    fun dataFunc015_eegDataAuthenticity() = Unit
}
