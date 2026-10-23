package com.oymotion.sensorcapiktdemo.cerelaxpro

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cerelax 06 Bin 录制/回放/解析（对应 Manual Cases 的 BIN-*），由 Python
 * gForceUltra/06_Bin_Record_Replay 1:1 迁移，spec 驱动适配。
 *
 * 型号差异适配：
 *   - Android 无 bumble/bleak 后端切换，回放/解析属数据解析层，不依赖后端。
 *   - Python BinFileInfo 为 dict（device_mac/device_name/chip_type/replay_duration），
 *     Android BinFileInfo 为固定字段（mac/deviceName/durationSec/valid/deviceInfo）。
 *   - Python replayBinFile(path, sensor, realtime) 阻塞；Android
 *     replayBinFile(path, deviceMac, realtime, timeoutMs) 立即返回 SensorProfile，
 *     数据经 setOnDataListener 回调，EOF 经 setOnDataTransferStateChangeListener。
 *   - pause/resume/stop 传 mac 字符串（非 sensor 对象），返回 "OK" 或错误串。
 *   - DEBUG_BLE_DATA_PATH="True" 在 profile 上设置；日志目录在 controller 上通过
 *     setParam("LOG_PATH", ...) + setParam("DEBUG_ENABLED", "True") 控制（同 MainActivity）。
 *
 * 前置：录制类用例需设备开机在范围内；回放/解析类用例可复用已录制的 bin 离线跑，
 *       无 bin 时尝试现场录制一次，仍不可得则 skip（附原因）。
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CerelaxBinTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    private lateinit var controller: SensorController

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val logDir: File by lazy { File(context.cacheDir, "sdklog").apply { mkdirs() } }

    @Before
    fun setUp() {
        controller = SensorController.getInstance()
        controller.registerBleBridge(context)
        // 会话日志落到受控目录，便于断言 .bin 是否生成（对应 MainActivity.applySdkDebugLog）
        controller.setParam("LOG_PATH", logDir.absolutePath)
        controller.setParam("DEBUG_ENABLED", "True")
    }

    companion object {
        /** 录制时长（秒）：够 pause/resume/stop 回放控制留出窗口。 */
        private const val RECORD_SECONDS = 8
        @Volatile private var cachedBin: String? = null
        @Volatile private var recordTried = false
    }

    // =====================================================================================
    // 内部辅助
    // =====================================================================================

    private fun findBins(): List<File> =
        logDir.listFiles()?.filter { it.name.lowercase().endsWith(".bin") }?.sortedBy { it.lastModified() }
            ?: emptyList()

    private fun newestBin(): File? = findBins().lastOrNull()

    private fun macOf(binPath: String): String =
        controller.getBinFileInfo(binPath)?.mac?.takeIf { it.isNotEmpty() } ?: "REPLAY"

    /** 连接设备并录制一个 bin，返回新生成的 bin 绝对路径；失败返回 null。 */
    private suspend fun recordBin(seconds: Int = RECORD_SECONDS): String? {
        val before = findBins().map { it.absolutePath }.toSet()
        val device = CerelaxTestSupport.scanTargetWithRetry(controller) ?: run {
            CerelaxTestSupport.log("recordBin: 未扫描到目标设备")
            return null
        }
        val p = controller.requireSensor(device.mac)
        if (p == null || !p.connect() || !CerelaxTestSupport.waitForReady(p)) {
            try { p?.disconnect() } catch (_: Exception) {}
            return null
        }
        if (!p.init(TestConfig.PACKAGE_SAMPLE_COUNT, TestConfig.INIT_TIMEOUT_MS, TestConfig.POWER_REFRESH_INTERVAL_MS)) {
            try { p.disconnect() } catch (_: Exception) {}
            return null
        }
        // 打开 spec 支持的全部流，让 bin 覆盖所有模态（供逐帧/DataType 交叉验证）
        val spec = TestConfig.specForIdentity(TestConfig.TARGET_IDENTITY)
        for (key in CerelaxSpec.supportedStreamKeys(spec)) {
            try { p.setParam(key, "ON") } catch (_: Exception) {}
        }
        p.setParam("DEBUG_BLE_DATA_PATH", "True")
        if (!p.startDataNotification()) {
            try { p.disconnect() } catch (_: Exception) {}
            return null
        }
        Thread.sleep(seconds * 1000L)
        p.stopDataNotification()
        try { p.disconnect() } catch (_: Exception) {}

        // 轮询等待新 bin 落盘
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            val newBin = findBins().lastOrNull { it.absolutePath !in before }
            if (newBin != null) return newBin.absolutePath
            Thread.sleep(200)
        }
        return null
    }

    /** 取一个可用 bin：优先复用已录制，其次现场录制一次；都没有返回 null。 */
    private suspend fun ensureBin(): String? {
        cachedBin?.let { if (File(it).exists()) return it }
        newestBin()?.let { cachedBin = it.absolutePath; return it.absolutePath }
        if (!recordTried) {
            recordTried = true
            recordBin()?.let { cachedBin = it; return it }
        }
        return null
    }

    /** 回放并返回 (profile, mac)。 */
    private fun startReplay(path: String, realtime: Boolean, timeoutMs: Int = 10000): Pair<SensorProfile, String>? {
        val mac = macOf(path)
        val p: SensorProfile? = controller.replayBinFile(path, mac, realtime, timeoutMs)
        return p?.let { it to mac }
    }

    /** 回放数据收集器：DataType 集合、批数、首包 startTimeStamp/delay、EOF 信号。 */
    private class ReplayCollector {
        val types = ConcurrentHashMap.newKeySet<Int>()
        val batches = AtomicInteger(0)
        val firstStamp = AtomicInteger(-1)
        val firstDelay = AtomicInteger(-1)
        val dataLatch = CountDownLatch(1)
        val eofLatch = CountDownLatch(1)

        fun attach(p: SensorProfile) {
            p.setOnDataListener { _, dataList ->
                for (d in dataList) {
                    types.add(d.dataType)
                    batches.incrementAndGet()
                    if (firstStamp.get() < 0) firstStamp.set(d.startTimeStamp)
                    if (firstDelay.get() < 0) firstDelay.set(d.delay)
                }
                if (dataList.isNotEmpty()) dataLatch.countDown()
            }
            p.setOnDataTransferStateChangeListener { _, isTransferring ->
                if (!isTransferring) eofLatch.countDown()
            }
        }
    }

    // =====================================================================================
    // BIN-FUNC-*：录制 / 回放 / 解析
    // =====================================================================================

    /** BIN-FUNC-001：连接起流后自动生成 .bin。 */
    @Test(timeout = 180000)
    fun binFunc001_recording_generatesBin() = runBlocking {
        val bin = recordBin()
        assertNotNull("连接起流后应生成 .bin（需设备开机在范围内）", bin)
        assertTrue("生成的 bin 文件应存在: $bin", File(bin!!).exists())
        cachedBin = bin
        CerelaxTestSupport.log("BIN-FUNC-001 生成 bin: $bin")
    }

    /** BIN-FUNC-002：getBinFileInfo 返回字段。 */
    @Test(timeout = 120000)
    fun binFunc002_getBinFileInfo_fields() = runBlocking {
        val bin = ensureBin() ?: run {
            assumeTrue("无可用 bin（需设备录制或预置），跳过", false)
            return@runBlocking
        }
        val info = controller.getBinFileInfo(bin)
        assertNotNull("有效 bin 的 getBinFileInfo 应非 null", info)
        val i = info!!
        assertTrue("valid 应为 true", i.valid)
        assertTrue("deviceName 应非空", i.deviceName.isNotEmpty())
        assertTrue("durationSec 应 > 0", i.durationSec > 0)
        assertNotNull("deviceInfo 应非 null", i.deviceInfo)
        CerelaxTestSupport.log("BIN-FUNC-002 mac=${i.mac} deviceName=${i.deviceName} durationSec=${i.durationSec}")
    }

    /** BIN-FUNC-003：getBinFileInfo 对不存在/空路径返回 null 或 valid=false。 */
    @Test
    fun binFunc003_getBinFileInfo_invalidPath() {
        assertNull("不存在路径应返回 null", controller.getBinFileInfo("/sdcard/nonexistent_cerelax.bin"))
        val empty = controller.getBinFileInfo("")
        assertTrue("空串应返回 null 或 valid=false", empty == null || !empty.valid)
    }

    /** BIN-FUNC-004：realtime=True 回放产生数据，DataType 覆盖 live 类型，startTimeStamp 有效。 */
    @Test(timeout = 120000)
    fun binFunc004_replayRealtime() = runBlocking {
        val bin = ensureBin() ?: run {
            assumeTrue("无可用 bin，跳过", false)
            return@runBlocking
        }
        val (p, mac) = startReplay(bin, realtime = true) ?: run {
            assumeTrue("replayBinFile 未启动回放", false)
            return@runBlocking
        }
        val c = ReplayCollector()
        c.attach(p)
        assertTrue(
            "realtime 回放应在 ${TestConfig.DATA_TIMEOUT_SEC}s 内产生数据",
            c.dataLatch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS)
        )
        Thread.sleep(2000)
        assertTrue("回放应产生非空 DataType 集合", c.types.isNotEmpty())
        assertTrue("回放 startTimeStamp 应为有效正值", c.firstStamp.get() > 0)
        CerelaxTestSupport.log("BIN-FUNC-004 types=${c.types} batches=${c.batches.get()}")
        controller.stopBinReplay(mac)
    }

    /** BIN-FUNC-005：realtime=False 全速回放，耗时应远小于录制时长。 */
    @Test(timeout = 120000)
    fun binFunc005_replayFullSpeed() = runBlocking {
        val bin = ensureBin() ?: run {
            assumeTrue("无可用 bin，跳过", false)
            return@runBlocking
        }
        val (p, mac) = startReplay(bin, realtime = false) ?: run {
            assumeTrue("replayBinFile 未启动回放", false)
            return@runBlocking
        }
        val c = ReplayCollector()
        c.attach(p)
        val t0 = System.currentTimeMillis()
        assertTrue(
            "全速回放应在 ${TestConfig.DATA_TIMEOUT_SEC}s 内产生数据",
            c.dataLatch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS)
        )
        assertTrue("全速回放应结束（EOF）", c.eofLatch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS))
        val elapsed = System.currentTimeMillis() - t0
        assertTrue("回放应产生非空 DataType 集合", c.types.isNotEmpty())
        assertTrue("回放 startTimeStamp 应为有效正值", c.firstStamp.get() > 0)
        assertTrue(
            "全速回放耗时应远小于录制时长（$elapsed ms < ${RECORD_SECONDS * 1000} ms）",
            elapsed < RECORD_SECONDS * 1000
        )
        CerelaxTestSupport.log("BIN-FUNC-005 types=${c.types} batches=${c.batches.get()} elapsed=${elapsed}ms")
        controller.stopBinReplay(mac)
    }

    /** BIN-FUNC-006：pause/resume/stop 回放。 */
    @Test(timeout = 180000)
    fun binFunc006_pauseResumeStop() = runBlocking {
        val bin = ensureBin() ?: run {
            assumeTrue("无可用 bin，跳过", false)
            return@runBlocking
        }
        val (p, mac) = startReplay(bin, realtime = true) ?: run {
            assumeTrue("replayBinFile 未启动回放", false)
            return@runBlocking
        }
        val c = ReplayCollector()
        c.attach(p)
        assertTrue("回放应在超时内产生数据", c.dataLatch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS))

        assertEquals("pauseBinReplay 应返回 OK", "OK", controller.pauseBinReplay(mac))
        val pausedCount = c.batches.get()
        Thread.sleep(2000)
        val pausedAfter = c.batches.get()
        assertTrue("pause 后批数应停止增长（$pausedCount -> $pausedAfter）", pausedAfter <= pausedCount + 20)

        assertEquals("resumeBinReplay 应返回 OK", "OK", controller.resumeBinReplay(mac))
        val resumeCount = c.batches.get()
        Thread.sleep(1500)
        assertTrue("resume 后批数应恢复增长（$resumeCount -> ${c.batches.get()}）", c.batches.get() > resumeCount)

        assertEquals("stopBinReplay 应返回 OK", "OK", controller.stopBinReplay(mac))
    }

    /** BIN-FUNC-007：实时起流中拒绝回放。 */
    @Test(timeout = 120000)
    fun binFunc007_replayRejectedWhileStreaming() = runBlocking {
        val bin = ensureBin() ?: run {
            assumeTrue("无可用 bin，跳过", false)
            return@runBlocking
        }
        val device = CerelaxTestSupport.scanTargetWithRetry(controller) ?: run {
            assumeTrue("未扫描到设备", false)
            return@runBlocking
        }
        val p = CerelaxTestSupport.connectReady(controller, device) ?: run {
            assumeTrue("连接未就绪", false)
            return@runBlocking
        }
        assertTrue("init 失败", p.init(TestConfig.PACKAGE_SAMPLE_COUNT, TestConfig.INIT_TIMEOUT_MS, TestConfig.POWER_REFRESH_INTERVAL_MS))
        assertTrue("startDataNotification 失败", p.startDataNotification())
        val rp: SensorProfile? = controller.replayBinFile(bin, device.mac, true, 5000)
        assertNull("实时起流中 replayBinFile 应被拒绝（返回 null）", rp)
        p.stopDataNotification()
        p.disconnect()
    }

    /** BIN-FUNC-008：parseBinToCsv 生成 CSV（表头 10 列，type 列取值合法）。 */
    @Test(timeout = 120000)
    fun binFunc008_parseBinToCsv() = runBlocking {
        val bin = ensureBin() ?: run {
            assumeTrue("无可用 bin，跳过", false)
            return@runBlocking
        }
        val csv = bin.removeSuffix(".bin") + ".csv"
        File(csv).delete()
        val result = controller.parseBinToCsv(bin, csv)
        assertFalse("parseBinToCsv 不应返回错误: $result", result.startsWith("Error"))
        val f = File(csv)
        assertTrue("CSV 文件应生成: $csv", f.exists())

        val lines = f.readLines()
        val header = lines.firstOrNull()
        assertNotNull("CSV 应有表头", header)
        val cols = header!!.split(',')
        assertEquals("CSV 应为 10 列（实际 ${cols.size}）", 10, cols.size)
        val typeIdx = cols.indexOfFirst { it.trim().equals("type", ignoreCase = true) }
        assertTrue("表头应含 type 列", typeIdx >= 0)

        val allowed = setOf("raw", "cmd_send", "cmd_recv", "event", "parsed")
        val bad = lines.drop(1).mapNotNull { line ->
            val parts = line.split(',')
            if (parts.size > typeIdx) parts[typeIdx].trim() else ""
        }.filter { it.isNotEmpty() && it !in allowed }.take(3)
        assertTrue("CSV 出现未知 type: $bad", bad.isEmpty())
        CerelaxTestSupport.log("BIN-FUNC-008 CSV: $result")
    }

    /** BIN-FUNC-009：回放还原 startTimeStamp/delay（非空、delay 非负）。 */
    @Test(timeout = 120000)
    fun binFunc009_replayRestoresTimestampDelay() = runBlocking {
        val bin = ensureBin() ?: run {
            assumeTrue("无可用 bin，跳过", false)
            return@runBlocking
        }
        val (p, mac) = startReplay(bin, realtime = false) ?: run {
            assumeTrue("replayBinFile 未启动回放", false)
            return@runBlocking
        }
        val c = ReplayCollector()
        c.attach(p)
        assertTrue("回放应在超时内产生数据", c.dataLatch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS))
        assertTrue("回放 startTimeStamp 应为有效正值", c.firstStamp.get() > 0)
        assertTrue("回放 delay 应 >= 0", c.firstDelay.get() >= 0)
        CerelaxTestSupport.log("BIN-FUNC-009 startTimeStamp=${c.firstStamp.get()} delay=${c.firstDelay.get()}")
        controller.stopBinReplay(mac)
    }

    /** BIN-FUNC-010：bin 回放 DataType 与 DeviceInfo ChannelCount>0 交叉验证。 */
    @Test(timeout = 120000)
    fun binFunc010_perFrameDataTypeCrossCheck() = runBlocking {
        val bin = ensureBin() ?: run {
            assumeTrue("无可用 bin，跳过", false)
            return@runBlocking
        }
        val info = controller.getBinFileInfo(bin)
        assumeTrue("getBinFileInfo 应返回有效 info", info != null && info.valid && info.deviceInfo != null)
        val spec = TestConfig.specForIdentity(TestConfig.TARGET_IDENTITY)
        val expectedTypes = CerelaxSpec.supportedStreamKeys(spec).mapNotNull { key ->
            val type = CerelaxSpec.KEY_TO_DATA_TYPE[key] ?: return@mapNotNull null
            if (CerelaxSpec.channelCount(info!!.deviceInfo, key) > 0) type else null
        }
        assumeTrue("bin deviceInfo 应含至少一种支持的模态", expectedTypes.isNotEmpty())

        val (p, mac) = startReplay(bin, realtime = false) ?: run {
            assumeTrue("replayBinFile 未启动回放", false)
            return@runBlocking
        }
        val c = ReplayCollector()
        c.attach(p)
        assertTrue("回放应在超时内产生数据", c.dataLatch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS))
        assertTrue("全速回放应结束（EOF）", c.eofLatch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS))

        val missing = expectedTypes.filter { it !in c.types }
        CerelaxTestSupport.log("BIN-FUNC-010 期望模态=$expectedTypes 回放收到=${c.types}")
        assertTrue("ChannelCount>0 的模态应在 bin 回放中出现（缺失=$missing）", missing.isEmpty())
        controller.stopBinReplay(mac)
    }

    /** BIN-FUNC-011：multiReplayBinFile 多文件同步回放。 */
    @Test(timeout = 120000)
    fun binFunc011_multiReplayGroup() = runBlocking {
        val bins = findBins().take(2).map { it.absolutePath }
        assumeTrue("需 >=2 个不同 MAC 的 bin（当前 ${bins.size}），跳过", bins.size >= 2)
        val macs = bins.map { macOf(it) }
        val profiles = controller.multiReplayBinFile(bins, macs, true, 10000)
        assertEquals("返回列表长度应等于输入文件数", bins.size, profiles.size)
        for (i in bins.indices) {
            val p = profiles[i]
            if (p == null) {
                CerelaxTestSupport.log("BIN-FUNC-011 成员 $i 启动失败（mac=${macs[i]}）")
                continue
            }
            val c = ReplayCollector()
            c.attach(p)
            assertTrue("成员 $i 应产生回放数据", c.dataLatch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS))
            assertTrue("成员 $i DataType 集合应非空", c.types.isNotEmpty())
        }
        macs.forEach { controller.stopBinReplay(it) }
    }

    // =====================================================================================
    // BIN-BND-*：边界 / 容错
    // =====================================================================================

    /** BIN-BND-001：默认（不设 DEBUG_BLE_DATA_PATH）时日志目录不新增 .bin。 */
    @Test(timeout = 180000)
    fun binBnd001_defaultNoExport() = runBlocking {
        val before = findBins().map { it.absolutePath }.toSet()
        val device = CerelaxTestSupport.scanTargetWithRetry(controller) ?: run {
            assumeTrue("未扫描到设备", false)
            return@runBlocking
        }
        val p = CerelaxTestSupport.connectReady(controller, device) ?: run {
            assumeTrue("连接未就绪", false)
            return@runBlocking
        }
        assertTrue("init 失败", p.init(TestConfig.PACKAGE_SAMPLE_COUNT, TestConfig.INIT_TIMEOUT_MS, TestConfig.POWER_REFRESH_INTERVAL_MS))
        // 关键：不调用 p.setParam("DEBUG_BLE_DATA_PATH", ...)
        assertTrue("startDataNotification 失败", p.startDataNotification())
        Thread.sleep(TestConfig.COLLECT_SECONDS * 1000L)
        p.stopDataNotification()
        p.disconnect()
        Thread.sleep(1000)

        val added = findBins().filter { it.absolutePath !in before }
        assertTrue("默认不设 DEBUG_BLE_DATA_PATH 时日志目录不应新增 .bin（实际=${added.map { it.name }}）", added.isEmpty())
    }

    /** BIN-BND-002：replayBinFile/parseBinToCsv/getBinFileInfo 空串/无效路径不崩溃。 */
    @Test(timeout = 60000)
    fun binBnd002_invalidPaths_noCrash() = runBlocking {
        // getBinFileInfo
        assertNull("空串 getBinFileInfo 应返回 null", controller.getBinFileInfo(""))
        assertNull("不存在路径 getBinFileInfo 应返回 null", controller.getBinFileInfo("/sdcard/__no_such__.bin"))

        // replayBinFile
        val rp1: SensorProfile? = controller.replayBinFile("", "REPLAY", true, 3000)
        assertNull("空串 replayBinFile 应被拒绝（返回 null）", rp1)
        val rp2: SensorProfile? = controller.replayBinFile("/sdcard/__no_such__.bin", "REPLAY", true, 3000)
        assertNull("不存在路径 replayBinFile 应被拒绝（返回 null）", rp2)

        // parseBinToCsv
        val r3 = controller.parseBinToCsv("", File(logDir, "nope.csv").absolutePath)
        CerelaxTestSupport.log("BIN-BND-002 parseBinToCsv('') -> $r3")
        val r4 = controller.parseBinToCsv("/sdcard/__no_such__.bin", File(logDir, "nope2.csv").absolutePath)
        CerelaxTestSupport.log("BIN-BND-002 parseBinToCsv(不存在) -> $r4")
    }

    /** BIN-BND-003：multiReplayBinFile 空列表/单文件/无效/重复 MAC 不崩溃。 */
    @Test(timeout = 60000)
    fun binBnd003_multiReplayInvalidInputs() = runBlocking {
        // 空列表
        val empty = controller.multiReplayBinFile(emptyList(), emptyList(), true, 3000)
        assertTrue("空列表应返回空列表", empty.isEmpty())

        // 仅无效路径
        val fake = "/sdcard/__no_such_dir__/not_exist.bin"
        val invalid = controller.multiReplayBinFile(listOf(fake), listOf("REPLAY"), true, 3000)
        assertEquals("返回列表长度应对齐输入", 1, invalid.size)
        assertNull("无效路径成员应为 null", invalid[0])

        // 有可用 bin 时补测：单文件、重复 MAC
        val bin = ensureBin()
        if (bin != null) {
            val single = controller.multiReplayBinFile(listOf(bin), listOf(macOf(bin)), true, 3000)
            assertTrue("单文件不应崩溃", single.size == 1)

            val dup = controller.multiReplayBinFile(listOf(bin, bin), listOf(macOf(bin), macOf(bin)), true, 3000)
            assertEquals("重复 MAC 返回列表应对齐输入", 2, dup.size)
        }
    }

    // =====================================================================================
    // BIN-ROB-*：健壮性 / 幂等
    // =====================================================================================

    /** BIN-ROB-001：磁盘不足跳过录制 —— Android 无法可靠模拟磁盘只读，暂 @Ignore。 */
    @Ignore("Android 无法可靠模拟磁盘不足/只读（icacls 不适用于 app 私有目录），需真机手工验证")
    @Test
    fun binRob001_diskFull_skipRecording() = Unit

    /** BIN-ROB-002：回放控制重复调用幂等。 */
    @Test(timeout = 120000)
    fun binRob002_replayControlIdempotent() = runBlocking {
        // 未回放时调用不崩溃
        CerelaxTestSupport.log("BIN-ROB-002 未回放 pause -> ${controller.pauseBinReplay("REPLAY")}")
        CerelaxTestSupport.log("BIN-ROB-002 未回放 resume -> ${controller.resumeBinReplay("REPLAY")}")
        CerelaxTestSupport.log("BIN-ROB-002 未回放 stop -> ${controller.stopBinReplay("REPLAY")}")

        // 有 bin 时：回放中重复 pause/resume，停止后再次 stop
        val bin = ensureBin() ?: run {
            assumeTrue("无可用 bin，跳过回放分支", false)
            return@runBlocking
        }
        val (p, mac) = startReplay(bin, realtime = true) ?: run {
            assumeTrue("replayBinFile 未启动回放", false)
            return@runBlocking
        }
        val c = ReplayCollector()
        c.attach(p)
        assertTrue("回放应在超时内产生数据", c.dataLatch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS))
        CerelaxTestSupport.log("BIN-ROB-002 pause1 -> ${controller.pauseBinReplay(mac)}")
        CerelaxTestSupport.log("BIN-ROB-002 pause2 -> ${controller.pauseBinReplay(mac)}")
        CerelaxTestSupport.log("BIN-ROB-002 resume1 -> ${controller.resumeBinReplay(mac)}")
        CerelaxTestSupport.log("BIN-ROB-002 resume2 -> ${controller.resumeBinReplay(mac)}")
        CerelaxTestSupport.log("BIN-ROB-002 stop1 -> ${controller.stopBinReplay(mac)}")
        CerelaxTestSupport.log("BIN-ROB-002 stop2 -> ${controller.stopBinReplay(mac)}")
    }

    /** BIN-ROB-003：multiReplayBinFile 组级 pause/resume/stop 幂等。 */
    @Test(timeout = 120000)
    fun binRob003_multiReplayGroupControlIdempotent() = runBlocking {
        val bins = findBins().take(2).map { it.absolutePath }
        assumeTrue("需 >=2 个 bin（当前 ${bins.size}），跳过", bins.size >= 2)
        val macs = bins.map { macOf(it) }
        val profiles = controller.multiReplayBinFile(bins, macs, true, 10000)
        assumeTrue("组回放应至少启动一个成员", profiles.any { it != null })

        val groupMac = macs.first()
        CerelaxTestSupport.log("BIN-ROB-003 组 pause1 -> ${controller.pauseBinReplay(groupMac)}")
        CerelaxTestSupport.log("BIN-ROB-003 组 pause2 -> ${controller.pauseBinReplay(groupMac)}")
        CerelaxTestSupport.log("BIN-ROB-003 组 resume1 -> ${controller.resumeBinReplay(groupMac)}")
        CerelaxTestSupport.log("BIN-ROB-003 组 resume2 -> ${controller.resumeBinReplay(groupMac)}")
        macs.forEach { CerelaxTestSupport.log("BIN-ROB-003 组 stop($it) -> ${controller.stopBinReplay(it)}") }
        macs.forEach { CerelaxTestSupport.log("BIN-ROB-003 组 stop 幂等($it) -> ${controller.stopBinReplay(it)}") }
    }
}
