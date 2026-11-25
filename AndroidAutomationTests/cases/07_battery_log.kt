package com.oymotion.sensorcapiktdemo.automation

import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

private const val CHAPTER = "07_电量与日志"

/**
 * 07 电量 / 日志 / 调试（BATT-* / LOG-*），与 Instrumentation 侧
 * `Test Cases/Automation/Generalized/07_Battery_Log/CerelaxBatteryLogTest.kt` 逐条对应。
 *
 * Android 与 Python 的日志控制 API 差异（口径与 Instrumentation 一致）：
 *  - Python `ctrl.setDebugEnabled(True/False)` -> `controller.setParam("DEBUG_ENABLED", "True"/"False")`
 *  - Python `ctrl.setLogPath(True, dir)`          -> `controller.setParam("LOG_PATH", dir)`
 *  - profile 级 `sensor.setParam("DEBUG_LOG_PATH", ...)` / `getParam("DEBUG_LOG_PATH")` 同名
 *  - 正确顺序：先 `LOG_PATH`、再 `DEBUG_ENABLED=True`；两者的注册都早于 `registerBleBridge`
 *    （Demo 的 bridge 在 App 启动时注册，因此用例内只需保证 LOG_PATH 先于 DEBUG_ENABLED）
 *  - 返回语义：`setParam`/`getParam` 返回 String，以 "Error" 开头即失败/不支持
 *
 * 电量口径：`getBatteryLevel` 是 suspend 扩展（返回 0..100，无有效读数 -1，通信失败抛 IOException）；
 * `getCachedPower` 只读缓存、不触设备，未被推送过时为 -1；
 * `onPowerChanged` 由 `init(_, _, powerRefreshIntervalMs)` 的周期推送驱动。
 */

/** 与 Instrumentation 侧 isError 一致：以 "Error" 开头即失败/不支持。 */
private fun isError(s: String) = s.startsWith("Error")

private suspend fun initProfile(
    p: SensorProfile,
    refreshMs: Int = TestConfig.POWER_REFRESH_MS,
): Boolean = p.init(TestConfig.PACKAGE_SAMPLE_COUNT, TestConfig.INIT_TIMEOUT_MS, refreshMs)

/** 起流相关用例的前置：能力只读 spec，spec 未注册任何支持流时按能力缺失 SKIP。 */
private fun TestContext.requireStreamCapability() {
    if (TestConfig.targetSpec().supportedStreams.isEmpty()) {
        skip("当前设备 spec 未注册任何支持流，无法起流")
    }
}

