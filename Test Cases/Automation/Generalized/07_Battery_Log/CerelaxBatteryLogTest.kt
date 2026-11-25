package com.oymotion.sensorcapiktdemo.cerelaxpro

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Cerelax 07 电量/日志/调试（BATT-* / LOG-*）。
 *
 * Android 与 Python 的日志控制 API 差异：
 *   - Python `ctrl.setDebugEnabled(True/False)` -> Android `controller.setParam("DEBUG_ENABLED", "True"/"False")`
 *   - Python `ctrl.setLogPath(True, dir)`          -> Android `controller.setParam("LOG_PATH", dir)`
 *   - Python `sensor.setParam("DEBUG_LOG_PATH", ...)` / `getParam("DEBUG_LOG_PATH")` 保持同名（profile 级）
 *
 * 返回语义（与 Python 一致）：setParam/getParam 返回 String，成功为 "OK" 或非 "Error" 开头，
 * 失败/不支持以 "Error" 开头。
 */
@RunWith(AndroidJUnit4::class)
class CerelaxBatteryLogTest {

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

    private suspend fun connectAndInit(device: BleDevice, refreshIntervalMs: Int): SensorProfile? {
        val p = CerelaxTestSupport.connectReady(controller, device) ?: return null
        if (!p.init(TestConfig.PACKAGE_SAMPLE_COUNT, TestConfig.INIT_TIMEOUT_MS, refreshIntervalMs)) {
            p.disconnect()
            return null
        }
        return p
    }

    private fun isError(s: String) = s.startsWith("Error")

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun cacheDir(): File = context().cacheDir

    private fun newLogDir(prefix: String): File {
        val dir = File(cacheDir(), "${prefix}_${System.currentTimeMillis()}")
        dir.mkdirs()
        return dir
    }

    /** 列出日志目录中所有 .log/.txt 文件。 */
    private fun listLogFiles(dir: File): Map<String, File> {
        if (!dir.isDirectory) return emptyMap()
        return dir.listFiles()
            ?.filter { it.isFile && (it.extension.equals("log", true) || it.extension.equals("txt", true)) }
            ?.associateBy { it.name }
            ?: emptyMap()
    }

    private fun totalSize(files: Map<String, File>): Long = files.values.sumOf { it.length() }

    private fun searchInLogs(dir: File, pattern: String): List<String> {
        val out = mutableListOf<String>()
        listLogFiles(dir).values.sortedBy { it.name }.forEach { f ->
            runCatching { f.readLines() }.getOrDefault(emptyList()).forEach { line ->
                if (line.contains(pattern, ignoreCase = true)) out.add(line.trim())
            }
        }
        return out
    }

    /** 注册 onPowerChanged 并收集 [millis] 毫秒，返回 (timestampMs, level) 列表。 */
    private fun collectPower(p: SensorProfile, millis: Long): List<Pair<Long, Int>> {
        val records = java.util.Collections.synchronizedList(mutableListOf<Pair<Long, Int>>())
        p.setOnPowerListener { _, power ->
            records.add(System.currentTimeMillis() to power)
        }
        Thread.sleep(millis)
        return records.toList()
    }

    // =====================================================================================
    // BATT-* 电量用例
    // =====================================================================================

    /** BATT-FUNC-001：getBatteryLevel 返回 0~100（或 -1，不判失败仅记录）。 */
    @Test
    fun battFunc001_getBatteryLevel_range() = runBlocking<Unit> {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val level = p.getBatteryLevel()
        CerelaxTestSupport.log("getBatteryLevel() = $level")
        assertTrue("电量应在 0..100（-1 表示无有效读数）：$level", level == -1 || level in 0..100)
        if (level == -1) {
            CerelaxTestSupport.log("返回 -1（设备无有效电量读数），记录但不判失败")
        }
        p.disconnect()
    }

    /** BATT-FUNC-002：onPowerChanged 上报 0~100、不报 -1、无 1% 抖动；电量变化即 PASS。 */
    @Test
    fun battFunc002_onPowerChanged_range_noMinusOne() = runBlocking<Unit> {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val records = java.util.Collections.synchronizedList(mutableListOf<Pair<Long, Int>>())
        val initial = java.util.concurrent.atomic.AtomicInteger(-2) // -2 = 未初始化哨兵
        val changed = java.util.concurrent.atomic.AtomicReference<Int?>(null)

        p.setOnPowerListener { _, power ->
            records.add(System.currentTimeMillis() to power)
            if (initial.get() == -2) initial.set(power)
            if (power != initial.get()) changed.compareAndSet(null, power)
            CerelaxTestSupport.log("[onPowerChanged] level=$power")
        }

        assertTrue("起流失败", p.startDataNotification())

        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < MAX_OBSERVE_MS && changed.get() == null) {
            Thread.sleep(1000)
        }
        val elapsed = System.currentTimeMillis() - start
        if (changed.get() != null) {
            CerelaxTestSupport.log("电量变化：${initial.get()} -> ${changed.get()}（${elapsed}ms）")
        } else {
            CerelaxTestSupport.log("${MAX_OBSERVE_MS}ms 内电量未变化（初始=${initial.get()}，设备可能满电/充电中）")
        }

