package com.oymotion.sensorcapiktdemo.cerelaxpro

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import com.oymotion.sensorcapiktdemo.devicespecs.DeviceSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Cerelax 09 异步接口（ASYNC-FUNC-001..012），由 Python gForceUltra/09_Async_Interface 1:1 迁移。
 *
 * Android 与 Python 的 async API 差异（已通过 javap 核对 AAR）：
 *   - Python `asyncConnect/asyncDisconnect` -> Android `connectAsync()/disconnectAsync()`（CompletableFuture）
 *   - Python `asyncScan/asyncInit/asyncSetParam/asyncGetParam/asyncGetBatteryLevel/asyncStartDataNotification/
 *            asyncStopDataNotification/asyncMultiStart/asyncMultiStop` -> Android 同名 suspend 扩展
 *            （`scan/init/setParam/getParam/getBatteryLevel/startDataNotification/stopDataNotification/
 *            multiStartDataNotification/multiStopDataNotification`），在 runBlocking 中直接调用。
 *
 * 说明：Android 无 Python 的 asyncio「事件循环内混用同步接口返回 None」限制，suspend 与
 * CompletableFuture 可自由混用，故 ASYNC-FUNC-010 改为验证「CompletableFuture 连接 + suspend
 * init/起流/停流/断开」的状态迁移正确性。
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

    private fun <T> CompletableFuture<T>.getOrNull(timeoutMs: Long): T? =
        runCatching { get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()

    /** 命中 TestConfig.DEVICES 清单（mac 精确 > 广播名后四位 identity），覆盖全部清单设备。 */
    private fun matchesAnyConfigured(device: BleDevice): Boolean {
        if (TestConfig.deviceByMac(device.mac) != null) return true
        val name = device.name.orEmpty()
        return TestConfig.DEVICES.any { d ->
            name.endsWith("(${d.identity})", ignoreCase = true) || name.endsWith(d.identity, ignoreCase = true)
        }
    }

    /** 多轮扫描收集命中清单的设备，按 mac 去重，返回前 [required] 台。 */
    private suspend fun scanAllTargets(required: Int): List<BleDevice> {
        val seen = LinkedHashMap<String, BleDevice>()
        for (attempt in 0..3) {
            controller.scan(TestConfig.SCAN_TIMEOUT_MS).forEach { d ->
                if (matchesAnyConfigured(d)) seen[d.mac.uppercase()] = d
            }
            if (seen.size >= required) break
            if (attempt < 3) {
                CerelaxTestSupport.log(
                    "已匹配 ${seen.size}/$required 台，${TestConfig.SCAN_RETRY_INTERVAL_MS}ms 后重扫（${attempt + 1}/3）..."
                )
                delay(TestConfig.SCAN_RETRY_INTERVAL_MS)
            }
        }
        return seen.values.toList()
    }

    private suspend fun connectInit(d: BleDevice): SensorProfile? {
        val p = controller.requireSensor(d.mac) ?: return null
        if (!p.connect()) return null
        if (!CerelaxTestSupport.waitForReady(p)) return null
        if (!init(p)) return null
        return p
    }

    private fun resultOf(results: Map<String, Boolean>, mac: String): Boolean? =
        results.entries.firstOrNull { it.key.equals(mac, ignoreCase = true) }?.value

    // =====================================================================================
    // ASYNC-* 用例
    // =====================================================================================

    /** ASYNC-FUNC-001：asyncScan 返回设备列表（Android：suspend scan）。 */
    @Test
    fun asyncFunc001_scan_returnsDeviceList() = runBlocking {
        assertTrue("蓝牙未开启（isEnable=false）", controller.isEnable)
        val devices = controller.scan(TestConfig.SCAN_TIMEOUT_MS)
        CerelaxTestSupport.log("scan 返回 ${devices.size} 台设备")
        assertTrue("scan 应返回非空设备列表", devices.isNotEmpty())
        assertTrue(
            "至少一台设备 Name/Address 非空",
            devices.any { it.name.orEmpty().isNotBlank() && it.mac.isNotBlank() }
        )
    }

    /** ASYNC-FUNC-002：asyncConnect 成功（True + Ready）/ 失败（无效 MAC）。 */
    @Test
    fun asyncFunc002_connectAsync_successAndFailure() = runBlocking {
        // 成功场景
        val device = requireTarget()
        val profile = controller.requireSensor(device.mac)!!
        val ok = profile.connectAsync().getOrNull(TestConfig.READY_TIMEOUT_MS)
        assertTrue("connectAsync 应返回 True，实际=$ok", ok == true)
        assertTrue("connectAsync 后应 Ready", CerelaxTestSupport.waitForReady(profile))
        profile.disconnectAsync().getOrNull(TestConfig.READY_TIMEOUT_MS)

        // 失败场景（无效 MAC）
        val invalid = controller.requireSensor("00:00:00:00:00:00")
        if (invalid == null) {
            CerelaxTestSupport.log("requireSensor(无效 MAC) 返回 null，无法构造无效设备，跳过失败场景断言")
        } else {
            val failOk = invalid.connectAsync().getOrNull(TestConfig.READY_TIMEOUT_MS)
            assertFalse("对无效设备 connectAsync 应返回 False，实际=$failOk", failOk == true)
        }
    }

    /** ASYNC-FUNC-003：asyncDisconnect 成功（True + Disconnected）。 */
    @Test
    fun asyncFunc003_disconnectAsync_success() = runBlocking {
        val device = requireTarget()
        val profile = controller.requireSensor(device.mac)!!
        val connOk = profile.connectAsync().getOrNull(TestConfig.READY_TIMEOUT_MS)
        assertTrue("connectAsync 失败，实际=$connOk", connOk == true)
        CerelaxTestSupport.waitForReady(profile)

        val ok = profile.disconnectAsync().getOrNull(TestConfig.READY_TIMEOUT_MS)
        assertTrue("disconnectAsync 应返回 True，实际=$ok", ok == true)
        CerelaxTestSupport.waitForState(profile, DeviceState.DISCONNECTED)
        assertTrue("disconnectAsync 后 deviceState 应为 DISCONNECTED", profile.getDeviceState() == DeviceState.DISCONNECTED)
    }

    /** ASYNC-FUNC-004：asyncInit 成功（True + hasInited）/ 失败（未 Ready）。 */
    @Test
    fun asyncFunc004_initAsync_successAndFailure() = runBlocking {
        val device = requireTarget()
        val p = connectReady(device)

        val ok = init(p)
        assertTrue("asyncInit 应返回 True，实际=$ok", ok)
        assertTrue("asyncInit 后 hasInited 应为 True", p.hasInited())

        p.disconnect()
        CerelaxTestSupport.waitForState(p, DeviceState.DISCONNECTED)
        val failOk = init(p)
        assertFalse("未 Ready 时 asyncInit 应返回 False，实际=$failOk", failOk)
    }

    /** ASYNC-FUNC-005：asyncSetParam 与同步一致（Android：suspend setParam）。 */
    @Test
    fun asyncFunc005_setParamAsync_consistent() = runBlocking {
        val sp = spec()
        val key = CerelaxSpec.supportedStreamKeys(sp).firstOrNull()
            ?: throw AssertionError("spec 未注册支持流")

        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val retOn = p.setParam(key, "ON")
        assertTrue("setParam($key, ON) 应成功，实际=$retOn", !isError(retOn))
        val vOn = p.getParam(key)
        assertTrue("getParam($key) 应含 ON，实际=$vOn", vOn.contains("ON"))

        p.setParam(key, "OFF")
        val vOff = p.getParam(key)
        assertTrue("getParam($key) 应含 OFF，实际=$vOff", vOff.contains("OFF"))

        p.disconnect()
    }

    /** ASYNC-FUNC-006：asyncGetParam 与同步一致（两次结果一致）。 */
    @Test
    fun asyncFunc006_getParamAsync_consistent() = runBlocking {
        val sp = spec()
        val key = CerelaxSpec.supportedStreamKeys(sp).firstOrNull()
            ?: throw AssertionError("spec 未注册支持流")

        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val v1 = p.getParam(key)
        val v2 = p.getParam(key)
        assertTrue("getParam($key) 不应 Error，实际=$v1", !isError(v1))
        assertEquals("两次 getParam($key) 应一致", v1, v2)

        p.disconnect()
    }

    /** ASYNC-FUNC-007：asyncGetBatteryLevel 返回 0~100/-1（Android：suspend getBatteryLevel）。 */
    @Test
    fun asyncFunc007_getBatteryLevelAsync() = runBlocking {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val level = p.getBatteryLevel()
        CerelaxTestSupport.log("getBatteryLevel() = $level")
        assertTrue("电量应在 0..100 或 -1，实际=$level", level == -1 || level in 0..100)

        p.disconnect()
    }

    /** ASYNC-FUNC-008：asyncStartDataNotification 起流（True + isDataTransfering）。 */
    @Test
    fun asyncFunc008_startDataNotificationAsync() = runBlocking {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val ok = p.startDataNotification()
        assertTrue("startDataNotification 应返回 True，实际=$ok", ok)
        assertTrue("起流后 isDataTransfering 应为 True", p.isDataTransfering())

        p.stopDataNotification()
        p.disconnect()
    }

    /** ASYNC-FUNC-009：asyncStopDataNotification 停流（True + isDataTransfering==False）。 */
    @Test
    fun asyncFunc009_stopDataNotificationAsync() = runBlocking {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        assertTrue("startDataNotification 失败", p.startDataNotification())
        val ok = p.stopDataNotification()
        assertTrue("stopDataNotification 应返回 True，实际=$ok", ok)
        assertFalse("停流后 isDataTransfering 应为 False", p.isDataTransfering())

        p.disconnect()
    }

    /** ASYNC-FUNC-010：async/sync 混用（CompletableFuture 连接 + suspend init/起流/停流/断开）状态迁移正确。 */
    @Test
    fun asyncFunc010_mixedAsyncSync_stateTransitions() = runBlocking {
        val device = requireTarget()
        val p = controller.requireSensor(device.mac)!!

        // async 连接
        val connOk = p.connectAsync().getOrNull(TestConfig.READY_TIMEOUT_MS)
        assertTrue("connectAsync 应返回 True，实际=$connOk", connOk == true)
        assertTrue("connectAsync 后应 Ready", CerelaxTestSupport.waitForReady(p))

        // suspend init
        assertTrue("init 失败", init(p))
        assertTrue("hasInited 应为 True", p.hasInited())

        // suspend 起流
        assertTrue("startDataNotification 失败", p.startDataNotification())
        Thread.sleep(2000)
        assertTrue("起流后 isDataTransfering 应为 True", p.isDataTransfering())

        // suspend 停流
        assertTrue("stopDataNotification 失败", p.stopDataNotification())
        assertFalse("停流后 isDataTransfering 应为 False", p.isDataTransfering())

        // suspend 断开
        assertTrue("disconnect 失败", p.disconnect())
        CerelaxTestSupport.waitForState(p, DeviceState.DISCONNECTED)
        assertTrue("disconnect 后 deviceState 应为 DISCONNECTED", p.getDeviceState() == DeviceState.DISCONNECTED)
    }

    /** ASYNC-FUNC-011：asyncMultiStartDataNotification 同步起流（需 ≥2 台，缺则跳过）。 */
    @Test
    fun asyncFunc011_multiStartDataNotification() = runBlocking {
        val devices = scanAllTargets(2)
        assumeTrue("需要 ≥2 台目标设备，实际 ${devices.size} 台", devices.size >= 2)

        val profiles = devices.take(2).mapNotNull { connectInit(it) }
        assumeTrue("成功初始化设备数不足 2 台（${profiles.size} 台）", profiles.size >= 2)

        val result = controller.multiStartDataNotification(profiles, TestConfig.INIT_TIMEOUT_MS, 5, 3)
        CerelaxTestSupport.log("multiStartDataNotification -> $result")
        profiles.forEach { p ->
            val mac = p.device.mac
            val v = resultOf(result, mac)
            assertTrue("[$mac] multiStart 应为 True，实际=$v", v == true)
        }
        assertTrue("起流后各台 isDataTransfering 应为 True", profiles.all { it.isDataTransfering() })

        controller.multiStopDataNotification(profiles, TestConfig.SCAN_TIMEOUT_MS)
        profiles.forEach { it.disconnect() }
    }

    /** ASYNC-FUNC-012：asyncMultiStopDataNotification 同步停流（需 ≥2 台，缺则跳过）。 */
    @Test
    fun asyncFunc012_multiStopDataNotification() = runBlocking {
        val devices = scanAllTargets(2)
        assumeTrue("需要 ≥2 台目标设备，实际 ${devices.size} 台", devices.size >= 2)

        val profiles = devices.take(2).mapNotNull { connectInit(it) }
        assumeTrue("成功初始化设备数不足 2 台（${profiles.size} 台）", profiles.size >= 2)

        controller.multiStartDataNotification(profiles, TestConfig.INIT_TIMEOUT_MS, 5, 3)

        val result = controller.multiStopDataNotification(profiles, TestConfig.SCAN_TIMEOUT_MS)
        CerelaxTestSupport.log("multiStopDataNotification -> $result")
        profiles.forEach { p ->
            val mac = p.device.mac
            val v = resultOf(result, mac)
            assertTrue("[$mac] multiStop 应为 True，实际=$v", v == true)
        }
        assertTrue("停流后各台 isDataTransfering 应为 False", profiles.all { !it.isDataTransfering() })

        profiles.forEach { it.disconnect() }
    }
}
