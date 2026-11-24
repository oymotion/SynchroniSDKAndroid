package com.oymotion.sensorcapiktdemo.cerelaxpro

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Cerelax 10 底层接口与边界补充（对应 Manual Cases 的 MISC-FUNC-001..008）。
 *
 * Android 与 Python 的 API 差异（关键）：
 *   - Python `hasDeviceFoundCallback` / `SensorData.to_flatbuffers` / `from_flatbuffers` /
 *     `from_flatbuffers_pooled` / `SensorData.clear` / `Sample.reset` 均为 Python 侧对象池/序列化接口；
 *     Android AAR 的 [SensorData] 与 [SensorData.Sample] 为不可变对象、无上述成员，
 *     bin 落盘/解析改由 [SensorController.replayBinFile] / [SensorController.parseBinToCsv] 承担。
 *     因此 MISC-FUNC-001..005 在 Android 无等价能力，标 @Ignore（不判失败）。
 *   - `startSampleIndex` 为 [SensorData] 的 public final int 字段；`DeviceState.INVALID` 为常量；
 *     [DeviceInfo] 的能力字段为 public 字段（Kotlin 直接属性访问）。
 */
@RunWith(AndroidJUnit4::class)
class CerelaxBoundaryTest {

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

    // =====================================================================================
    // 负向/边界（原有骨架）
    // =====================================================================================

    /** MISC-*：getSensor 对空/非法 MAC 返回 null。 */
    @Test
    fun getSensor_invalidMac_returnsNull() {
        assertNull("空 MAC 应返回 null", controller.getSensor(""))
        assertNull("非法 MAC 应返回 null", controller.getSensor("00:00:00:00:00:00"))
    }

    /** MISC-*：未连接时 getDeviceInfo 返回 null。 */
    @Test
    fun getDeviceInfo_beforeConnect_returnsNull() {
        runBlocking {
            val device = requireTarget()
            val profile = controller.requireSensor(device.mac)!!
            assertNull("未连接时 getDeviceInfo 应为 null", profile.getDeviceInfo())
        }
    }

    /** MISC-*：未连接时 init 返回 false。 */
    @Test
    fun init_beforeConnect_returnsFalse() {
        runBlocking {
            val device = requireTarget()
            val profile = controller.requireSensor(device.mac)!!
            assertTrue(
                "未连接时 init 应返回 false",
                !profile.init(TestConfig.PACKAGE_SAMPLE_COUNT, TestConfig.INIT_TIMEOUT_MS, TestConfig.POWER_REFRESH_INTERVAL_MS)
            )
        }
    }

    // =====================================================================================
    // MISC-FUNC-001..008
    // =====================================================================================

    /** MISC-FUNC-001：hasDeviceFoundCallback 查询 —— Android 无此接口，跳过。 */
    @Ignore("Android SensorController 无 hasDeviceFoundCallback 等价接口；仅提供 setOnScanResultListener")
    @Test
    fun miscFunc001_hasDeviceFoundCallback() = Unit

    /** MISC-FUNC-002：Sample.reset 清空字段 —— Android SensorData.Sample 不可变、无 reset()，跳过。 */
    @Ignore("Android SensorData.Sample 为不可变 final 类，无 reset()")
    @Test
    fun miscFunc002_sampleReset() = Unit

    /** MISC-FUNC-003：SensorData.clear/reset 复用 —— Android 无此接口，跳过。 */
    @Ignore("Android SensorData 无 clear()/reset() 复用接口")
    @Test
    fun miscFunc003_sensorDataClearReset() = Unit

    /** MISC-FUNC-004：SensorData.to_flatbuffers/from_flatbuffers 往返 —— Android 无此接口，跳过。 */
    @Ignore("Android SensorData 无 to_flatbuffers/from_flatbuffers；落盘/解析走 replayBinFile/parseBinToCsv")
    @Test
    fun miscFunc004_flatbuffersRoundTrip() = Unit

    /** MISC-FUNC-005：SensorData.from_flatbuffers_pooled 池化 —— Android 无此接口，跳过。 */
    @Ignore("Android 无池化反序列化接口 from_flatbuffers_pooled")
    @Test
    fun miscFunc005_flatbuffersPooled() = Unit

    /** MISC-FUNC-006：SensorData.startSampleIndex 属性存在且为非负整数。 */
    @Test(timeout = 300000)
    fun miscFunc006_startSampleIndex_nonNegative() = runBlocking<Unit> {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val latch = CountDownLatch(1)
        val indices = CopyOnWriteArrayList<Int>()
        p.setOnDataListener { _, dataList ->
            for (d in dataList) {
                if (d.sampleCount > 0) indices.add(d.startSampleIndex)
            }
            if (indices.size >= 3) latch.countDown()
        }

        assertTrue("startDataNotification 失败", p.startDataNotification())
        latch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS)
        p.stopDataNotification()

