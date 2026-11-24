package com.oymotion.sensorcapiktdemo.cerelaxpro

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cerelax E2E 冒烟：
 * 一次走通「环境 → 扫描 → 连接 → Ready → init → 设备信息 → 参数 → 起流 → 电量 → bin 导出/解析/回放」主链路。
 *
 * Android 与 Python 差异：
 *   - Python `ctrl.isEnable`/`getVersion` -> Android `controller.isEnable()`/`getVersion()`；
 *     无 `getBLEBackendName()`（Android 无后端切换），不迁移。
 *   - Python `ctrl.setLogPath(True, dir)`/`setDebugEnabled(True)` -> Android
 *     `controller.setParam("LOG_PATH", dir)` + `controller.setParam("DEBUG_ENABLED", "True")`。
 *   - Python `parseBinToCsv(bin)` 单参 -> Android `parseBinToCsv(bin, csvPath)` 双参。
 *   - Python `replayBinFile(bin, sensor, realtime)` 阻塞 -> Android
 *     `replayBinFile(bin, mac, realtime, timeoutMs)` 立即返回 SensorProfile，数据经 setOnDataListener 回调。
 *   - Python `getBinFileInfo` 返回 dict -> Android 返回 [BinFileInfo]（valid/deviceName/durationSec/mac/deviceInfo）。
 */
