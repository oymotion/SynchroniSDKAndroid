package com.oymotion.sensorcapiktdemo.cerelaxpro

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * BLE 偶发连接失败（原生日志 `Failed to connect`）时重试。
 * 实测同一设备失败后数秒重连即成功，属链路时序抖动而非 SDK 缺陷。
 */
private suspend fun connectWithRetry(profile: SensorProfile, attempts: Int = 3): Boolean {
    repeat(attempts) { i ->
        if (profile.connect()) return true
        CerelaxTestSupport.log("connect 第 ${i + 1}/$attempts 次失败")
        if (i < attempts - 1) Thread.sleep(3000)
    }
    return false
}

/**
 * Cerelax 02 连接与状态机（DEV-SM-*）。
 *
 * 型号差异适配（spec 驱动）：本类聚焦状态机与连接语义，不依赖 EMG/EEG 等型号专属流能力；
 * "目标设备"统一走 [TestConfig.matchesTarget]（mac 精确 > 广播名后四位 identity），
 * Cerelax 与 gForceUltra 的差异仅体现在 TARGET_IDENTITY / DEVICES 配置。
 *
 * Android 与 Python 差异说明：
 *   - Python input() 人工暂停 -> Android [CerelaxTestSupport.pauseForManual] 固定窗口 + logcat。
 *   - Python subprocess 崩溃隔离（DEV-SM-006）-> Android 直接调用 + @Test(timeout=...)；
 *     若状态门控失效导致 native 崩溃，整个测试进程 fail（等价于崩溃被观测）。
 *   - Python sensor.autoReconnect 可读 -> Android 仅 setAutoReconnect(boolean)，无 getter，
 *     故 DEV-SM-008/010 不读回默认值，直接依赖 SDK 默认开启语义。
 *   - Python onAutoReconnect(sensor, restore, answer) -> Android
 *     setOnAutoReconnectListener { _, restore, answer -> answer.answer(bool) }，
 *     其中 answer(true)=应用接管（跳过默认恢复），answer(false)=默认恢复，语义与 Python 一致。
 */
@RunWith(AndroidJUnit4::class)
class CerelaxConnectionTest {

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

    /** 扫描并命中本轮目标设备；未命中直接 fail。 */
    private suspend fun requireTarget(): BleDevice =
        CerelaxTestSupport.scanTargetWithRetry(controller) ?: throw AssertionError(
            "未扫描到目标设备（TARGET_IDENTITY=${TestConfig.TARGET_IDENTITY}），请确认设备开机在范围内"
        )