/** 日志目录统一落在 App 可写目录（不写 /sdcard 绝对路径）。 */
private fun TestContext.newLogDir(prefix: String): File {
    val dir = File(androidContext.cacheDir, "${prefix}_${System.currentTimeMillis()}")
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

/** 以指定 powerRefreshInterval 连接并 init（连接失败/init 失败即断言失败）。 */
private suspend fun TestContext.connectWithRefresh(refreshMs: Int): SensorProfile {
    val p = prepare(requireTarget(), init = false)
    check(initProfile(p, refreshMs), "init(powerRefreshInterval=$refreshMs) 失败")
    return p
}

val batteryLogCases = listOf(
    // ---- BATT-FUNC：电量 ----------------------------------------------------------------
    auto("BATT-FUNC-001", "getBatteryLevel 返回 0~100（或 -1）", CHAPTER, Priority.P0) {
        val p = connected()
        // suspend 扩展；设备无有效读数返回 -1，通信失败抛 IOException
        val level = p.getBatteryLevel()
        log("getBatteryLevel() = $level")
        check(level == -1 || level in 0..100, "电量应在 0..100（-1 表示无有效读数）：$level")
        if (level == -1) log("返回 -1（设备无有效电量读数），记录但不判失败")
        p.disconnect()
        "battery=$level"
    },
    auto("BATT-FUNC-002", "onPowerChanged 上报 0~100、不报 -1、无 1% 抖动", CHAPTER,
        Priority.P1, 660_000) {
        requireStreamCapability()
        val p = connected()

        val records = ConcurrentLinkedQueue<Pair<Long, Int>>()
        val initial = AtomicInteger(-2) // -2 = 未初始化哨兵
        val changed = AtomicReference<Int?>(null)

        p.setOnPowerListener { _, power ->
            records.offer(System.currentTimeMillis() to power)
            if (initial.get() == -2) initial.set(power)
            if (power != initial.get()) changed.compareAndSet(null, power)
            log("[onPowerChanged] level=$power")
        }

        check(p.startDataNotification(), "起流失败")

        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < MAX_OBSERVE_MS && changed.get() == null) {
            delay(1_000)
        }
        val elapsed = System.currentTimeMillis() - start
        if (changed.get() != null) {
            log("电量变化：${initial.get()} -> ${changed.get()}（${elapsed}ms）")
        } else {
            log("${MAX_OBSERVE_MS}ms 内电量未变化（初始=${initial.get()}，设备可能满电/充电中）")
        }

        p.stopDataNotification()
        val snapshot = records.toList()
        log("共收到 ${snapshot.size} 次 onPowerChanged 回调")

        check(snapshot.isNotEmpty(), "应收到至少一次 onPowerChanged 回调")
        val levels = snapshot.map { it.second }
        check(levels.all { it in 0..100 }, "所有上报值应在 0..100")
        check(levels.none { it == -1 }, "所有上报值不应为 -1")
        if (snapshot.size >= 2) {
            val hasOneStep = snapshot.zipWithNext().any { (a, b) -> abs(a.second - b.second) == 1 }
            check(!hasOneStep, "不应出现相邻回调值相差恰好 1（1% 抖动被 ±2% 滞回过滤）")
        }
        p.disconnect()
        "callbacks=${snapshot.size} initial=${initial.get()} changed=${changed.get()}"
    },
    auto("BATT-FUNC-003", "powerRefreshInterval 决定上报周期（1000/3000ms）", CHAPTER,
        Priority.P1, 240_000) {
        for (refresh in BATT003_INTERVALS) {
            log("=== powerRefreshInterval=$refresh ms ===")
            val p = connectWithRefresh(refresh)

            val records = ConcurrentLinkedQueue<Pair<Long, Int>>()
            p.setOnPowerListener { _, power -> records.offer(System.currentTimeMillis() to power) }
            delay(BATT003_COLLECT_SECONDS * 1000L)
            p.disconnect()

            val snapshot = records.toList()
            log("收到 ${snapshot.size} 次回调")
            check(snapshot.size >= 2, "回调次数不足（${snapshot.size}），无法计算周期")

            val intervals = snapshot.map { it.first }.zipWithNext().map { (a, b) -> b - a }
            val avg = intervals.average()
            val lower = refresh * (1 - INTERVAL_TOLERANCE)
            val upper = refresh * (1 + INTERVAL_TOLERANCE)
            log("平均间隔=${avg}ms（期望 ${refresh}ms，范围 ${lower}..${upper}ms）详细=$intervals")
            check(
                avg in lower..upper,
                "powerRefreshInterval=$refresh 平均间隔 ${avg}ms 应在 ${lower}..${upper}ms",
            )
        }
        "intervals=$BATT003_INTERVALS"
    },
    auto("BATT-FUNC-004", "起流期间 onPowerChanged 持续回调", CHAPTER, Priority.P1, 210_000) {
        requireStreamCapability()
        val refresh = TestConfig.POWER_REFRESH_MS
        val maxInterval = 5L * refresh

        val p = connected()
        val records = ConcurrentLinkedQueue<Pair<Long, Int>>()
        p.setOnPowerListener { _, power -> records.offer(System.currentTimeMillis() to power) }

        check(p.startDataNotification(), "起流失败")
        log("起流期间观察 ${BATT004_COLLECT_SECONDS}s ...")
        delay(BATT004_COLLECT_SECONDS * 1000L)
        val collectEnd = System.currentTimeMillis()
        p.stopDataNotification()

        val snapshot = records.toList()
        log("共收到 ${snapshot.size} 次回调")
        check(snapshot.size >= 2, "回调次数不足（${snapshot.size}），无法计算间隔")

        val timestamps = snapshot.map { it.first }
        val intervals = timestamps.zipWithNext().map { (a, b) -> b - a }
        val maxObserved = intervals.maxOrNull() ?: 0L
        val gapToEnd = collectEnd - timestamps.last()
        log("相邻间隔最大=${maxObserved}ms（允许 ≤ $maxInterval ms），末尾间隔=${gapToEnd}ms")

        check(maxObserved <= maxInterval, "任意相邻回调间隔应 ≤ ${maxInterval}ms，实际最大=$maxObserved")
        check(gapToEnd <= maxInterval, "窗口结束前应无静默（末尾间隔 ≤ ${maxInterval}ms），实际=$gapToEnd")
        p.disconnect()
        "callbacks=${snapshot.size} maxGap=$maxObserved"
    },

    // ---- LOG-FUNC：日志 / 调试 ----------------------------------------------------------
    auto("LOG-FUNC-001", "DEBUG_ENABLED 开关 controller log", CHAPTER, Priority.P1) {
        val dir = newLogDir("sdklog_ctrl")
        val filesBefore = listLogFiles(dir)

        try {
            val retPath = controller.setParam("LOG_PATH", dir.absolutePath)
            log("LOG_PATH -> $retPath")
            check(!isError(retPath), "LOG_PATH 应成功，实际=$retPath")

            controller.setParam("DEBUG_ENABLED", "True")
            delay(2_000)
            val filesAfter = listLogFiles(dir)
            val newFiles = filesAfter.keys - filesBefore.keys
            log("开启后日志文件: ${filesAfter.keys}")
            check(
                newFiles.isNotEmpty() || (filesBefore.isEmpty() && filesAfter.isNotEmpty()),
                "DEBUG_ENABLED=True 应创建 .log/.txt 文件",
            )
            val sizeAfterEnable = totalSize(filesAfter)

            // 关闭后「刹车 + 观察」分离：先等落盘尾流结束取基线，再看观察期是否还有增量。
            controller.setParam("DEBUG_ENABLED", "False")
            delay(LOG_BRAKE_MS)
            val baseline = totalSize(listLogFiles(dir))
            delay(LOG_OBSERVE_MS)
            val observed = totalSize(listLogFiles(dir))
            val growth = observed - baseline
            log("关闭前=$sizeAfterEnable bytes 基线=$baseline bytes 观察期增量=$growth")
            check(growth <= 0, "DEBUG_ENABLED=False 后日志不应继续增长（观察期增量=$growth）")
            "size=$sizeAfterEnable growth=$growth"
        } finally {
            controller.setParam("DEBUG_ENABLED", "False")
        }
    },
    auto("LOG-FUNC-002", "LOG_PATH 自动创建目录 / 已存在文件追加写入", CHAPTER, Priority.P1) {
        val parent = androidContext.cacheDir
        val newDir = File(parent, "sdklog_created_${System.currentTimeMillis()}")
        check(!newDir.exists(), "测试前目录不应存在")

        val r1 = controller.setParam("LOG_PATH", newDir.absolutePath)
        log("LOG_PATH(新目录) -> $r1")
        delay(300)
        check(newDir.isDirectory, "LOG_PATH 应自动创建目录")

        val existingFile = File(parent, "existing_${System.currentTimeMillis()}.txt")
        existingFile.writeText("x")
        // SDK 约定（已确认）：LOG_PATH 指向已存在文件时按追加写日志，"追加是期望的逻辑"。
        // 不要求拒绝，只要求原内容作为前缀保留、不被截断/清空。
        val r2 = runCatching { controller.setParam("LOG_PATH", existingFile.absolutePath) }
            .getOrElse { "Error: ${it.javaClass.simpleName}" }
        log("LOG_PATH(已存在文件) -> $r2")
        val content = existingFile.takeIf { it.isFile }?.readText() ?: ""
        log("已存在文件内容 -> $content")
        check(content.startsWith("x"), "LOG_PATH 指向文件时应追加日志且不截断原内容")
        controller.setParam("LOG_PATH", newDir.absolutePath) // 恢复正常日志目录，避免影响后续用例
        existingFile.delete()
        "dir=$r1 file=$r2"
    },
    auto("LOG-FUNC-003", "DEBUG_LOG_PATH=True 生成 profile log", CHAPTER, Priority.P1, 120_000) {
        val dir = newLogDir("sdklog_profile")
        var profile: SensorProfile? = null
        try {
            controller.setParam("LOG_PATH", dir.absolutePath)
            controller.setParam("DEBUG_ENABLED", "True")

            val p = connected()
            profile = p
            val ret = p.setParam("DEBUG_LOG_PATH", "True")
            log("setParam(DEBUG_LOG_PATH, True) -> $ret")
            check(!isError(ret), "DEBUG_LOG_PATH 应成功，实际=$ret")

            check(p.startDataNotification(), "起流失败")
            delay(LOG_COLLECT_SECONDS * 1000L)
            p.stopDataNotification()

            val path = p.getParam("DEBUG_LOG_PATH")
            log("getParam(DEBUG_LOG_PATH) = $path")
            val profileFile = path.takeIf { it.isNotBlank() && !isError(it) }?.let { File(it) }
            check(profileFile != null, "getParam 应返回有效路径，实际=$path")
            check(profileFile!!.isFile, "profile log 文件应存在：$path")
            "profileLog=$path"
        } finally {
            runCatching { profile?.setParam("DEBUG_LOG_PATH", "False") }
            runCatching { profile?.disconnect() }
            controller.setParam("DEBUG_ENABLED", "False")
        }
    },
    auto("LOG-FUNC-004", "早期 scan/connect 日志不丢失", CHAPTER, Priority.P2, 120_000) {
        val dir = newLogDir("sdklog_early")
        // 顺序按 README：先 LOG_PATH、再 DEBUG_ENABLED，之后才 scan/connect
        controller.setParam("LOG_PATH", dir.absolutePath)
        controller.setParam("DEBUG_ENABLED", "True")
        delay(300)
        val p = connected()
        val device = p.device
        try {
            delay(2_000)

            val keywords = listOf(
                "scan",
                "connect",
                device.name.orEmpty(),
                device.mac.replace(":", "").takeLast(4).uppercase(),
            ).filter { it.isNotBlank() }

            val matches = keywords.flatMap { searchInLogs(dir, it) }
            log("搜索关键字 $keywords 匹配到 ${matches.size} 行")
            check(matches.isNotEmpty(), "早期 scan/connect 日志不应丢失")
            "matches=${matches.size}"
        } finally {
            runCatching { p.disconnect() }
            controller.setParam("DEBUG_ENABLED", "False")
        }
    },
    auto("LOG-FUNC-005", "log(msg, level) 写入应用日志", CHAPTER, Priority.P1, 120_000) {
        val dir = newLogDir("sdklog_app")
        var profile: SensorProfile? = null
        try {
            controller.setParam("LOG_PATH", dir.absolutePath)
            controller.setParam("DEBUG_ENABLED", "True")

            // controller 级日志
            val ctrlMsg = "test_controller_msg_${System.currentTimeMillis()}"
            controller.log(ctrlMsg, "I")
            delay(1_000)
            check(searchInLogs(dir, ctrlMsg).isNotEmpty(), "controller.log 消息应写入日志")

            // profile 级日志
            val p = connected()
            profile = p
            val profileMsg = "test_profile_msg_${System.currentTimeMillis()}"
            p.log(profileMsg, "W")
            delay(1_000)
            check(
                searchInLogs(dir, profileMsg).isNotEmpty(),
                "profile.log 消息应写入日志（profile log 或回退 controller log）",
            )

            // 回退：profile log（DEBUG_LOG_PATH）未启用时，profile.log 应回退到 controller log
            val fallbackMsg = "test_fallback_msg_${System.currentTimeMillis()}"
            p.log(fallbackMsg, "D")
            delay(1_000)
            check(
                searchInLogs(dir, fallbackMsg).isNotEmpty(),
                "profile 未启用时 profile.log 应回退写入 controller log",
            )
            "ctrl/profile/fallback OK"
        } finally {
            runCatching { profile?.disconnect() }
            controller.setParam("DEBUG_ENABLED", "False")
        }
    },
    auto("LOG-BND-001", "空串/非法 level 入参不崩溃", CHAPTER, Priority.P1) {
        controller.log("", "I")
        controller.log("msg", "")
        controller.log("msg", "X")
        controller.log("msg", "d") // 大小写不敏感，合法

        // 非法参数值 SDK 可能返回 Error 串，也可能直接抛 IOException（实测 LOG_PATH="" 抛异常），
        // 两种都视为被拒绝；底线是不崩溃。
        suspend fun trySet(key: String, value: String): String =
            runCatching { controller.setParam(key, value) }
                .getOrElse { "Error: ${it.javaClass.simpleName}" }

        val r1 = trySet("LOG_PATH", "")
        log("LOG_PATH='' -> $r1")
        val r2 = trySet("DEBUG_ENABLED", "")
        log("DEBUG_ENABLED='' -> $r2")
        controller.setParam("LOG_PATH", newLogDir("sdklog_bnd_restore").absolutePath)
        // 不抛异常即通过
        "LOG_PATH='$r1' DEBUG_ENABLED='$r2'"
    },
    auto("LOG-ROB-001", "重复调用幂等", CHAPTER, Priority.P1) {
        val dir = newLogDir("sdklog_rob")

        controller.setParam("DEBUG_ENABLED", "True")
        controller.setParam("DEBUG_ENABLED", "True")
        controller.setParam("DEBUG_ENABLED", "False")
        controller.setParam("DEBUG_ENABLED", "False")

        val r1 = controller.setParam("LOG_PATH", dir.absolutePath)
        val r2 = controller.setParam("LOG_PATH", dir.absolutePath)
        log("LOG_PATH -> $r1 / $r2")
        // 不崩溃不抛异常，日志状态与最后一次一致
        "LOG_PATH=$r1/$r2"
    },
    auto("BATT-FUNC-005", "getCachedPower 缓存一致性", CHAPTER, Priority.P1) {
        // 先连接但先不 init：验证 getCachedPower 只读缓存、不触设备
        val p = prepare(requireTarget(), init = false)

        val cachedBefore = p.getCachedPower()
        log("getCachedPower 初始 = $cachedBefore")
        check(cachedBefore == -1 || cachedBefore in 0..100, "初始缓存应为 -1 或 0..100：$cachedBefore")

        check(initProfile(p), "init 失败")
        val level = p.getBatteryLevel()
        val cachedAfter = p.getCachedPower()
        log("getBatteryLevel=$level，getCachedPower=$cachedAfter")
        if (level >= 0) {
            check(cachedAfter in 0..100, "有效读取后缓存应为 0..100：$cachedAfter")
        } else {
            log("设备无有效电量读数（-1），缓存一致性不判失败")
        }
        p.disconnect()
        "before=$cachedBefore level=$level after=$cachedAfter"
    },
    auto("BATT-FUNC-006", "getBatteryLevelSync 同步读取范围", CHAPTER, Priority.P1) {
        val p = connected()
        val level = p.getBatteryLevelSync()
        log("getBatteryLevelSync() = $level")
        check(level == -1 || level in 0..100, "同步电量应在 0..100（-1 表示无有效读数）：$level")
        if (level >= 0) {
            val cached = p.getCachedPower()
            check(cached in 0..100, "同步读取后缓存应为 0..100：$cached")
        }
        p.disconnect()
        "syncBattery=$level"
    },
    auto("LOG-FUNC-006", "DATA_LOG_ENABLED 开关原始数据记录", CHAPTER, Priority.P1) {
        val dir = newLogDir("sdklog_datalog")
        try {
            controller.setParam("LOG_PATH", dir.absolutePath)
            controller.setParam("DEBUG_ENABLED", "True")

            val rOn = controller.setParam("DATA_LOG_ENABLED", "True")
            log("DATA_LOG_ENABLED=True -> $rOn")
            check(!isError(rOn), "DATA_LOG_ENABLED=True 应成功，实际=$rOn")

            val rOff = controller.setParam("DATA_LOG_ENABLED", "False")
            log("DATA_LOG_ENABLED=False -> $rOff")
            check(!isError(rOff), "DATA_LOG_ENABLED=False 应成功，实际=$rOff")

            val rBad = runCatching { controller.setParam("DATA_LOG_ENABLED", "MAYBE") }
                .getOrElse { "Error: ${it.javaClass.simpleName}" }
            log("DATA_LOG_ENABLED=MAYBE -> $rBad（非法值不崩溃即可）")
            "on=$rOn off=$rOff"
        } finally {
            controller.setParam("DEBUG_ENABLED", "False")
        }
    },
)

// ---- 常量（与 Instrumentation 侧一致） --------------------------------------------------

private const val MAX_OBSERVE_MS = 600_000L // BATT-002 最多观察 10 分钟
private val BATT003_INTERVALS = listOf(1000, 3000)
private const val BATT003_COLLECT_SECONDS = 20
private const val INTERVAL_TOLERANCE = 0.5
private const val BATT004_COLLECT_SECONDS = 120
private const val LOG_COLLECT_SECONDS = 3
private const val LOG_BRAKE_MS = 2_000L // DEBUG_ENABLED=False 后的落盘尾流制动时间
private const val LOG_OBSERVE_MS = 5_000L // 制动后观察期：判断是否真的停写