        p.stopDataNotification()
        val snapshot = records.toList()
        CerelaxTestSupport.log("共收到 ${snapshot.size} 次 onPowerChanged 回调")

        assertTrue("应收到至少一次 onPowerChanged 回调", snapshot.isNotEmpty())
        val levels = snapshot.map { it.second }
        assertTrue("所有上报值应在 0..100", levels.all { it in 0..100 })
        assertTrue("所有上报值不应为 -1", levels.all { it != -1 })
        if (snapshot.size >= 2) {
            val hasOneStep = snapshot.zipWithNext().any { (a, b) -> Math.abs(a.second - b.second) == 1 }
            assertTrue("不应出现相邻回调值相差恰好 1（1% 抖动被 ±2% 滞回过滤）", !hasOneStep)
        }
        p.disconnect()
    }

    /** BATT-FUNC-003：powerRefreshInterval 决定上报周期（1000ms / 3000ms，±50% 容差）。 */
    @Test
    fun battFunc003_powerRefreshInterval_period() = runBlocking<Unit> {
        val device = requireTarget()

        for (refresh in BATT003_INTERVALS) {
            CerelaxTestSupport.log("=== powerRefreshInterval=$refresh ms ===")
            val p = connectAndInit(device, refresh)
                ?: throw AssertionError("连接或 init 失败（powerRefreshInterval=$refresh）")

            val records = collectPower(p, BATT003_COLLECT_SECONDS * 1000L)
            CerelaxTestSupport.log("收到 ${records.size} 次回调")
            p.disconnect()

            assertTrue("回调次数不足（${records.size}），无法计算周期", records.size >= 2)
            val timestamps = records.map { it.first }
            val intervals = timestamps.zipWithNext().map { (a, b) -> b - a }
            val avg = intervals.average()
            val lower = refresh * (1 - INTERVAL_TOLERANCE)
            val upper = refresh * (1 + INTERVAL_TOLERANCE)
            CerelaxTestSupport.log(
                "平均间隔=${avg}ms（期望 ${refresh}ms，范围 ${lower}..${upper}ms）详细=${intervals}"
            )
            assertTrue(
                "powerRefreshInterval=$refresh 平均间隔 ${avg}ms 应在 ${lower}..${upper}ms",
                avg in lower..upper
            )
        }
    }

    /** BATT-FUNC-004：起流期间 onPowerChanged 持续回调，相邻间隔 ≤ 5×powerRefreshInterval 且无静默。 */
    @Test
    fun battFunc004_streaming_keepsPowerCallback() = runBlocking<Unit> {
        val refresh = TestConfig.POWER_REFRESH_INTERVAL_MS
        val maxInterval = 5L * refresh

        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val records = java.util.Collections.synchronizedList(mutableListOf<Pair<Long, Int>>())
        p.setOnPowerListener { _, power ->
            records.add(System.currentTimeMillis() to power)
        }

        assertTrue("起流失败", p.startDataNotification())
        val collectStart = System.currentTimeMillis()
        CerelaxTestSupport.log("起流期间观察 ${BATT004_COLLECT_SECONDS}s ...")
        Thread.sleep(BATT004_COLLECT_SECONDS * 1000L)
        val collectEnd = System.currentTimeMillis()
        p.stopDataNotification()

        val snapshot = records.toList()
        CerelaxTestSupport.log("共收到 ${snapshot.size} 次回调")

        assertTrue("回调次数不足（${snapshot.size}），无法计算间隔", snapshot.size >= 2)
        val timestamps = snapshot.map { it.first }
        val intervals = timestamps.zipWithNext().map { (a, b) -> b - a }
        val maxObserved = intervals.maxOrNull() ?: 0L
        val gapToEnd = collectEnd - timestamps.last()
        CerelaxTestSupport.log("相邻间隔最大=${maxObserved}ms（允许 ≤ $maxInterval ms），末尾间隔=${gapToEnd}ms")

        assertTrue("任意相邻回调间隔应 ≤ ${maxInterval}ms，实际最大=$maxObserved", maxObserved <= maxInterval)
        assertTrue("窗口结束前应无静默（末尾间隔 ≤ ${maxInterval}ms），实际=$gapToEnd", gapToEnd <= maxInterval)
        p.disconnect()
    }

    // =====================================================================================
    // LOG-* 日志/调试用例
    // =====================================================================================

    /** LOG-FUNC-001：setDebugEnabled 开关 controller log（Android: DEBUG_ENABLED）。 */
    @Test
    fun logFunc001_debugEnabled_createsAndClosesLog() = runBlocking<Unit> {
        val dir = newLogDir("sdklog_ctrl")
        val filesBefore = listLogFiles(dir)

        val retPath = controller.setParam("LOG_PATH", dir.absolutePath)
        CerelaxTestSupport.log("LOG_PATH -> $retPath")
        assertTrue("LOG_PATH 应成功，实际=$retPath", !isError(retPath))

        controller.setParam("DEBUG_ENABLED", "True")
        Thread.sleep(2000)
        val filesAfter = listLogFiles(dir)
        val newFiles = filesAfter.keys - filesBefore.keys
        CerelaxTestSupport.log("开启后日志文件: ${filesAfter.keys}")
        assertTrue(
            "setDebugEnabled(True) 应创建 .log/.txt 文件",
            newFiles.isNotEmpty() || (filesBefore.isEmpty() && filesAfter.isNotEmpty())
        )

        val sizeAfterEnable = totalSize(filesAfter)

        controller.setParam("DEBUG_ENABLED", "False")
        Thread.sleep(2000)
        val filesAfterDisable = listLogFiles(dir)
        val sizeAfterDisable = totalSize(filesAfterDisable)
        val growth = sizeAfterDisable - sizeAfterEnable
        CerelaxTestSupport.log("关闭前=$sizeAfterEnable bytes 关闭后=$sizeAfterDisable bytes 增长=$growth")
        assertTrue("setDebugEnabled(False) 后日志不应继续增长", growth <= 0)

        controller.setParam("DEBUG_ENABLED", "False")
    }

    /** LOG-FUNC-002：setLogPath 自动创建目录 / 指向已存在文件按追加语义写入（Android: LOG_PATH）。 */
    @Test
    fun logFunc002_logPath_dirCreate_existingFileAppend() = runBlocking<Unit> {
        val parent = cacheDir()
        val newDir = File(parent, "sdklog_created_${System.currentTimeMillis()}")
        assertTrue("测试前目录不应存在", !newDir.exists())

        val r1 = controller.setParam("LOG_PATH", newDir.absolutePath)
        CerelaxTestSupport.log("LOG_PATH(新目录) -> $r1")
        Thread.sleep(300)
        assertTrue("setLogPath 应自动创建目录", newDir.isDirectory)

        val existingFile = File(parent, "existing_${System.currentTimeMillis()}.txt")
        existingFile.writeText("x")
        // SDK 约定（已确认）：LOG_PATH 指向已存在文件时按追加写日志，"追加是期望的逻辑"。
        // 因此不要求拒绝，只要求原内容作为前缀保留、不被截断/清空。
        val r2 = runCatching { controller.setParam("LOG_PATH", existingFile.absolutePath) }
            .getOrElse { "Error: ${it.message}" }
        CerelaxTestSupport.log("LOG_PATH(已存在文件) -> $r2")
        val content = existingFile.takeIf { it.isFile }?.readText() ?: ""
        CerelaxTestSupport.log("已存在文件内容 -> $content")
        assertTrue(
            "LOG_PATH 指向已存在文件应追加日志且不截断原内容，实际=$r2，内容=$content",
            content.startsWith("x")
        )
        controller.setParam("LOG_PATH", newDir.absolutePath) // 恢复合法目录，避免后续用例写入已删文件
        existingFile.delete()
    }

    /** LOG-FUNC-003：DEBUG_LOG_PATH=True 生成 profile log，getParam 返回有效路径且文件落盘。 */
    @Test
    fun logFunc003_debugLogPath_profileLogGenerated() = runBlocking<Unit> {
        val dir = newLogDir("sdklog_profile")
        controller.setParam("LOG_PATH", dir.absolutePath)
        controller.setParam("DEBUG_ENABLED", "True")

        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val ret = p.setParam("DEBUG_LOG_PATH", "True")
        CerelaxTestSupport.log("setParam(DEBUG_LOG_PATH, True) -> $ret")
        assertTrue("DEBUG_LOG_PATH 应成功，实际=$ret", !isError(ret))

        assertTrue("起流失败", p.startDataNotification())
        Thread.sleep(LOG_COLLECT_SECONDS * 1000L)
        p.stopDataNotification()

        val path = p.getParam("DEBUG_LOG_PATH")
        CerelaxTestSupport.log("getParam(DEBUG_LOG_PATH) = $path")
        val profileFile = path.takeIf { it.isNotBlank() && !isError(it) }?.let { File(it) }
        assertTrue("getParam 应返回有效路径，实际=$path", profileFile != null)
        assertTrue("profile log 文件应存在：$path", profileFile!!.isFile)

        p.setParam("DEBUG_LOG_PATH", "False")
        p.disconnect()
        controller.setParam("DEBUG_ENABLED", "False")
    }

    /** LOG-FUNC-004：先开启日志再 scan/connect，早期 scan/connect 日志不应丢失。 */
    @Test
    fun logFunc004_earlyScanConnect_logNotLost() = runBlocking<Unit> {
        // 关键：必须先生效日志路径与调试开关，scan/connect 日志才会落盘
        val dir = newLogDir("sdklog_early")
        controller.setParam("LOG_PATH", dir.absolutePath)
        controller.setParam("DEBUG_ENABLED", "True")

        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))
        Thread.sleep(2000)

        val keywords = listOf(
            "scan",
            "connect",
            device.name.orEmpty(),
            device.mac.replace(":", "").takeLast(4).uppercase()
        ).filter { it.isNotBlank() }

        val matches = keywords.flatMap { searchInLogs(dir, it) }
        CerelaxTestSupport.log("搜索关键字 $keywords 匹配到 ${matches.size} 行")
        assertTrue("早期 scan/connect 日志不应丢失", matches.isNotEmpty())

        p.disconnect()
        controller.setParam("DEBUG_ENABLED", "False")
    }

    /** LOG-FUNC-005：log(msg, level) 写入应用日志；profile 未启用时回退 controller log。 */
    @Test
    fun logFunc005_appLog_writeLevels() = runBlocking<Unit> {
        val dir = newLogDir("sdklog_app")
        controller.setParam("LOG_PATH", dir.absolutePath)
        controller.setParam("DEBUG_ENABLED", "True")

        // controller 级日志
        val ctrlMsg = "test_controller_msg_${System.currentTimeMillis()}"
        controller.log(ctrlMsg, "I")
        Thread.sleep(1000)
        assertTrue("controller.log 消息应写入日志", searchInLogs(dir, ctrlMsg).isNotEmpty())

        // profile 级日志
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val profileMsg = "test_profile_msg_${System.currentTimeMillis()}"
        p.log(profileMsg, "W")
        Thread.sleep(1000)
        assertTrue(
            "profile.log 消息应写入日志（profile log 或回退 controller log）",
            searchInLogs(dir, profileMsg).isNotEmpty()
        )

        // 回退：profile log（DEBUG_LOG_PATH）未启用时，sensor.log 应回退到 controller log
        val fallbackMsg = "test_fallback_msg_${System.currentTimeMillis()}"
        p.log(fallbackMsg, "D")
        Thread.sleep(1000)
        assertTrue(
            "profile 未启用时 sensor.log 应回退写入 controller log",
            searchInLogs(dir, fallbackMsg).isNotEmpty()
        )

        p.disconnect()
        controller.setParam("DEBUG_ENABLED", "False")
    }

    /** LOG-BND-001：空串 / 非法 level 入参不崩溃，被拒绝或忽略。 */
    @Test
    fun logBnd001_emptyOrInvalidInput_noCrash() = runBlocking<Unit> {
        controller.log("", "I")
        controller.log("msg", "")
        controller.log("msg", "X")
        controller.log("msg", "d") // 大小写不敏感，合法

        // 空串入参：SDK 失败时抛 IOException（不返回错误串），捕获后视为"被拒绝"，只验证不崩溃
        val r1 = runCatching { controller.setParam("LOG_PATH", "") }
            .getOrElse { "Error: ${it.message}" }
        CerelaxTestSupport.log("LOG_PATH='' -> $r1")
        val r2 = runCatching { controller.setParam("DEBUG_ENABLED", "") }
            .getOrElse { "Error: ${it.message}" }
        CerelaxTestSupport.log("DEBUG_ENABLED='' -> $r2")
        // 不抛异常即通过（被拒绝或安全忽略均可）
    }

    /** LOG-ROB-001：setDebugEnabled/setLogPath 重复调用幂等，不崩溃。 */
    @Test
    fun logRob001_repeatedCalls_idempotent() = runBlocking<Unit> {
        val dir = newLogDir("sdklog_rob")

        controller.setParam("DEBUG_ENABLED", "True")
        controller.setParam("DEBUG_ENABLED", "True")
        controller.setParam("DEBUG_ENABLED", "False")
        controller.setParam("DEBUG_ENABLED", "False")

        val r1 = controller.setParam("LOG_PATH", dir.absolutePath)
        val r2 = controller.setParam("LOG_PATH", dir.absolutePath)
        CerelaxTestSupport.log("LOG_PATH -> $r1 / $r2")
        // 不崩溃不抛异常，日志状态与最后一次一致
    }

    /** BATT-FUNC-005：getCachedPower 不触设备读缓存；初始 -1 或 0..100，主动读取成功后缓存为有效值。 */
    @Test
    fun battFunc005_getCachedPower_matchesLastReading() = runBlocking<Unit> {
        val device = requireTarget()
        val p = connectReady(device)

        val cachedBefore = p.getCachedPower()
        CerelaxTestSupport.log("getCachedPower 初始 = $cachedBefore")
        assertTrue("初始缓存应为 -1 或 0..100：$cachedBefore", cachedBefore == -1 || cachedBefore in 0..100)

        assertTrue("init 失败", init(p))
        val level = p.getBatteryLevel()
        val cachedAfter = p.getCachedPower()
        CerelaxTestSupport.log("getBatteryLevel=$level，getCachedPower=$cachedAfter")
        if (level >= 0) {
            assertTrue("有效读取后缓存应为 0..100：$cachedAfter", cachedAfter in 0..100)
        } else {
            CerelaxTestSupport.log("设备无有效电量读数（-1），缓存一致性不判失败")
        }
        p.disconnect()
    }

    /** BATT-FUNC-006：getBatteryLevelSync 同步读取返回 0~100（或 -1，不判失败仅记录），成功后缓存同步刷新。 */
    @Test
    fun battFunc006_getBatteryLevelSync_range() = runBlocking<Unit> {
        val device = requireTarget()
        val p = connectReady(device)
        assertTrue("init 失败", init(p))

        val level = p.getBatteryLevelSync()
        CerelaxTestSupport.log("getBatteryLevelSync() = $level")
        assertTrue("同步电量应在 0..100（-1 表示无有效读数）：$level", level == -1 || level in 0..100)
        if (level >= 0) {
            assertTrue("同步读取后缓存应为 0..100：${p.getCachedPower()}", p.getCachedPower() in 0..100)
        }
        p.disconnect()
    }

    /** LOG-FUNC-006：DATA_LOG_ENABLED 开关原始数据记录（controller 级），ON/OFF 返回 OK，非法值不崩溃。 */
    @Test
    fun logFunc006_dataLogEnabled_toggle() = runBlocking<Unit> {
        val dir = newLogDir("sdklog_datalog")
        controller.setParam("LOG_PATH", dir.absolutePath)
        controller.setParam("DEBUG_ENABLED", "True")

        val rOn = controller.setParam("DATA_LOG_ENABLED", "True")
        CerelaxTestSupport.log("DATA_LOG_ENABLED=True -> $rOn")
        assertTrue("DATA_LOG_ENABLED=True 应成功，实际=$rOn", !isError(rOn))
        val rOff = controller.setParam("DATA_LOG_ENABLED", "False")
        CerelaxTestSupport.log("DATA_LOG_ENABLED=False -> $rOff")
        assertTrue("DATA_LOG_ENABLED=False 应成功，实际=$rOff", !isError(rOff))

        val rBad = controller.setParam("DATA_LOG_ENABLED", "MAYBE")
        CerelaxTestSupport.log("DATA_LOG_ENABLED=MAYBE -> $rBad（非法值不崩溃即可）")
        controller.setParam("DEBUG_ENABLED", "False")
    }

    // =====================================================================================
    // 常量
    // =====================================================================================

    companion object {
        private const val MAX_OBSERVE_MS = 600_000L   // BATT-002 最多观察 10 分钟
        private val BATT003_INTERVALS = listOf(1000, 3000)
        private const val BATT003_COLLECT_SECONDS = 20
        private const val INTERVAL_TOLERANCE = 0.5
        private const val BATT004_COLLECT_SECONDS = 120
        private const val LOG_COLLECT_SECONDS = 3
    }
}