        assertTrue("应收到 >=3 批数据，实际=${indices.size}", indices.size >= 3)
        for ((i, v) in indices.withIndex()) {
            assertTrue("startSampleIndex 应 >= 0（批[$i]=$v）", v >= 0)
        }
        try { p.disconnect() } catch (_: Exception) {}
    }

    /** MISC-FUNC-007：DeviceState.INVALID 常量存在；异常 init 不崩溃，正常状态机可恢复。 */
    @Test(timeout = 300000)
    fun miscFunc007_deviceStateInvalid_existsAndNoCrash() = runBlocking<Unit> {
        // INVALID 为 public static final int，编译期即保证存在；此处仅做 nameOf 解析冒烟。
        assertNotNull("nameOf(INVALID) 不应为 null", DeviceState.nameOf(DeviceState.INVALID))

        val device = requireTarget()
        val p = controller.requireSensor(device.mac)!!

        // 未连接时 init：不应返回 true，且 SDK 不崩溃（返回 false / 抛异常均可接受）。
        val ret: Boolean? = try {
            p.init(TestConfig.PACKAGE_SAMPLE_COUNT, TestConfig.INIT_TIMEOUT_MS, TestConfig.POWER_REFRESH_INTERVAL_MS)
        } catch (e: Exception) {
            CerelaxTestSupport.log("未连接 init 抛异常 ${e.javaClass.simpleName}")
            null
        }
        assertTrue("未连接时 init 不应返回 true（实际=$ret）", ret != true)

        // 正常连接 → Ready → 断开 → Disconnected（验证状态机可恢复）。
        assertTrue("connect 失败", p.connect())
        assertTrue("未到达 Ready", CerelaxTestSupport.waitForReady(p))
        assertTrue("Ready 后 deviceState 应为 READY", p.getDeviceState() == DeviceState.READY)
        try { p.disconnect() } catch (_: Exception) {}
        assertTrue(
            "disconnect 后应回到 DISCONNECTED",
            CerelaxTestSupport.waitForState(p, DeviceState.DISCONNECTED)
        )
    }

    /** MISC-FUNC-008：DeviceInfo 能力字段完整性（Cerelax：EEG/PPG/SPO2/IMU/ACC/GYRO/IMPE > 0，EMG/ECG 等 == 0）。 */
    @Test(timeout = 300000)
    fun miscFunc008_deviceInfoCapabilityFields_complete() = runBlocking<Unit> {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val info = p.getDeviceInfo()
        assertNotNull("getDeviceInfo() 不应返回 null", info)

        // 检查1：modelName 非空
        assertTrue("modelName 应为非空字符串，实际='${info.modelName}'", info.modelName.isNotBlank())

        // 检查2：所有 ChannelCount >= 0（覆盖 AAR 暴露的全部能力字段）
        val channelCounts = listOf(
            "emg" to info.emgChannelCount, "eeg" to info.eegChannelCount, "ecg" to info.ecgChannelCount,
            "brth" to info.brthChannelCount, "acc" to info.accChannelCount, "gyro" to info.gyroChannelCount,
            "mag" to info.magChannelCount, "angle" to info.angleChannelCount, "imu" to info.imuChannelCount,
            "euler" to info.eulerChannelCount, "quat" to info.quatChannelCount, "gest" to info.gestChannelCount,
            "ppg" to info.ppgChannelCount, "spo2" to info.spo2ChannelCount, "impe" to info.impeChannelCount,
        )
        for ((name, v) in channelCounts) {
            assertTrue("$name ChannelCount 应 >= 0，实际=$v", v >= 0)
        }

        // 检查3：所有 SampleRate >= 0
        val sampleRates = listOf(
            "emg" to info.emgSampleRate, "eeg" to info.eegSampleRate, "ecg" to info.ecgSampleRate,
            "brth" to info.brthSampleRate, "acc" to info.accSampleRate, "gyro" to info.gyroSampleRate,
            "mag" to info.magSampleRate, "angle" to info.angleSampleRate, "imu" to info.imuSampleRate,
            "euler" to info.eulerSampleRate, "quat" to info.quatSampleRate, "gest" to info.gestSampleRate,
            "ppg" to info.ppgSampleRate, "spo2" to info.spo2SampleRate, "impe" to info.impeSampleRate,
        )
        for ((name, v) in sampleRates) {
            assertTrue("$name SampleRate 应 >= 0，实际=$v", v >= 0f)
        }

        // 检查4：按 spec（单一事实来源）断言各流通道数——支持流必须与 spec 一致，不支持流必须为 0。
        // 型号差异只来自 spec，不在用例里写死 EEG==2 之类的数值。
        val spec = TestConfig.specForIdentity(TestConfig.TARGET_IDENTITY)
        for ((key, expected) in spec.streams) {
            val actual = CerelaxSpec.channelCount(info, key)
            if (expected.supported && expected.channels > 0) {
                assertEquals("$key 通道数应与 spec 一致", expected.channels, actual)
            } else {
                assertEquals("$key 在 spec 中不支持，通道数应为 0", 0, actual)
            }
        }

        try { p.disconnect() } catch (_: Exception) {}
    }

    /** MISC-FUNC-009：onSuspend（App 退后台通知）重复调用不崩溃、不抛异常。auto（无需设备）。 */
    @Test
    fun miscFunc009_onSuspend_idempotentNoCrash() {
        try {
            controller.onSuspend()
            controller.onSuspend()
            CerelaxTestSupport.log("onSuspend 重复调用无异常")
        } catch (e: Exception) {
            throw AssertionError("onSuspend 不应抛异常: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** MISC-FUNC-010：fetchDeviceInfo（suspend）重新查询设备信息，返回非空且 modelName 非空。 */
    @Test(timeout = 300000)
    fun miscFunc010_fetchDeviceInfo_suspendReturnsDeviceInfo() = runBlocking<Unit> {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val info = p.fetchDeviceInfo(3000)
        assertTrue("fetchDeviceInfo 的 modelName 应为非空，实际='${info.modelName}'", info.modelName.isNotBlank())

        p.disconnect()
    }
}
