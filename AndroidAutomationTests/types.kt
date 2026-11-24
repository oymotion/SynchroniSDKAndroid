package com.oymotion.sensorcapiktdemo.automation

/** 自动化程度。auto=程序自动执行；semi-auto=需人工制造系统/硬件动作；manual=纯人工。 */
enum class RunMode(val label: String) {
    AUTO("自动"), SEMI_AUTO("半自动"), MANUAL("人工")
}

enum class Verdict { PENDING, PASS, FAIL, SKIP }
enum class Priority { P0, P1, P2 }

data class TestCase(
    val id: String,
    val title: String,
    val chapter: String,
    val priority: Priority = Priority.P1,
    val mode: RunMode = RunMode.AUTO,
    val steps: List<String> = emptyList(),
    val timeoutMs: Long = 90_000L,
    val run: (suspend TestContext.() -> String)? = null,
    /** 依赖的设备能力（spec stream key，如 NTF_EEG）；当前 spec 不支持时直接 SKIP。 */
    val requires: Set<String> = emptySet(),
    /** 破坏性用例（如 terminate 全局 controller）：会终止 SDK 后端，必须排在整轮执行最后。 */
    val destructive: Boolean = false,
)

data class TestResult(
    val id: String,
    val title: String,
    val chapter: String,
    val priority: Priority,
    val mode: RunMode,
    val verdict: Verdict,
    val detail: String,
    val elapsedMs: Long = 0,
)

class SkipException(message: String) : Exception(message)

fun TestContext.skip(message: String): Nothing = throw SkipException(message)

fun TestContext.check(value: Boolean, message: String) {
    if (!value) throw AssertionError(message)
}

internal fun auto(
    id: String,
    title: String,
    chapter: String,
    priority: Priority = Priority.P1,
    timeoutMs: Long = 90_000L,
    requires: Set<String> = emptySet(),
    destructive: Boolean = false,
    body: suspend TestContext.() -> String,
) = TestCase(
    id = id, title = title, chapter = chapter, priority = priority, mode = RunMode.AUTO,
    timeoutMs = timeoutMs, run = body, requires = requires, destructive = destructive,
)

internal fun semi(
    id: String,
    title: String,
    chapter: String,
    priority: Priority = Priority.P1,
    steps: List<String>,
    timeoutMs: Long = 90_000L,
    requires: Set<String> = emptySet(),
    body: (suspend TestContext.() -> String)? = null,
) = TestCase(
    id = id, title = title, chapter = chapter, priority = priority, mode = RunMode.SEMI_AUTO,
    steps = steps, timeoutMs = timeoutMs, run = body, requires = requires,
)

internal fun manual(
    id: String,
    title: String,
    chapter: String,
    priority: Priority = Priority.P1,
    steps: List<String>,
    requires: Set<String> = emptySet(),
) = TestCase(
    id = id, title = title, chapter = chapter, priority = priority, mode = RunMode.MANUAL,
    steps = steps, requires = requires,
)

internal suspend fun failureResult(block: suspend () -> String): Boolean = try {
    block().startsWith("Error")
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (_: Exception) {
    true
}
