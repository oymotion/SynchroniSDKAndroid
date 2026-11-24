package com.oymotion.sensorcapiktdemo.automation

import com.oymotion.sensorcapiktdemo.TestConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 自动化用例执行器。
 * 额外保留 Android 侧的单条超时、取消传播、逐用例设备清理。
 */
private const val DIAG_LOG_TAIL = 40

class AutomationRunner(private val contextFactory: ((String) -> Unit) -> TestContext) {

    /** 执行单个 auto 用例；semi-auto/manual 返回 PENDING 占位（由 UI 走步骤引导）。 */
    suspend fun runCase(testCase: TestCase, log: (String) -> Unit): TestResult {
        if (testCase.mode != RunMode.AUTO) {
            return TestResult(
                testCase.id, testCase.title, testCase.chapter, testCase.priority, testCase.mode,
                Verdict.PENDING, testCase.steps.joinToString("；"),
            )
        }
        return execute(testCase, log)
    }

    /** 半自动用例的「我已执行，验证」：运行 run() 产出 PASS/FAIL/SKIP。 */
    suspend fun runVerification(testCase: TestCase, log: (String) -> Unit): TestResult =
        execute(testCase, log)

    /** 按顺序执行全部 auto 用例。 */
    suspend fun runAutoCases(
        cases: List<TestCase>,
        log: (String) -> Unit,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<TestResult> {
        val autos = cases.filter { it.mode == RunMode.AUTO }
        val results = ArrayList<TestResult>(autos.size)
        for ((index, testCase) in autos.withIndex()) {
            onProgress(index + 1, autos.size)
            results += runCase(testCase, log)
        }
        return results
    }

    private suspend fun execute(testCase: TestCase, log: (String) -> Unit): TestResult {
        val started = System.currentTimeMillis()
        specIssue()?.let { issue ->
            return TestResult(
                testCase.id, testCase.title, testCase.chapter, testCase.priority, testCase.mode,
                Verdict.SKIP, issue,
            )
        }
        unmetRequirements(testCase)?.let { unmet ->
            return TestResult(
                testCase.id, testCase.title, testCase.chapter, testCase.priority, testCase.mode,
                Verdict.SKIP, "当前设备 spec 不支持: $unmet",
            )
        }
        val body = testCase.run ?: return TestResult(
            testCase.id, testCase.title, testCase.chapter, testCase.priority, testCase.mode,
            Verdict.SKIP, "无自动执行实现",
        )

        // 收集本用例日志尾部，失败时直接附进报告，避免只有一行 AssertionError。
        val logTail = ArrayDeque<String>()
        val caseLog: (String) -> Unit = { line ->
            synchronized(logTail) {
                logTail.addLast(line)
                if (logTail.size > DIAG_LOG_TAIL) logTail.removeFirst()
            }
            log(line)
        }
        val context = contextFactory(caseLog)
        context.bindCase(testCase.id)

        var cleaned = false
        suspend fun cleanupOnce() {
            if (cleaned) return
            cleaned = true
            withContext(NonCancellable + Dispatchers.IO) { runCatching { context.cleanup() } }
        }

        try {
            val detail = withContext(Dispatchers.IO) {
                withTimeout(testCase.timeoutMs) { body.invoke(context) }
            }
            return TestResult(
                testCase.id, testCase.title, testCase.chapter, testCase.priority, testCase.mode,
                Verdict.PASS, detail.ifBlank { "OK" }, System.currentTimeMillis() - started,
            )
        } catch (e: TimeoutCancellationException) {
            val snapshot = snapshotSafely(context)
            cleanupOnce()
            return failResult(testCase, started, "执行超时（${testCase.timeoutMs}ms）", snapshot, context, logTail)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SkipException) {
            return TestResult(
                testCase.id, testCase.title, testCase.chapter, testCase.priority, testCase.mode,
                Verdict.SKIP, e.message.orEmpty(), System.currentTimeMillis() - started,
            )
        } catch (e: Throwable) {
            val snapshot = snapshotSafely(context)
            cleanupOnce()
            return failResult(
                testCase, started, "${e.javaClass.simpleName}: ${e.message.orEmpty()}",
                snapshot, context, logTail,
            )
        } finally {
            cleanupOnce()
        }
    }

    /** 失败现场快照：最多等 3s，避免设备无响应时把整条 run 拖死。 */
    private suspend fun snapshotSafely(context: TestContext): String =
        withContext(NonCancellable) {
            withTimeoutOrNull(3_000) {
                runCatching { context.diagnosticSnapshot() }.getOrDefault("")
            } ?: "(设备状态快照超时 3s，可能已断链/无响应)"
        }

    /**
     * 组装失败详情：错误头 + 失败瞬间设备状态 + 本用例日志/bin 产物 + 用例日志尾部。
     * 现场快照须在 cleanup 之前采集，产物列表须在 cleanup（FLUSH_BLE_DATA）之后采集。
     */
    private fun failResult(
        testCase: TestCase,
        started: Long,
        head: String,
        snapshot: String,
        context: TestContext,
        logTail: ArrayDeque<String>,
    ): TestResult {
        val artifacts = context.diagnosticArtifacts()
        val tail = synchronized(logTail) { logTail.toList() }
        val detail = buildString {
            append(head)
            if (snapshot.isNotBlank()) append('\n').append(snapshot.trimEnd())
            append('\n').append(artifacts.trimEnd())
            if (tail.isNotEmpty()) {
                append("\n用例日志(最后${tail.size}行):")
                tail.forEach { append("\n  ").append(it.take(300)) }
            }
        }
        return TestResult(
            testCase.id, testCase.title, testCase.chapter, testCase.priority, testCase.mode,
            Verdict.FAIL, detail, System.currentTimeMillis() - started,
        )
    }

    /**
     * 目标型号缺少 spec 时返回提示；spec 可用返回 null。
     *
     * 口径：新设备必须先跑 `gradlew probeDevice -PprobeIdentity=<后四位>` 生成
     * `device_specs/<model>.json`，再跑通用用例；没有 spec 时整批 SKIP，
     * 避免把「能力未知」误判成失败。
     */
    private fun specIssue(): String? {
        val model = runCatching { TestConfig.targetModel() }.getOrNull()
            ?: return "DEVICES 为空，无法确定目标型号；请先在 automation/config.json 登记设备"
        val spec = runCatching { TestConfig.targetSpec() }.getOrNull()
        return if (spec == null) {
            "目标型号 $model 缺少设备规格，请先执行 " +
                "gradlew probeDevice -PprobeIdentity=${TestConfig.TARGET_IDENTITY}"
        } else null
    }

    /** 返回当前设备 spec 不满足的能力 key（逗号分隔）；无要求或 spec 不可用时返回 null。 */
    private fun unmetRequirements(testCase: TestCase): String? {
        if (testCase.requires.isEmpty()) return null
        val spec = runCatching { TestConfig.targetSpec() }.getOrNull() ?: return null
        val unmet = testCase.requires.filter { !spec.supports(it) }
        return unmet.takeIf { it.isNotEmpty() }?.joinToString(",")
    }
}
