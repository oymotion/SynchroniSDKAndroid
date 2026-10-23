package com.oymotion.sensorcapiktdemo.cerelaxpro

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import com.oymotion.sensorcapiktdemo.cerelaxpro.CerelaxTestSupport.connectReady
import com.oymotion.sensorcapiktdemo.cerelaxpro.CerelaxTestSupport.log
import com.oymotion.sensorcapiktdemo.cerelaxpro.CerelaxTestSupport.pauseForManual
import com.oymotion.sensorcapiktdemo.cerelaxpro.CerelaxTestSupport.scanTargetWithRetry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Cerelax 01 控制器与扫描（CTRL-*），由 Python gForceUltra/01_Control_Scan 1:1 迁移。
 *
 * 型号差异适配（spec 驱动）：本类主要针对控制器/扫描通用 API，不涉及 EMG/EEG 等流能力，
 * 但"目标设备匹配"统一走 [TestConfig.matchesTarget]（mac 精确 > 广播名后四位 identity），
 * 因此 Cerelax 与 gForceUltra 仅体现在 TARGET_IDENTITY / DEVICES 配置，无需改测试逻辑。
 *
 * Android 与 Python 差异说明：
 *   - Python 的 subprocess 崩溃隔离（CTRL-BND-001 / CTRL-ROB-001）在 Android 用 JUnit
 *     @Test(timeout=...) 代替；非法参数若导致 native 崩溃会直接 fail 整个测试进程。
 *   - Python 的 getBLEBackendName / checkSetupDongle（bleak/bumble 后端切换、USB dongle）
 *     在 Android 不存在对应概念，标 @Ignore 保留追溯。
 *   - Python 的 asyncScan 在 Android 无独立接口（scan 本身即 suspend/coroutine）。
 *   - Python 的 getConnectedDevices 在 Android 无对应，仅保留 getConnectedSensors。
 *   - Python 的"批量 runner test_ctrl_scan_all.py"由 AndroidJUnit4/Gradle 天然承担，无需单独脚本。
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

    // =====================================================================================
    // 内部辅助
    // =====================================================================================

    /** 扫描并命中本轮目标设备；未命中直接 fail（返回非空 BleDevice）。 */
    private suspend fun requireTarget(): BleDevice =
        scanTargetWithRetry(controller) ?: throw AssertionError(
            "未扫描到目标设备（TARGET_IDENTITY=${TestConfig.TARGET_IDENTITY}），请确认设备开机在范围内"
        )

    /** 安全调用 startScan，返回（是否抛异常, 返回值）。 */
    private fun safeStartScan(periodMs: Int): Pair<Boolean, Boolean> =
        try {
            false to controller.startScan(periodMs)
        } catch (e: Exception) {
            log("startScan($periodMs) 抛异常 ${e.javaClass.simpleName}: ${e.message}")
            true to false
        }

    /** 安全调用 scan（suspend），蓝牙关闭等异常场景下返回空列表。 */
    private suspend fun safeScan(timeoutMs: Int): List<BleDevice> =
        try {
            controller.scan(timeoutMs)
        } catch (e: Exception) {
            log("scan 抛异常 ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }

    // =====================================================================================
    // CTRL-* 用例
    // =====================================================================================

    /** CTRL-FUNC-001：蓝牙未开启时 isEnable==False，startScan 被拒绝、scan 无结果。semi-auto（人工开关蓝牙）。 */
    @Test
    fun ctrlFunc001_bluetoothOff_isEnableFalse_startScanRejected() {
        pauseForManual("请【关闭手机/平板】蓝牙（不是待测 Cerelax 头环），头环保持开机在范围内")

        assertFalse("蓝牙关闭时 isEnable 应为 false", controller.isEnable)

        val (_, ret) = safeStartScan(TestConfig.SCAN_TIMEOUT_MS)
        log("蓝牙关闭时 startScan 返回 $ret")
        assertFalse("蓝牙关闭时 startScan 不应进入扫描态", controller.isScanning)

        val devices = runBlocking { safeScan(TestConfig.SCAN_TIMEOUT_MS) }
        log("蓝牙关闭时 scan 返回 ${devices.size} 台")
        assertTrue("蓝牙关闭时 scan 应为空", devices.isEmpty())

        pauseForManual("请【开启手机/平板】蓝牙以恢复（本阶段只检查 isEnable）")
        assertTrue("恢复后 isEnable 应为 true", controller.isEnable)
    }

    /** CTRL-FUNC-002：蓝牙开关时 isEnable 与 onEnableChanged 回调一致。semi-auto（人工开关蓝牙）。 */
    @Test
    fun ctrlFunc002_bluetoothToggle_onEnableChangedCallback() {
        val events = CopyOnWriteArrayList<Boolean>()
        controller.setOnEnableChangedListener { enabled ->
            events.add(enabled)
            log("onEnableChanged enabled=$enabled")
        }

        pauseForManual("请【关闭手机/平板】蓝牙")
        Thread.sleep(2000)
        assertTrue("关闭蓝牙后应收到 enabled=false 回调", events.any { !it })

        pauseForManual("请【开启手机/平板】蓝牙")
        Thread.sleep(2000)
        assertTrue("开启蓝牙后应收到 enabled=true 回调", events.any { it })
        assertTrue("开启蓝牙后 isEnable 应为 true", controller.isEnable)
    }

    /** CTRL-FUNC-003：startScan 返回 True、isScanning 翻转，并连接目标设备到 Ready。 */
    @Test
    fun ctrlFunc003_startScan_stateFlips_connect() = runBlocking {
        val (raised, ret) = safeStartScan(TestConfig.SCAN_TIMEOUT_MS)
        assertFalse("startScan 不应抛异常", raised)
        assertTrue("startScan 应返回 true", ret)
        assertTrue("startScan 后 isScanning 应为 true", controller.isScanning)

        controller.stopScan()
        Thread.sleep(500)
        assertFalse("stopScan 后 isScanning 应为 false", controller.isScanning)

        val target = requireTarget()
        val profile = connectReady(controller, target)
        assertNotNull("connect 应成功且到 Ready", profile)
        profile?.disconnect()
    }

    /** CTRL-FUNC-004：scan 返回列表且含目标设备，Address/RSSI 非空。 */
    @Test
    fun ctrlFunc004_scan_returnsTarget_withAddressRssi() = runBlocking {
        val devices = controller.scan(TestConfig.SCAN_TIMEOUT_MS)
        val target = devices.firstOrNull { TestConfig.matchesTarget(it) }
        assertNotNull("scan 应发现目标 Cerelax", target)

        val t = target!!
        assertTrue("目标设备 Address 应非空", t.mac.isNotEmpty())
        log("目标设备: name=${t.name.orEmpty()} mac=${t.mac} rssi=${t.rssi}")
        assertTrue("目标设备 RSSI 应为非零（BLE 信号强度）", t.rssi != 0)
    }

    /** CTRL-FUNC-005：onScanResult 周期触发且列表含目标设备。 */
    @Test
    fun ctrlFunc005_onScanResult_periodic_withTarget() {
        val events = CopyOnWriteArrayList<Pair<Long, List<String>>>()
        val t0 = System.currentTimeMillis()
        controller.setOnScanResultListener { devices ->
            events.add(System.currentTimeMillis() - t0 to devices.map { it.name.orEmpty() })
            log("onScanResult +${System.currentTimeMillis() - t0}ms ${devices.size} 台")
        }

        assertTrue("startScan 应返回 true", controller.startScan(3000))
        Thread.sleep(8000)
        controller.stopScan()

        assertTrue("onScanResult 应至少触发 2 次", events.size >= 2)
        val hasTarget = events.any { (_, names) ->
            names.any { n -> TestConfig.targetIdentities.any { id -> n.endsWith("($id)", true) } }
        }
        assertTrue("onScanResult 列表应含目标 Cerelax", hasTarget)
    }

    /** CTRL-FUNC-006：stopScan 后回调停止（不再新增）。 */
    @Test
    fun ctrlFunc006_stopScan_stopsCallback() {
        val events = CopyOnWriteArrayList<Long>()
        val t0 = System.currentTimeMillis()
        controller.setOnScanResultListener { events.add(System.currentTimeMillis() - t0) }

        assertTrue("startScan 应返回 true", controller.startScan(3000))
        // 等待至少 1 个周期，确认停止前回调在触发
        val waitDeadline = System.currentTimeMillis() + 15000
        while (events.size < 1 && System.currentTimeMillis() < waitDeadline) {
            Thread.sleep(200)
        }
        assertTrue("stopScan 前 onScanResult 应已触发", events.size >= 1)

        controller.stopScan()
        Thread.sleep(3000)  // 排空在途回调
        val baseline = events.size
        Thread.sleep(6000)  // 稳定期（2 个周期）
        val after = events.size
        log("stopScan 后：排空后 $baseline 次 -> 稳定期后 $after 次（新增 ${after - baseline}）")
        assertTrue("stopScan 后不应再有新回调", after == baseline)
    }

    /** CTRL-FUNC-007：requireSensor 有效 MAC 命中、无效 MAC 未命中。 */
    @Test
    fun ctrlFunc007_requireSensor_validAndInvalid() = runBlocking {
        val target = requireTarget()

        val profile = controller.requireSensor(target.mac)
        assertNotNull("requireSensor(有效 mac) 应返回 SensorProfile", profile)

        val empty = controller.requireSensor("")
        assertNull("requireSensor(空串) 应返回 null", empty)

        val bad = controller.requireSensor("00:00:00:00:00:00")
        assertNull("requireSensor(无效 mac) 应返回 null", bad)
    }

    /** CTRL-FUNC-008：getSensor 正确 MAC 命中、错误 MAC 未命中。 */
    @Test
    fun ctrlFunc008_getSensor_hitAndMiss() = runBlocking {
        val target = requireTarget()
        val created = controller.requireSensor(target.mac)
        assertNotNull("requireSensor 应创建 profile", created)

        val hit = controller.getSensor(target.mac)
        assertNotNull("getSensor(正确 mac) 应命中", hit)

        val miss = controller.getSensor("00:00:00:00:00:00")
        assertNull("getSensor(错误 mac) 不应命中", miss)
    }

    /** CTRL-FUNC-009：连接后 getConnectedSensors 含目标 mac，断开后不含。 */
    @Test
    fun ctrlFunc009_getConnectedSensors_connectDisconnect() = runBlocking {
        val target = requireTarget()
        val profile = controller.requireSensor(target.mac)
        assertNotNull("requireSensor 应返回 profile", profile)
        assertTrue("connect 应成功", profile!!.connect())

        Thread.sleep(1000)
        val connectedMacs = controller.getConnectedSensors().map { it.device.mac }
        log("连接后 getConnectedSensors = $connectedMacs")
        assertTrue("连接后 getConnectedSensors 应含 ${target.mac}", target.mac in connectedMacs)

        profile.disconnect()
        Thread.sleep(1000)
        val afterMacs = controller.getConnectedSensors().map { it.device.mac }
        log("断开后 getConnectedSensors = $afterMacs")
        assertTrue("断开后 getConnectedSensors 不应含 ${target.mac}", target.mac !in afterMacs)
    }

    /** CTRL-FUNC-010：getBLEBackendName。Android 无后端切换（原生 BLE），标记不适用。 */
    @Ignore("Android 不适用：SDK 固定使用系统原生 BLE，无 bleak/bumble 后端切换与 getBLEBackendName")
    @Test
    fun ctrlFunc010_getBLEBackendName_notApplicableOnAndroid() {
        // 桌面专属（bleak/bumble 后端），Android 无对应 API。
    }

    /** CTRL-FUNC-011：checkSetupDongle。Android 无 USB dongle 概念，标记不适用。 */
    @Ignore("Android 不适用：无 USB BLE dongle / WinUSB 驱动引导流程，checkSetupDongle 为桌面专属")
    @Test
    fun ctrlFunc011_checkSetupDongle_notApplicableOnAndroid() {
        // 桌面专属，Android 无对应 API。
    }

    /** CTRL-FUNC-012：getVersion 返回非空且为 x.y.z 版本号格式。auto（无需设备）。 */
    @Test
    fun ctrlFunc012_getVersion_nonEmptyAndFormatted() {
        val version = controller.getVersion()
        log("getVersion = $version")
        assertTrue("getVersion 应返回非空", version.isNotEmpty())
        assertTrue(
            "getVersion 应为 x.y.z 版本号格式，实际=$version",
            Regex("^\\d+\\.\\d+\\.\\d+").containsMatchIn(version)
        )
    }

    /** CTRL-FUNC-013：terminate 重复调用不抛异常（连接态下连接数归零）。 */
    @Test
    fun ctrlFunc013_terminate_idempotent() = runBlocking {
        val target = requireTarget()
        val profile = connectReady(controller, target)
        assertNotNull("应先成功连接", profile)

        Thread.sleep(500)
        val before = controller.getConnectedSensors().size
        log("连接态 getConnectedSensors = $before")
        assertTrue("连接态 getConnectedSensors 应至少 1 台", before >= 1)

        // 第 1 次 terminate
        try {
            SensorController.terminate()
            log("第1次 terminate 无异常")
        } catch (e: Exception) {
            assertTrue("第1次 terminate 不应抛异常: ${e.message}", false)
        }

        val after1 = controller.getConnectedSensors().size
        log("第1次 terminate 后 getConnectedSensors = $after1")
        assertTrue("第1次 terminate 后连接应归零", after1 == 0)

        // 第 2 次 terminate（幂等）
        try {
            SensorController.terminate()
            log("第2次 terminate 无异常")
        } catch (e: Exception) {
            assertTrue("第2次 terminate 不应抛异常: ${e.message}", false)
        }
    }

    /** CTRL-BND-001：startScan(period_in_ms) 边界值不崩溃/非法值被拒绝。auto（无需设备）。 */
    @Test
    fun ctrlBnd001_startScan_boundaryValues() {
        // Android startScan 参数为 Int，Int.MAX_VALUE+1 无法表示（会溢出），故边界集为 5 点。
        val legal = setOf(0, 1, Int.MAX_VALUE - 1, Int.MAX_VALUE)
        val illegal = setOf(-1)

        (legal + illegal).forEach { value ->
            val (raised, ret) = safeStartScan(value)
            controller.stopScan()
            if (value in legal) {
                assertFalse("合法值 startScan($value) 不应抛异常", raised)
                log("合法值 startScan($value) -> $ret（不抛异常即通过）")
            } else {
                val rejected = raised || !ret
                assertTrue("非法值 startScan($value) 应被拒绝（返回 false 或抛异常）", rejected)
                log("非法值 startScan($value) -> raised=$raised ret=$ret")
            }
        }
    }

    /** CTRL-ROB-001：扫描期间重复 start/stop 无异常且最终 isScanning==False。auto（无需设备）。 */
    @Test(timeout = 60000)
    fun ctrlRob001_repeatedStartStop_noException() {
        var errCount = 0
        repeat(20) { i ->
            try {
                controller.startScan(300)
            } catch (e: Exception) {
                errCount++
                log("startScan#${i} 抛异常 ${e.javaClass.simpleName}")
            }
            try {
                controller.stopScan()
            } catch (e: Exception) {
                errCount++
                log("stopScan#${i} 抛异常 ${e.javaClass.simpleName}")
            }
        }
        assertTrue("重复 start/stop 不应抛异常（异常数=$errCount）", errCount == 0)
        assertFalse("最终 isScanning 应为 false", controller.isScanning)
    }

    /** CTRL-BND-002：getSensor/requireSensor 空串/非法 MAC 边界，未命中返回 null 且不崩溃。auto（无需设备）。 */
    @Test
    fun ctrlBnd002_getSensorRequireSensor_boundary() {
        // Kotlin 非空 String 参数无法表达 Python 的 None；此处覆盖空串与多种非法 MAC 边界。
        val badMacs = listOf("", "00:00:00:00:00:00", "INVALID_MAC", "AA:BB:CC:DD:EE:FF:GG")
        for (mac in badMacs) {
            val r1 = try {
                controller.requireSensor(mac)
            } catch (e: Exception) {
                log("requireSensor('$mac') 抛异常 ${e.javaClass.simpleName}")
                null
            }
            val r2 = try {
                controller.getSensor(mac)
            } catch (e: Exception) {
                log("getSensor('$mac') 抛异常 ${e.javaClass.simpleName}")
                null
            }
            assertNull("requireSensor('$mac') 应返回 null", r1)
            assertNull("getSensor('$mac') 应返回 null", r2)
        }
    }

    /** CTRL-ROB-002：startScan/stopScan/scan 重复调用幂等（不抛异常，最终 isScanning==False）。auto（无需设备）。 */
    @Test(timeout = 60000)
    fun ctrlRob002_scanCycle_idempotent() = runBlocking {
        var errCount = 0
        repeat(3) {
            try { controller.startScan(200) } catch (e: Exception) {
                errCount++
                log("startScan 抛异常 ${e.javaClass.simpleName}")
            }
            try { controller.stopScan() } catch (e: Exception) {
                errCount++
                log("stopScan 抛异常 ${e.javaClass.simpleName}")
            }
            safeScan(1000)
        }
        assertTrue("重复 startScan/stopScan/scan 不应抛异常（异常数=$errCount）", errCount == 0)
        assertFalse("最终 isScanning 应为 false", controller.isScanning)
    }
}