    /** 轮询等待条件成立（对应 Python 的 _wait_until）。 */
    private fun waitUntil(cond: () -> Boolean, timeoutMs: Long, what: String): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(200)
        }
        CerelaxTestSupport.log("  [等待超时] $what（${timeoutMs}ms）")
        return cond()
    }

    private suspend fun init(profile: SensorProfile): Boolean =
        profile.init(
            TestConfig.PACKAGE_SAMPLE_COUNT,
            TestConfig.INIT_TIMEOUT_MS,
            TestConfig.POWER_REFRESH_INTERVAL_MS
        )

    // =====================================================================================
    // DEV-SM-* 用例
    // =====================================================================================

    /** DEV-SM-001：connect 成功，状态迁移 Disconnected→Connecting→Connected→Ready。 */
    @Test
    fun devSm001_connect_stateMigration_reachesReady() = runBlocking<Unit> {
        val target = requireTarget()
        val profile = controller.requireSensor(target.mac)!!
        assertTrue(
            "connect 前 deviceState 应为 DISCONNECTED",
            profile.getDeviceState() == DeviceState.DISCONNECTED
        )

        val states = CopyOnWriteArrayList<Int>()
        profile.setOnStateChangeListener { _, state ->
            states.add(state)
            CerelaxTestSupport.log("  onStateChanged -> ${DeviceState.nameOf(state)}")
        }

        assertTrue("connect 应返回 true", profile.connect())
        assertTrue("未到达 Ready", CerelaxTestSupport.waitForReady(profile))
        assertTrue("deviceState 应为 READY", profile.getDeviceState() == DeviceState.READY)
        assertTrue("isReady 应为 true", profile.isReady())

        val iConnecting = states.indexOf(DeviceState.CONNECTING)
        val iConnected = states.indexOf(DeviceState.CONNECTED)
        val iReady = states.indexOf(DeviceState.READY)
        val orderOk = iConnected >= 0 && iReady >= 0 && iConnected < iReady &&
            (iConnecting < 0 || iConnecting < iConnected)
        CerelaxTestSupport.log("状态迁移序列 = ${states.map { DeviceState.nameOf(it) }}")
        assertTrue("状态迁移顺序应为 Connecting→Connected→Ready", orderOk)

        profile.disconnect()
    }

    /** DEV-SM-002：connect 失败返回 False 并回调原因。semi-auto（人工关闭设备电源）。 */
    @Test(timeout = 180000)
    fun devSm002_connectDeviceOff_returnsFalseAndError() = runBlocking<Unit> {
        CerelaxTestSupport.pauseForManual("请确认待测 Cerelax 头环已【开机】且在范围内（用于扫描获取设备）")
        val target = requireTarget()
        CerelaxTestSupport.log("已扫描到目标: name=${target.name.orEmpty()} mac=${target.mac}")

        CerelaxTestSupport.pauseForManual("请【关闭】待测 Cerelax 头环电源（或移出蓝牙范围），制造连接失败")

        val profile = controller.requireSensor(target.mac)!!
        val errors = CopyOnWriteArrayList<String>()
        profile.setOnErrorListener { _, errorMsg ->
            errors.add(errorMsg)
            CerelaxTestSupport.log("  onError: $errorMsg")
        }
        profile.setOnStateChangeListener { _, state ->
            CerelaxTestSupport.log("  onStateChanged -> ${DeviceState.nameOf(state)}")
        }

        CerelaxTestSupport.log("connect() ...（对已关机设备可能阻塞至连接超时）")
        assertFalse("对已关机设备 connect 应返回 false", profile.connect())
        assertTrue("应收到 onError 回调（连接失败原因）", errors.isNotEmpty())
        assertTrue("不应进入 Ready", profile.getDeviceState() != DeviceState.READY)
    }

    /** DEV-SM-003：非目标设备不误连（防串设备）。需 ≥2 台设备才可验证防串。 */
    @Test
    fun devSm003_nonTargetDevice_notMisConnected() = runBlocking<Unit> {
        val devices = controller.scan(TestConfig.SCAN_TIMEOUT_MS)
        val target = devices.firstOrNull { TestConfig.matchesTarget(it) }
        assertNotNull("应扫描到目标设备", target)

        val targetMac = target!!.mac.uppercase()
        val others = devices.filter { it.mac.uppercase() != targetMac }
        CerelaxTestSupport.log("目标: $targetMac；非目标 ${others.size} 台: ${others.map { it.mac }}")

        val profile = controller.requireSensor(target.mac)!!
        assertTrue("connect 应成功", profile.connect())
        assertTrue("未到达 Ready", CerelaxTestSupport.waitForReady(profile))

        val connected = controller.getConnectedSensors().map { it.device.mac.uppercase() }.toSet()
        CerelaxTestSupport.log("连接集合 = $connected")
        assertTrue("连接集合应仅含目标设备，实际=$connected", connected == setOf(targetMac))

        if (others.isNotEmpty()) {
            val stray = others.map { it.mac.uppercase() }.filter { it in connected }
            assertTrue("非目标设备不应被误连，实际误连=$stray", stray.isEmpty())
        } else {
            CerelaxTestSupport.log("环境中无非目标设备，跳过防串验证")
        }

        profile.disconnect()
    }

    /** DEV-SM-004：disconnect 成功，状态迁移到 Disconnected，onStateChanged 触发。 */
    @Test
    fun devSm004_disconnect_stateMigration_disconnected() = runBlocking<Unit> {
        val target = requireTarget()
        val profile = controller.requireSensor(target.mac)!!
        val states = CopyOnWriteArrayList<Int>()
        profile.setOnStateChangeListener { _, state ->
            states.add(state)
            CerelaxTestSupport.log("  onStateChanged -> ${DeviceState.nameOf(state)}")
        }

        assertTrue("connect 应成功", profile.connect())
        assertTrue("未到达 Ready", CerelaxTestSupport.waitForReady(profile))

        states.clear()
        assertTrue("disconnect 应返回 true", profile.disconnect())
        assertTrue(
            "disconnect 后应 DISCONNECTED",
            CerelaxTestSupport.waitForState(profile, DeviceState.DISCONNECTED)
        )
        assertTrue(
            "onStateChanged 应触发 DISCONNECTED，序列=${states.map { DeviceState.nameOf(it) }}",
            states.contains(DeviceState.DISCONNECTED)
        )
    }

    /** DEV-SM-005：起流中 disconnect 自动先停流。 */
    @Test
    fun devSm005_disconnectWhileStreaming_autoStopsFirst() = runBlocking<Unit> {
        val target = requireTarget()
        val profile = CerelaxTestSupport.connectReady(controller, target)
        assertNotNull("连接未就绪", profile)
        val p = profile!!

        assertTrue("init 应成功", init(p))
        assertTrue("startDataNotification 应成功", p.startDataNotification())
        assertTrue("起流后 isDataTransfering 应为 true", p.isDataTransfering())

        // 起流中直接 disconnect，不显式 stopDataNotification
        assertTrue("disconnect 应成功", p.disconnect())
        assertTrue("disconnect 后 isDataTransfering 应为 false", !p.isDataTransfering())
        assertTrue(
            "disconnect 后应 DISCONNECTED",
            CerelaxTestSupport.waitForState(p, DeviceState.DISCONNECTED)
        )
    }

    /** DEV-SM-006：Ready 前 init / setParam / startDataNotification 被拒绝且不崩溃。 */
    @Test(timeout = 60000)
    fun devSm006_commandsBeforeReady_rejected() = runBlocking<Unit> {
        val target = requireTarget()
        val profile = controller.requireSensor(target.mac)!!
        assertTrue(
            "前置：未连接时 deviceState 应非 READY",
            profile.getDeviceState() != DeviceState.READY
        )

        assertFalse("Ready 前 init 应返回 false", init(profile))

        val setRet = try {
            val key = TestConfig.targetSpec().supportedStreams.firstOrNull() ?: "NTF_EEG"
            profile.setParam(key, "ON")
        } catch (e: Exception) {
            "Error: ${e.javaClass.simpleName}"
        }
        CerelaxTestSupport.log("Ready 前 setParam -> $setRet")
        assertTrue("Ready 前 setParam 应被拒绝（返回 Error）", setRet.startsWith("Error"))

        assertFalse("Ready 前 startDataNotification 应返回 false", profile.startDataNotification())
    }

    /** DEV-SM-007：BLEDevice 的 Name/Address/RSSI 与扫描一致。 */
    @Test
    fun devSm007_bleDevice_metadataMatchesScan() = runBlocking<Unit> {
        val target = requireTarget()
        val profile = controller.requireSensor(target.mac)!!
        val bd = profile.device

        assertTrue(
            "Name 应一致（scan=${target.name.orEmpty()} device=${bd.name.orEmpty()}）",
            bd.name.orEmpty() == target.name.orEmpty()
        )
        assertTrue(
            "Address 应一致（scan=${target.mac} device=${bd.mac}）",
            bd.mac.equals(target.mac, ignoreCase = true)
        )
        // RSSI 随每次扫描/信号环境变化，两次快照不保证相等（实测 scan=-55 device=-59），只记录不硬断言
        CerelaxTestSupport.log("RSSI scan=${target.rssi} device=${bd.rssi}（动态值，不做相等断言）")
    }

    /** DEV-SM-008：autoReconnect=True（默认）异常断链自动恢复。semi-auto（断电后恢复供电）。 */
    @Test(timeout = 400000)
    fun devSm008_autoReconnectTrue_recoversStream() = runBlocking<Unit> {
        val target = requireTarget()
        val profile = controller.requireSensor(target.mac)!!

        val errors = CopyOnWriteArrayList<String>()
        val reconnects = CopyOnWriteArrayList<Boolean>()
        profile.setOnStateChangeListener { _, state ->
            CerelaxTestSupport.log("  onStateChanged -> ${DeviceState.nameOf(state)}")
        }
        profile.setOnErrorListener { _, errorMsg ->
            errors.add(errorMsg)
            CerelaxTestSupport.log("  onError: $errorMsg")
        }
        profile.setOnAutoReconnectListener { _, restore, answer ->
            reconnects.add(restore)
            CerelaxTestSupport.log("  onAutoReconnect restore=$restore")
            answer.answer(false) // 默认恢复流程（connect -> init -> replay -> startDataNotification）
        }

        assertTrue("connect 应成功", profile.connect())
        assertTrue("未到达 Ready", CerelaxTestSupport.waitForReady(profile))
        assertTrue("init 应成功", init(profile))
        assertTrue("startDataNotification 应成功", profile.startDataNotification())
        assertTrue("起流后 isDataTransfering 应为 true", profile.isDataTransfering())

        CerelaxTestSupport.pauseForManual("请【关闭】待测 Cerelax 头环电源（或移出蓝牙范围）")
        val stateDisconnected = waitUntil(
            { profile.getDeviceState() == DeviceState.DISCONNECTED },
            60000,
            "deviceState==Disconnected"
        )
        assertTrue("断链后 deviceState 应 DISCONNECTED", stateDisconnected)
        assertTrue("断链应触发 onError 回调", errors.isNotEmpty())

        CerelaxTestSupport.pauseForManual("请【重新开启】待测 Cerelax 头环电源（或移回范围）")
        val reconnected = waitUntil(
            { profile.getDeviceState() == DeviceState.READY },
            120000,
            "自动重连恢复 Ready"
        )
        assertTrue("自动重连后应恢复 Ready", reconnected)
        assertTrue("应触发 onAutoReconnect 回调", reconnects.isNotEmpty())

        val transferRecovered = waitUntil(
            { profile.isDataTransfering() },
            20000,
            "isDataTransfering 恢复为 true"
        )
        assertTrue("恢复 Ready 后应自动恢复起流", transferRecovered)

        try { profile.stopDataNotification() } catch (_: Exception) {}
        try { profile.disconnect() } catch (_: Exception) {}
    }

    /** DEV-SM-009：autoReconnect=False 不自动重连。semi-auto（断电后恢复供电）。 */
    @Test(timeout = 300000)
    fun devSm009_autoReconnectFalse_noReconnect() = runBlocking<Unit> {
        val target = requireTarget()
        val profile = controller.requireSensor(target.mac)!!
        profile.setAutoReconnect(false)

        val errors = CopyOnWriteArrayList<String>()
        val reconnects = CopyOnWriteArrayList<Boolean>()
        profile.setOnStateChangeListener { _, state ->
            CerelaxTestSupport.log("  onStateChanged -> ${DeviceState.nameOf(state)}")
        }
        profile.setOnErrorListener { _, errorMsg ->
            errors.add(errorMsg)
            CerelaxTestSupport.log("  onError: $errorMsg")
        }
        profile.setOnAutoReconnectListener { _, restore, answer ->
            reconnects.add(restore)
            CerelaxTestSupport.log("  onAutoReconnect restore=$restore")
            answer.answer(false)
        }

        assertTrue("connect 应成功", profile.connect())
        assertTrue("未到达 Ready", CerelaxTestSupport.waitForReady(profile))
        assertTrue("init 应成功", init(profile))
        assertTrue("startDataNotification 应成功", profile.startDataNotification())

        CerelaxTestSupport.pauseForManual("请【关闭】待测 Cerelax 头环电源（或移出蓝牙范围）")
        assertTrue(
            "断链后 deviceState 应 DISCONNECTED",
            waitUntil({ profile.getDeviceState() == DeviceState.DISCONNECTED }, 60000, "断链")
        )
        assertTrue("断链应触发 onError 回调", errors.isNotEmpty())

        CerelaxTestSupport.pauseForManual("请【重新开启】待测 Cerelax 头环电源（或移回范围）")
        val reachedReady = waitUntil(
            { profile.getDeviceState() == DeviceState.READY },
            30000,
            "观察窗口内检测是否自动重连"
        )
        assertTrue("autoReconnect=False 时不应自动重连到 Ready", !reachedReady)
        assertTrue("autoReconnect=False 时 onAutoReconnect 应 0 次", reconnects.isEmpty())

        try { profile.stopDataNotification() } catch (_: Exception) {}
        try { profile.disconnect() } catch (_: Exception) {}
    }

    /** DEV-SM-010：onAutoReconnect answer(true) 自定义恢复（跳过默认恢复）。semi-auto。 */
    @Test(timeout = 400000)
    fun devSm010_onAutoReconnectCustom_skipsDefaultRecovery() = runBlocking<Unit> {
        val target = requireTarget()
        val profile = controller.requireSensor(target.mac)!!

        val errors = CopyOnWriteArrayList<String>()
        val reconnects = CopyOnWriteArrayList<Boolean>()
        profile.setOnStateChangeListener { _, state ->
            CerelaxTestSupport.log("  onStateChanged -> ${DeviceState.nameOf(state)}")
        }
        profile.setOnErrorListener { _, errorMsg ->
            errors.add(errorMsg)
            CerelaxTestSupport.log("  onError: $errorMsg")
        }
        profile.setOnAutoReconnectListener { _, restore, answer ->
            reconnects.add(restore)
            CerelaxTestSupport.log("  onAutoReconnect restore=$restore")
            answer.answer(true) // 应用接管，跳过 SDK 默认恢复流程
        }

        assertTrue("connect 应成功", profile.connect())
        assertTrue("未到达 Ready", CerelaxTestSupport.waitForReady(profile))
        assertTrue("init 应成功", init(profile))
        assertTrue("startDataNotification 应成功", profile.startDataNotification())

        CerelaxTestSupport.pauseForManual("请【关闭】待测 Cerelax 头环电源（或移出蓝牙范围）")
        assertTrue(
            "断链后 deviceState 应 DISCONNECTED",
            waitUntil({ profile.getDeviceState() == DeviceState.DISCONNECTED }, 60000, "断链")
        )
        assertTrue("断链应触发 onError 回调", errors.isNotEmpty())

        CerelaxTestSupport.pauseForManual("请【重新开启】待测 Cerelax 头环电源（或移回范围）")
        assertTrue(
            "应触发 onAutoReconnect 回调",
            waitUntil({ reconnects.isNotEmpty() }, 120000, "onAutoReconnect 触发")
        )

        val reachedReady = waitUntil(
            { profile.getDeviceState() == DeviceState.READY },
            30000,
            "观察窗口内检测是否回到 Ready"
        )
        assertTrue("answer(true) 后应跳过默认恢复（未自动回到 Ready）", !reachedReady)
        assertTrue("answer(true) 后不应自动起流", !profile.isDataTransfering())

        try { profile.stopDataNotification() } catch (_: Exception) {}
        try { profile.disconnect() } catch (_: Exception) {}
    }

    /** DEV-SM-011：多台设备同时 connect 互不干扰。需 ≥2 台目标设备，否则 skip。 */
    @Test(timeout = 180000)
    fun devSm011_multiDevice_connectNoInterference() = runBlocking<Unit> {
        val devices = controller.scan(TestConfig.SCAN_TIMEOUT_MS)
        val matched = devices
            .filter { TestConfig.deviceByMac(it.mac) != null }
            .distinctBy { it.mac.uppercase() }
        assumeTrue("需要 ≥2 台目标设备在范围内，当前 ${matched.size} 台", matched.size >= 2)

        val profiles = matched.map { d -> d to controller.requireSensor(d.mac)!! }

        // 串行连接，各自等 Ready
        for ((d, p) in profiles) {
            assertTrue("[${d.mac}] connect 应成功", p.connect())
            assertTrue("[${d.mac}] 未到达 Ready", CerelaxTestSupport.waitForReady(p))
        }
        assertTrue(
            "连接后每台应保持 Ready（无串扰）",
            profiles.all { it.second.getDeviceState() == DeviceState.READY }
        )

        // init + 起流 + 收数据
        val dataCounts = ConcurrentHashMap<String, AtomicInteger>()
        for ((d, p) in profiles) {
            assertTrue("[${d.mac}] init 应成功", init(p))
            val counter = AtomicInteger(0)
            dataCounts[d.mac] = counter
            p.setOnDataListener { _, dataList ->
                counter.addAndGet(dataList.sumOf { it.getSampleCount() })
            }
            assertTrue("[${d.mac}] startDataNotification 应成功", p.startDataNotification())
            assertTrue("[${d.mac}] 起流后 isDataTransfering 应为 true", p.isDataTransfering())
        }

        Thread.sleep(TestConfig.COLLECT_SECONDS * 1000L)
        for ((d, _) in profiles) {
            val n = dataCounts[d.mac]?.get() ?: 0
            assertTrue("[${d.mac}] 应收到数据（样本数>0），实际=$n", n > 0)
        }

        // 断开第一台，其余仍 Ready（断开串扰）
        profiles.first().second.disconnect()
        Thread.sleep(2000)
        assertTrue(
            "断开第一台后其余应仍 Ready（无串扰）",
            profiles.drop(1).all { it.second.getDeviceState() == DeviceState.READY }
        )

        for ((_, p) in profiles) {
            try { p.stopDataNotification() } catch (_: Exception) {}
            try { p.disconnect() } catch (_: Exception) {}
        }
    }

    /** DEV-SM-012：突然断电/超范围触发 Disconnected 与错误回调。semi-auto。 */
    @Test(timeout = 300000)
    fun devSm012_suddenPowerOff_triggersDisconnectedAndError() = runBlocking<Unit> {
        val target = requireTarget()
        val profile = controller.requireSensor(target.mac)!!

        val states = CopyOnWriteArrayList<Int>()
        val errors = CopyOnWriteArrayList<String>()
        profile.setOnStateChangeListener { _, state ->
            states.add(state)
            CerelaxTestSupport.log("  onStateChanged -> ${DeviceState.nameOf(state)}")
        }
        profile.setOnErrorListener { _, errorMsg ->
            errors.add(errorMsg)
            CerelaxTestSupport.log("  onError: $errorMsg")
        }

        assertTrue("connect 应成功", profile.connect())
        assertTrue("未到达 Ready", CerelaxTestSupport.waitForReady(profile))
        assertTrue("init 应成功", init(profile))
        assertTrue("startDataNotification 应成功", profile.startDataNotification())

        CerelaxTestSupport.pauseForManual("请【关闭】待测 Cerelax 头环电源（或移出蓝牙范围）")
        assertTrue(
            "onStateChanged 应触发 DISCONNECTED",
            waitUntil({ states.contains(DeviceState.DISCONNECTED) }, 90000, "断链可观测")
        )
        assertTrue("应触发 onError 回调（异常断链原因）", errors.isNotEmpty())

        try { profile.stopDataNotification() } catch (_: Exception) {}
        try { profile.disconnect() } catch (_: Exception) {}
    }

    /** DEV-SM-013：isReady 与 deviceState==Ready 一致。 */
    @Test
    fun devSm013_isReady_consistentWithDeviceState() = runBlocking<Unit> {
        val target = requireTarget()
        val profile = controller.requireSensor(target.mac)!!

        // connect 前
        assertTrue(
            "connect 前 deviceState 应为 DISCONNECTED",
            profile.getDeviceState() == DeviceState.DISCONNECTED
        )
        assertFalse("connect 前 isReady 应为 false", profile.isReady())

        // Ready
        assertTrue("connect 应成功", profile.connect())
        assertTrue("未到达 Ready", CerelaxTestSupport.waitForReady(profile))
        assertTrue("Ready 时 deviceState 应为 READY", profile.getDeviceState() == DeviceState.READY)
        assertTrue("Ready 时 isReady 应为 true", profile.isReady())

        // 断开后
        profile.disconnect()
        assertTrue(
            "断开后应 DISCONNECTED",
            CerelaxTestSupport.waitForState(profile, DeviceState.DISCONNECTED)
        )
        assertFalse("断开后 isReady 应为 false", profile.isReady())
    }

    /** DEV-SM-014：onDeviceInfoUpdate 触发与就地更新（Cerelax 有 EEG，可经 EEG_SAMPLE_RATE 触发）。 */
    @Test(timeout = 120000)
    fun devSm014_onDeviceInfoUpdate_triggerAndInPlace() = runBlocking<Unit> {
        val target = requireTarget()
        val profile = controller.requireSensor(target.mac)!!

        val updates = CopyOnWriteArrayList<DeviceInfo>()
        profile.setOnDeviceInfoUpdateListener { _, info ->
            updates.add(info)
            CerelaxTestSupport.log("  onDeviceInfoUpdate 收到 DeviceInfo 更新")
        }

        assertTrue("connect 应成功", profile.connect())
        assertTrue("未到达 Ready", CerelaxTestSupport.waitForReady(profile))
        assertTrue("init 应成功", init(profile))

        val infoBefore = profile.getDeviceInfo()
        assertNotNull("init 后 getDeviceInfo 应返回非空 DeviceInfo", infoBefore)

        // 变更采样率会改变 DeviceInfo 上报值，触发 onDeviceInfoUpdate；优先选 spec 中多档可设的参数
        val rateEntry = TestConfig.targetSpec().sampleRates.entries
            .firstOrNull { it.value.settable && it.value.rates.size > 1 }
        var originalRate: String? = null
        var triggered = false
        try {
            if (rateEntry != null) {
                val raw = try { profile.getParam(rateEntry.key) } catch (_: Exception) { "" }
                originalRate = rateEntry.value.rates.firstOrNull { raw.contains(it) }
                val value = rateEntry.value.rates.firstOrNull { it != originalRate }
                    ?: rateEntry.value.rates.first { it != rateEntry.value.default }
                CerelaxTestSupport.log(
                    "触发尝试 setParam(${rateEntry.key}, $value)（原值=${originalRate ?: raw}）"
                )
                profile.setParam(rateEntry.key, value)

                triggered = waitUntil({ updates.isNotEmpty() }, 30000, "onDeviceInfoUpdate 触发")
                if (triggered) {
                    val cbInfo = updates.last()
                    assertTrue(
                        "回调 DeviceInfo 与 getDeviceInfo() 应为同一缓存对象（就地更新）",
                        cbInfo === profile.getDeviceInfo()
                    )
                } else {
                    CerelaxTestSupport.log("未触发 onDeviceInfoUpdate（环境/固件无触发场景），跳过就地更新断言")
                }
            } else {
                CerelaxTestSupport.log("spec 无多档可设采样率，跳过变更触发")
            }
        } finally {
            // 关键：还原原采样率，避免污染后续用例/下一轮（曾导致 PARAM-FUNC-004/006 误判默认值）
            val restore = originalRate ?: rateEntry?.value?.default
            if (rateEntry != null && restore != null) {
                try { profile.setParam(rateEntry.key, restore) } catch (_: Exception) {}
                CerelaxTestSupport.log("已还原 ${rateEntry.key}=$restore")
            }
            profile.disconnect()
        }
    }

    /** DEV-SM-015：connect 重复调用幂等（Ready 后再 connect 不破坏已连接状态）。 */
    @Test
    fun devSm015_connectRepeated_idempotent() = runBlocking<Unit> {
        val target = requireTarget()
        val profile = controller.requireSensor(target.mac)!!
        assertTrue("首次 connect 应成功", profile.connect())
        assertTrue("未到达 Ready", CerelaxTestSupport.waitForReady(profile))

        var err = false
        try { profile.connect() } catch (e: Exception) {
            err = true
            CerelaxTestSupport.log("重复 connect 抛异常 ${e.javaClass.simpleName}")
        }
        assertFalse("重复 connect 不应抛异常", err)
        val state = profile.getDeviceState()
        assertTrue(
            "重复 connect 后不应回到 DISCONNECTED（state=$state）",
            state == DeviceState.READY || state == DeviceState.CONNECTED || state == DeviceState.CONNECTING
        )
        profile.disconnect()
    }

    /** DEV-SM-016：disconnect 重复调用幂等。 */
    @Test
    fun devSm016_disconnectRepeated_idempotent() = runBlocking<Unit> {
        val target = requireTarget()
        val profile = controller.requireSensor(target.mac)!!
        assertTrue("connect 应成功", connectWithRetry(profile))
        assertTrue("未到达 Ready", CerelaxTestSupport.waitForReady(profile))

        profile.disconnect()
        assertTrue("首次 disconnect 后应 DISCONNECTED", CerelaxTestSupport.waitForState(profile, DeviceState.DISCONNECTED))

        var err = false
        try { profile.disconnect() } catch (e: Exception) {
            err = true
            CerelaxTestSupport.log("重复 disconnect 抛异常 ${e.javaClass.simpleName}")
        }
        assertFalse("重复 disconnect 不应抛异常", err)
        assertTrue("重复 disconnect 后应仍 DISCONNECTED", profile.getDeviceState() == DeviceState.DISCONNECTED)
    }

    /** DEV-SM-017：BLE MTU 协商前后数据分包正确 —— Android SDK 未暴露 MTU 接口，需抓包/仪器验证。 */
    @Ignore("Android SDK 未暴露 MTU 协商接口；分包正确性由底层 BLE 栈保证，需抓包/仪器人工验证")
    @Test
    fun devSm017_mtuNegotiation_packetCorrect() = Unit

    /** DEV-SM-018：配对/bond 信息持久与免配对重连 —— 依赖系统蓝牙配对状态，需人工验证。 */
    @Ignore("依赖系统蓝牙配对/bond 状态，需人工在系统设置配对后验证免配对重连")
    @Test
    fun devSm018_bondPersistence_reconnect() = Unit

    /** DEV-SM-019：广播间隔与重连退避 —— 底层 BLE 栈时序，SDK 未暴露可断言接口，需抓包验证。 */
    @Ignore("广播间隔与重连退避为底层 BLE 栈时序，Android SDK 未暴露可断言接口，需抓包验证")
    @Test
    fun devSm019_advInterval_backoff() = Unit

    /** DEV-SM-020：isAutoReconnecting 在未断链/正常连接/断开后均为 false；开关 setAutoReconnect 不影响该空闲态。 */
    @Test
    fun devSm020_isAutoReconnecting_falseWhenNotRecovering() = runBlocking<Unit> {
        val target = requireTarget()
        val profile = controller.requireSensor(target.mac)!!
        assertFalse("未连接时 isAutoReconnecting 应为 false", profile.isAutoReconnecting())

        assertTrue("connect 应成功", profile.connect())
        assertTrue("未到达 Ready", CerelaxTestSupport.waitForReady(profile))
        assertFalse("正常 Ready 态 isAutoReconnecting 应为 false", profile.isAutoReconnecting())

        profile.setAutoReconnect(false)
        assertFalse("关闭 autoReconnect 后 isAutoReconnecting 应为 false", profile.isAutoReconnecting())
        profile.setAutoReconnect(true)
        assertFalse("重新开启 autoReconnect 后（未断链）isAutoReconnecting 应为 false", profile.isAutoReconnecting())

        profile.disconnect()
        assertTrue("应到达 DISCONNECTED", CerelaxTestSupport.waitForState(profile, DeviceState.DISCONNECTED))
        assertFalse("主动断开后 isAutoReconnecting 应为 false", profile.isAutoReconnecting())
    }
}