@RunWith(AndroidJUnit4::class)
class CerelaxE2eSmokeTest {

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
        TestConfig.bind(context)
        // 对应 Python setLogPath/setDebugEnabled；bin 落盘到受控目录便于断言
        runBlocking {
            controller.setParam("LOG_PATH", logDir.absolutePath)
            controller.setParam("DEBUG_ENABLED", "True")
        }
    }

    private fun findBins(): List<File> =
        logDir.listFiles()?.filter { it.name.lowercase().endsWith(".bin") }?.sortedBy { it.lastModified() }
            ?: emptyList()

    @Test(timeout = 300000)
    fun e2eSmoke_fullChain() = runBlocking<Unit> {
        // 1) 环境：蓝牙已开启
        assertTrue("前置条件不满足：电脑蓝牙未开启（isEnable == false）", controller.isEnable())
        CerelaxTestSupport.log("sdk version = ${controller.getVersion()}")
        val binsBefore = findBins().map { it.absolutePath }.toSet()

        // 2) 扫描匹配
        val device = CerelaxTestSupport.scanTargetWithRetry(controller) ?: throw AssertionError(
            "未匹配到目标设备（TARGET_IDENTITY=${TestConfig.TARGET_IDENTITY}），请确认设备开机在范围内"
        )

        // 3) requireSensor + connect -> Ready
        val p = controller.requireSensor(device.mac) ?: throw AssertionError("requireSensor 返回 null")
        assertTrue("connect 失败", p.connect())
        assertTrue("未到达 Ready", CerelaxTestSupport.waitForReady(p))

        // 4) init + hasInited
        assertTrue(
            "init 失败",
            p.init(TestConfig.PACKAGE_SAMPLE_COUNT, TestConfig.INIT_TIMEOUT_MS, TestConfig.POWER_REFRESH_INTERVAL_MS)
        )
        assertTrue("init 后 hasInited 应为 true", p.hasInited())

        // 5) 设备信息
        val info = p.getDeviceInfo()
        assertNotNull("init 后 getDeviceInfo() 不应返回 null", info)
        CerelaxTestSupport.log(
            "deviceInfo model=${info!!.modelName} fw=${info.firmwareVersion} " +
                "eegCh=${info.eegChannelCount} eegRate=${info.eegSampleRate}"
        )

        // 6) 参数（FILTER 聚合查询）——部分固件单键可写、聚合读返回 Not supported，不可用时只记录
        val flt = try {
            p.getParam("FILTER")
        } catch (e: Exception) {
            "Error: ${e.message ?: e.javaClass.simpleName}"
        }
        CerelaxTestSupport.log("getParam('FILTER') = $flt")
        if (flt.startsWith("Error")) {
            CerelaxTestSupport.log("FILTER 聚合查询不可用，跳过断言（固件差异，非 SDK 崩溃）")
        }

        // 7) 开启 bin 导出（对应 Python setParam('DEBUG_BLE_DATA_PATH','True')）
        p.setParam("DEBUG_BLE_DATA_PATH", "True")

        // 8) 起流 + 数据回调计数 + isDataTransfering
        val samples = AtomicInteger(0)
        val dataLatch = CountDownLatch(1)
        p.setOnDataListener { _, dataList ->
            if (dataList.isNotEmpty()) {
                samples.addAndGet(dataList.sumOf { it.sampleCount })
                dataLatch.countDown()
            }
        }
        assertTrue("startDataNotification 失败", p.startDataNotification())
        assertTrue("起流后 isDataTransfering 应为 true", p.isDataTransfering())
        assertTrue(
            "${TestConfig.DATA_TIMEOUT_SEC}s 内未收到任何数据",
            dataLatch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS)
        )
        assertTrue("收到数据但样本数为 0", samples.get() > 0)

        // 继续采集一段时间，确保 bin 落盘（对应 Python COLLECT_SECONDS）
        Thread.sleep(TestConfig.COLLECT_SECONDS * 1000L)

        // 9) 电量
        val battery = p.getBatteryLevel()
        CerelaxTestSupport.log("getBatteryLevel() = $battery")

        // 10) 停流 + 断开
        p.stopDataNotification()
        p.disconnect()

        // 11) bin：导出 -> 元数据 -> 解析 -> 回放
        val deadline = System.currentTimeMillis() + 3000
        var bin: String? = null
        while (System.currentTimeMillis() < deadline) {
            bin = findBins().lastOrNull { it.absolutePath !in binsBefore }?.absolutePath
            if (bin != null) break
            Thread.sleep(200)
        }
        assertNotNull("起流后应生成 .bin 文件（需 DEBUG_BLE_DATA_PATH=True）", bin)
        assertTrue("生成的 bin 文件应存在: $bin", File(bin!!).exists())

        // 元数据
        val binInfo = controller.getBinFileInfo(bin)
        assertNotNull("getBinFileInfo 应返回元数据", binInfo)
        assertTrue("bin valid 应为 true", binInfo!!.valid)
        CerelaxTestSupport.log(
            "binInfo mac=${binInfo.mac} deviceName=${binInfo.deviceName} durationSec=${binInfo.durationSec}"
        )

        // 解析 CSV
        val csvFile = File(context.cacheDir, "e2e_smoke_${System.currentTimeMillis()}.csv")
        val csvResult = CerelaxTestSupport.parseBinToCsv(controller, bin, csvFile.absolutePath)
        assertFalse("parseBinToCsv 不应返回错误: $csvResult", csvResult.startsWith("Error"))
        assertTrue("parseBinToCsv 应生成 CSV 文件", csvFile.exists())

        // 回放（realtime=false 快速回放）
        val mac = binInfo.mac.takeIf { it.isNotEmpty() } ?: "REPLAY"
        val rp = controller.replayBinFile(bin, mac, false, 10000)
        assertNotNull("replayBinFile 应返回 SensorProfile", rp)
        val replaySamples = AtomicInteger(0)
        val replayLatch = CountDownLatch(1)
        rp!!.setOnDataListener { _, dataList ->
            if (dataList.isNotEmpty()) {
                replaySamples.addAndGet(dataList.sumOf { it.sampleCount })
                replayLatch.countDown()
            }
        }
        assertTrue("replayBinFile 回放应产生数据", replayLatch.await(10, TimeUnit.SECONDS))
        assertTrue("回放样本数应 > 0", replaySamples.get() > 0)

        // 清理
        try { p.setParam("DEBUG_BLE_DATA_PATH", "False") } catch (_: Exception) {}
        controller.setParam("DEBUG_ENABLED", "False")
        CerelaxTestSupport.log("E2E smoke 主链路 PASS；bin 落盘目录: ${logDir.absolutePath}")
    }
}
