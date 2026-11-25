package com.oymotion.sensorcapiktdemo.automation

import android.content.Context
import android.os.Environment
import com.oymotion.sensorcapiktdemo.TestConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 生成 JSON + TXT 报告，并尽力复制到公共 Downloads。
 * 主报告写入应用私有外部目录并带时间戳，保留历史版本。
 */
object AutomationReport {
    data class Output(
        val jsonPath: String,
        val txtPath: String,
        val summary: String,
        /** 尽力复制到公共下载目录后的路径；无权限或失败时为空。 */
        val sharedPaths: List<String> = emptyList(),
    )

    /**
     * @param cases 全量用例；提供时按「自动 + 半自动」补全未执行项为 PENDING（纯人工不入报告），
     *              不提供时只导出已执行结果。
     */
    fun write(
        context: Context,
        results: Collection<TestResult>,
        cases: List<TestCase> = emptyList(),
    ): Output {
        val sorted = reportRows(results, cases).sortedBy { it.id }
        val summary = Verdict.values().joinToString(" ") { v ->
            "${v.name.lowercase()}=${sorted.count { it.verdict == v }}"
        }
        val (model, identity, macs) = deviceInfo()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(context.getExternalFilesDir("automation") ?: context.filesDir, "").apply { mkdirs() }
        // 文件名带型号，三设备矩阵跑批时结果不会混淆。
        val safeModel = model.ifBlank { "unknown" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val base = File(dir, "report_${safeModel}_$stamp")
        val jsonFile = File("${base.absolutePath}.json")
        val txtFile = File("${base.absolutePath}.txt")
        val array = JSONArray()
        val text = StringBuilder("device=$model ($identity) mac=$macs\n")
            .append("total=${sorted.size} $summary\n\n")
        for (result in sorted) {
            array.put(JSONObject().apply {
                put("id", result.id)
                put("title", result.title)
                put("chapter", result.chapter)
                put("priority", result.priority.name)
                put("mode", result.mode.name)
                put("verdict", result.verdict.name)
                put("detail", result.detail)
                put("elapsedMs", result.elapsedMs)
            })
            text.append("[${result.verdict}] ${result.id} (${result.priority}/${result.mode.label}) ${result.title}\n")
                .append("    ${result.detail} (${result.elapsedMs}ms)\n\n")
        }
        jsonFile.writeText(JSONObject().apply {
            put("generatedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date()))
            put("deviceModel", model)
            put("deviceIdentity", identity)
            put("deviceMac", macs)
            put("summary", "total=${sorted.size} $summary")
            put("results", array)
        }.toString(2))
        txtFile.writeText(text.toString())
        // 尽力复制到公共下载目录（固定文件名覆盖上一份）；
        // 失败（如未授予“所有文件访问权限”）不影响主报告。
        val sharedPaths = mutableListOf<String>()
        listOf(jsonFile to "sensor_automation_report.json", txtFile to "sensor_automation_report.txt")
            .forEach { (source, name) ->
                runCatching {
                    val downloads = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS,
                    )
                    if (!downloads.exists()) downloads.mkdirs()
                    val target = File(downloads, name)
                    source.copyTo(target, overwrite = true)
                    sharedPaths.add(target.absolutePath)
                }
            }
        return Output(
            jsonFile.absolutePath,
            txtFile.absolutePath,
            "total=${sorted.size} $summary",
            sharedPaths,
        )
    }

    /**
     * 报告行：全量用例中自动 + 半自动每条一行（未执行记 PENDING），纯人工剔除；
     * 不在用例目录里的已执行结果（旧版本/临时用例）仍保留。
     */
    private fun reportRows(results: Collection<TestResult>, cases: List<TestCase>): List<TestResult> {
        if (cases.isEmpty()) return results.toList()
        val executed = results.associateBy { it.id }
        val spec = runCatching { TestConfig.targetSpec() }.getOrNull()
        // 能力不适用（spec 不支持）的用例不进报告；自动/半自动/人工都保留，未执行记 PENDING 待人工确认。
        val applicable = cases.filter { case ->
            spec == null || case.requires.all { spec.supports(it) }
        }
        val catalogIds = applicable.mapTo(HashSet()) { it.id }
        val rows = applicable.map { case ->
            executed[case.id] ?: TestResult(
                case.id, case.title, case.chapter, case.priority, case.mode, Verdict.PENDING,
                case.steps.joinToString("；"),
            )
        }.toMutableList()
        rows += results.filter {
            it.id !in catalogIds && !it.detail.startsWith("当前设备 spec 不支持")
        }
        return rows
    }

    /** 当前目标设备信息（型号 / identity / MAC）；配置不可用时返回空串，不影响报告生成。 */
    private fun deviceInfo(): Triple<String, String, String> {
        val model = runCatching { TestConfig.targetModel() }.getOrNull().orEmpty()
        val identity = runCatching { TestConfig.TARGET_IDENTITY }.getOrNull().orEmpty()
        val macs = runCatching {
            TestConfig.targetIdentities.mapNotNull { id ->
                TestConfig.DEVICES.firstOrNull { it.identity.equals(id, ignoreCase = true) }?.mac
            }.joinToString(",")
        }.getOrNull().orEmpty()
        return Triple(model, identity, macs)
    }
}
