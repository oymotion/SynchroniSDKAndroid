package com.oymotion.sensorcapiktdemo

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.automation.*
import com.oymotion.sensorcapiktdemo.devicespecs.DeviceSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 自动化测试页。 */
class AutomationScreen(
    private val activity: Activity,
    private val controllerProvider: () -> SensorController,
) : LinearLayout(activity) {

    /** 对齐 RN：每次现取实例，terminate 后由 ControllerProvider 重建并补注册 BLE bridge。 */
    private val controller: SensorController get() = controllerProvider()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 宿主信号：08 章半自动用例据此判断旋转/分屏/切后台是否真的发生。 */
    private var backgroundStops = 0
    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStopped(stopped: Activity) {
            if (stopped === activity) backgroundStops++
        }
        override fun onActivityCreated(created: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(started: Activity) = Unit
        override fun onActivityResumed(resumed: Activity) = Unit
        override fun onActivityPaused(paused: Activity) = Unit
        override fun onActivitySaveInstanceState(saved: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(destroyed: Activity) = Unit
    }
    private val hostSignals = object : HostSignals {
        override fun orientation(): Int = activity.resources.configuration.orientation
        override fun inMultiWindow(): Boolean = activity.isInMultiWindowMode
        override fun backgroundStops(): Int = this@AutomationScreen.backgroundStops
    }

    private val runner = AutomationRunner { logger ->
        TestContext(
            androidContext = activity.applicationContext,
            log = logger,
            prompt = { message -> promptManual(message) },
            hostSignals = hostSignals,
        )
    }
    private val results = LinkedHashMap<String, TestResult>()
    private val logs = ArrayList<String>()
    private val collapsed = LinkedHashSet<String>()
    private var runJob: Job? = null
    private var busy = false

    /** 当前执行中的用例 ID：仅 UI 瞬时态，不写入 results、不进报告。 */
    private var runningCaseId: String? = null

    /** 当前已连接设备的 spec；未连接时为 null（列表显示全量，不标注不适用）。 */
    private var activeSpec: DeviceSpec? = null

    private val adapter = CaseAdapter()
    private lateinit var runAllButton: Button
    private lateinit var runFilteredButton: Button
    private lateinit var runChapterButton: Button
    private lateinit var selectDeviceButton: Button
    private lateinit var stopButton: Button
    private lateinit var exportButton: Button
    private lateinit var summaryText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var chapterSpinner: Spinner
    private lateinit var prioritySpinner: Spinner
    private lateinit var filterEdit: EditText
    private lateinit var caseList: ListView

    private val chapters = listOf("全部章节") + allCases.map { it.chapter }.distinct()
    private val priorities = listOf("全部级别", "P0", "P1", "P2")

    init {
        TestConfig.bind(activity.applicationContext)
        activity.application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        orientation = VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        buildToolbar()
        buildFilters()
        summaryText = TextView(activity).apply {
            textSize = 12f
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        addView(summaryText)
        caseList = ListView(activity).apply {
            adapter = this@AutomationScreen.adapter
            setOnItemClickListener { _, _, position, _ ->
                val item = this@AutomationScreen.adapter.rows.getOrNull(position)
                when (item) {
                    is Row.Header -> toggleChapter(item.title)
                    is Row.CaseRow -> if (!busy) openCase(item.testCase)
                    null -> Unit
                }
            }
        }
        addView(caseList, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        buildLogPanel()
        refresh()
        updateButtons()
    }

    private fun buildToolbar() {
        runAllButton = Button(activity).apply {
            text = "Run All Auto"
            setOnClickListener { runAuto(allCases) }
        }
        runFilteredButton = Button(activity).apply {
            text = "运行筛选"
            setOnClickListener { runAuto(filteredCases()) }
        }
        runChapterButton = Button(activity).apply {
            text = "运行本章"
            setOnClickListener {
                val selected = chapterSpinner.selectedItem as? String
                runAuto(allCases.filter { selected == null || selected == "全部章节" || it.chapter == selected })
            }
        }
        selectDeviceButton = Button(activity).apply {
            text = "选择设备"
            setOnClickListener { showDevicePicker() }
        }
        stopButton = Button(activity).apply {
            text = "停止"
            setOnClickListener {
                appendLog("=== 请求停止，当前原生调用返回后终止 ===")
                runJob?.cancel()
            }
        }
        exportButton = Button(activity).apply {
            text = "导出报告"
            setOnClickListener { exportReport() }
        }
        val firstRow = LinearLayout(activity).apply { orientation = HORIZONTAL }
        for (button in listOf(runAllButton, runFilteredButton, runChapterButton)) {
            firstRow.addView(button, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(2); marginEnd = dp(2)
            })
        }
        addView(firstRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val secondRow = LinearLayout(activity).apply { orientation = HORIZONTAL }
        for (button in listOf(selectDeviceButton, stopButton, exportButton)) {
            secondRow.addView(button, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(2); marginEnd = dp(2)
            })
        }
        addView(secondRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun buildFilters() {
        val row = LinearLayout(activity).apply { orientation = HORIZONTAL }
        chapterSpinner = Spinner(activity).apply {
            adapter = spinnerAdapter(chapters)
            onItemSelectedListener = simpleSelection { refresh() }
        }
        prioritySpinner = Spinner(activity).apply {
            adapter = spinnerAdapter(priorities)
            onItemSelectedListener = simpleSelection { refresh() }
        }
        filterEdit = EditText(activity).apply {
            hint = "用例 ID / 标题"
            textSize = 12f
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = refresh()
            })
        }
        row.addView(chapterSpinner, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.2f))
        row.addView(prioritySpinner, LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.8f))
        row.addView(filterEdit, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(row)
    }

    private fun buildLogPanel() {
        logScroll = ScrollView(activity).apply {
            logText = TextView(activity).apply {
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(Color.DKGRAY)
            }
            addView(logText)
        }
        addView(logScroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(150)))
    }

    private fun spinnerAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(activity, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun simpleSelection(onChange: () -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = onChange()
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    private fun filteredCases(): List<TestCase> {
        val chapter = chapterSpinner.selectedItem as? String ?: "全部章节"
        val priority = prioritySpinner.selectedItem as? String ?: "全部级别"
        val query = filterEdit.text.toString().trim().uppercase()
        return allCases.filter {
            (chapter == "全部章节" || it.chapter == chapter) &&
                (priority == "全部级别" || it.priority.name == priority) &&
                (query.isEmpty() || it.id.uppercase().contains(query) || it.title.uppercase().contains(query))
        }
    }

    private fun toggleChapter(chapter: String) {
        if (!collapsed.remove(chapter)) collapsed.add(chapter)
        refresh()
    }

    private fun refresh() {
        refreshActiveSpec()
        val rows = ArrayList<Row>()
        var chapter = ""
        for (testCase in filteredCases()) {
            if (testCase.chapter != chapter) {
                chapter = testCase.chapter
                rows.add(Row.Header(chapter))
            }
            if (chapter !in collapsed) rows.add(Row.CaseRow(testCase))
        }
        adapter.rows = rows
        adapter.notifyDataSetChanged()
        updateSummary()
    }

    /** 刷新当前设备的 spec：优先已连接设备，未连接时回退本轮目标设备。 */
    private fun refreshActiveSpec() {
        activeSpec = runCatching {
            val connected = controller.connectedSensors.firstOrNull()
            if (connected != null) {
                // 优先按 MAC 反查已登记型号（gForceMax 固件会把 modelName 上报成 gForceUltra，
                // OB6000C 上报 OB6000，直接用 modelName 会加载不到对应 spec）。
                val model = TestConfig.deviceByMac(connected.device.mac)?.model
                    ?: connected.deviceInfo?.modelName
                if (!model.isNullOrBlank()) return@runCatching TestConfig.loadSpec(model)
            }
            // 未连接时按本轮目标设备加载，切换设备后立即标注「不适用」用例
            TestConfig.targetSpec()
        }.getOrNull()
    }

    /** 该用例在当前已连接设备上缺失的能力 key；未连接或全部满足时为空。 */
    private fun unmetRequires(testCase: TestCase): List<String> {
        val spec = activeSpec ?: return emptyList()
        return testCase.requires.filter { !spec.supports(it) }
    }

    private fun updateSummary() {
        if (!::summaryText.isInitialized) return
        val values = results.values
        val specLabel = activeSpec?.let { "spec=${it.model}" } ?: "未连接(全量)"
        val notApplicable = allCases.count { unmetRequires(it).isNotEmpty() }
        val running = if (runningCaseId != null) " | 执行中" else ""
        summaryText.text = "目标=${TestConfig.TARGET_IDENTITY} | $specLabel | 不适用 $notApplicable 条 | " +
            "筛选 ${filteredCases().size}/${allCases.size} 条$running | " +
            Verdict.values().joinToString(" ") { "${it.name}=${values.count { r -> r.verdict == it }}" }
    }

    /** 标记当前执行中的用例（null=空闲）并刷新列表；不改动列表滚动位置。 */
    private fun setRunning(caseId: String?) {
        runningCaseId = caseId
        adapter.notifyDataSetChanged()
        updateSummary()
    }

    private fun updateButtons() {
        runAllButton.isEnabled = !busy
        runFilteredButton.isEnabled = !busy
        runChapterButton.isEnabled = !busy
        selectDeviceButton.isEnabled = !busy
        exportButton.isEnabled = results.isNotEmpty()
        stopButton.isEnabled = busy
    }

    /** 选择本轮目标设备（可多选，多选用于 MULTI-* 多设备用例）。 */
    private fun showDevicePicker() {
        if (busy) return
        val devices = TestConfig.DEVICES
        if (devices.isEmpty()) {
            appendLog("DEVICES 为空，请先在 config.json 登记设备")
            return
        }
        val current = TestConfig.targetIdentities.map { it.uppercase() }.toSet()
        val checked = BooleanArray(devices.size) { devices[it].identity.uppercase() in current }
        AlertDialog.Builder(activity)
            .setTitle("选择目标设备（当前 ${TestConfig.TARGET_IDENTITY}）")
            .setMultiChoiceItems(
                devices.map { "${it.model}  |  ${it.identity}  |  ${it.mac}" }.toTypedArray(),
                checked
            ) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("应用") { _, _ ->
                val picked = devices.filterIndexed { index, _ -> checked[index] }
                if (picked.isEmpty()) {
                    appendLog("未选择设备，保持目标 ${TestConfig.TARGET_IDENTITY}")
                } else {
                    applyTarget(picked.map { it.identity }.joinToString(","))
                }
            }
            .setNeutralButton("扫描检查") { _, _ -> scanCheckTargets(devices) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 扫描一次，报告已登记设备是否在范围内（只检查，不改目标）。 */
    private fun scanCheckTargets(devices: List<TestDevice>) {
        appendLog("=== 扫描检查已登记设备是否在范围内 ===")
        scope.launch {
            val scanned = withContext(Dispatchers.IO) {
                runCatching { controller.scan(TestConfig.SCAN_TIMEOUT_MS) }.getOrElse { emptyList<BleDevice>() }
            }
            val lines = devices.map { d ->
                val hit = scanned.firstOrNull { dev ->
                    dev.mac.equals(d.mac, ignoreCase = true) ||
                        dev.name.orEmpty().endsWith(d.identity, ignoreCase = true)
                }
                "${d.model}(${d.identity}) -> ${if (hit != null) "在范围 name=${hit.name} mac=${hit.mac}" else "未发现"}"
            }
            appendLog(lines.joinToString("\n"))
        }
    }

    /** 切换目标设备：断开旧连接 -> 运行时覆盖 -> 写回外部 config.json -> 刷新能力标注。 */
    private fun applyTarget(identity: String) {
        if (busy) return
        scope.launch {
            val disconnected = withContext(Dispatchers.IO) {
                val connected = runCatching { controller.connectedSensors }.getOrNull().orEmpty()
                connected.forEach { profile -> runCatching { profile.disconnect() } }
                connected.size
            }
            TestConfig.setTargetIdentity(identity)
            val persisted = withContext(Dispatchers.IO) { TestConfig.persistTargetIdentity(identity) }
            activeSpec = null
            refreshActiveSpec()
            refresh()
            updateSummary()
            if (disconnected > 0) appendLog("切换目标前已断开 $disconnected 台旧连接")
            appendLog(
                "目标设备已切换为 ${TestConfig.targetIdentities.joinToString(",")}" +
                    "（写回外部 config.json：${if (persisted) "成功" else "失败"}）"
            )
        }
    }

    private fun openCase(testCase: TestCase) {
        if (testCase.mode == RunMode.AUTO) {
            runCases(listOf(testCase))
            return
        }
        val message = testCase.steps.mapIndexed { index, step -> "${index + 1}. $step" }
            .joinToString("\n").ifBlank { "请按手工用例预期结果检查。" }
            .let {
                if (testCase.run != null) {
                    "下面是流程概览。开始后程序会逐个动作弹窗提示（如单独提示关机、再单独提示开机），" +
                        "每次完成后点「我已执行，继续」。\n\n$it"
                } else {
                    it
                }
            }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("${testCase.id} ${testCase.title}")
            .setMessage(message)
            .setPositiveButton(if (testCase.run != null) "开始执行" else "通过", null)
            .setNegativeButton("失败", null)
            .setNeutralButton("跳过", null)
            .create()
        // 纯人工用例：判定弹窗打开期间标记为「执行中」，关闭后恢复。
        if (testCase.run == null) setRunning(testCase.id)
        dialog.setOnDismissListener { setRunning(null) }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (testCase.run != null) {
                    dialog.dismiss()
                    runCases(listOf(testCase), verification = true)
                } else {
                    recordManual(testCase, Verdict.PASS)
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                recordManual(testCase, Verdict.FAIL)
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                recordManual(testCase, Verdict.SKIP)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * semi-auto 用例的人工步骤提示：一次只弹一个动作，用户点「我已执行，继续」后才继续执行。
     * 关机/开机等需要按顺序完成的动作因此不会被一次性堆在同一条提示里。
     */
    private suspend fun promptManual(message: String): Unit = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val dialog = AlertDialog.Builder(activity)
                .setTitle("请执行人工操作")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("我已执行，继续", null)
                .setNegativeButton("跳过本用例", null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (dialog.isShowing) dialog.dismiss()
                    if (cont.isActive) cont.resume(Unit)
                }
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                    if (dialog.isShowing) dialog.dismiss()
                    if (cont.isActive) cont.resumeWithException(SkipException("用户跳过人工步骤"))
                }
            }
            cont.invokeOnCancellation { activity.runOnUiThread { if (dialog.isShowing) dialog.dismiss() } }
            dialog.show()
        }
    }

    private fun recordManual(testCase: TestCase, verdict: Verdict) {
        results[testCase.id] = TestResult(
            testCase.id, testCase.title, testCase.chapter, testCase.priority, testCase.mode,
            verdict, "人工判定 $verdict", 0,
        )
        adapter.notifyDataSetChanged()
        updateSummary()
    }

    /** 运行全部自动用例（runAutoCases 入口）。 */
    private fun runAuto(cases: List<TestCase>) {
        if (busy) return
        val autos = executionOrder(cases).filter { it.mode == RunMode.AUTO }
        if (autos.isEmpty()) {
            appendLog("没有可执行的自动用例")
            return
        }
        busy = true
        updateButtons()
        appendLog("=== 开始执行 ${autos.size} 条自动用例 ===")
        runJob = scope.launch {
            try {
                for ((index, testCase) in autos.withIndex()) {
                    ensureActive()
                    appendLog(">>> [${index + 1}/${autos.size}] ${testCase.id} ${testCase.title}")
                    setRunning(testCase.id)
                    val result = runner.runCase(testCase, ::appendLog)
                    results[result.id] = result
                    appendLog("<<< ${result.id} -> ${result.verdict}: ${result.detail}")
                    adapter.notifyDataSetChanged()
                    updateSummary()
                    updateButtons()
                }
                appendLog("=== 自动用例执行完成 ===")
            } catch (_: CancellationException) {
                appendLog("=== 执行已停止 ===")
            } finally {
                setRunning(null)
                busy = false
                updateButtons()
                runJob = null
            }
        }
    }

    /** 运行指定用例（runCase / runVerification 入口）。 */
    private fun runCases(cases: List<TestCase>, verification: Boolean = false) {
        if (busy) return
        if (cases.isEmpty()) {
            appendLog("没有可执行的用例")
            return
        }
        busy = true
        updateButtons()
        appendLog("=== 开始执行 ${cases.size} 条用例 ===")
        runJob = scope.launch {
            try {
                for ((index, testCase) in cases.withIndex()) {
                    ensureActive()
                    appendLog(">>> [${index + 1}/${cases.size}] ${testCase.id} ${testCase.title}")
                    setRunning(testCase.id)
                    val result = if (verification) {
                        runner.runVerification(testCase, ::appendLog)
                    } else {
                        runner.runCase(testCase, ::appendLog)
                    }
                    results[result.id] = result
                    appendLog("<<< ${result.id} -> ${result.verdict}: ${result.detail}")
                    adapter.notifyDataSetChanged()
                    updateSummary()
                }
                appendLog("=== 执行完成 ===")
            } catch (_: CancellationException) {
                appendLog("=== 执行已停止 ===")
            } finally {
                setRunning(null)
                busy = false
                updateButtons()
                runJob = null
            }
        }
    }

    private fun exportReport() {
        if (results.isEmpty()) {
            appendLog("尚无结果，请先执行用例")
            return
        }
        scope.launch {
            val output = withContext(Dispatchers.IO) { AutomationReport.write(activity, results.values) }
            appendLog("报告: ${output.jsonPath}\n文本: ${output.txtPath}\n${output.summary}")
            if (output.sharedPaths.isNotEmpty()) {
                appendLog("已复制到公共下载目录:\n${output.sharedPaths.joinToString("\n")}")
            } else {
                appendLog("未能复制到公共下载目录（请确认已授予“所有文件访问权限”）")
            }
        }
    }

    private fun appendLog(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread { appendLog(message) }
            return
        }
        logs.add(message)
        if (logs.size > 500) logs.removeAt(0)
        logText.text = logs.joinToString("\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    fun close() {
        runJob?.cancel()
        scope.cancel()
        activity.application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class CaseRow(val testCase: TestCase) : Row()
    }

    /** 用例行样式。改配色只动这里。 */
    private data class CaseStyle(
        val background: Int,
        val label: String,
        val labelColor: Int,
        val running: Boolean = false,
    )

    private inner class CaseAdapter : BaseAdapter() {
        var rows: List<Row> = emptyList()
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 2
        override fun getItemViewType(position: Int): Int = if (rows[position] is Row.Header) 0 else 1

        /** 执行中 > 已判定结果 > 当前设备不适用 > 待执行。 */
        private fun styleOf(testCase: TestCase, result: TestResult?): CaseStyle = when {
            runningCaseId == testCase.id ->
                CaseStyle(Color.rgb(0xE3, 0xF2, 0xFD), "执行中…", Color.rgb(0x15, 0x65, 0xC0), true)
            result?.verdict == Verdict.PASS ->
                CaseStyle(Color.rgb(0xE8, 0xF5, 0xE9), "通过", Color.rgb(0x18, 0x80, 0x38))
            result?.verdict == Verdict.FAIL ->
                CaseStyle(Color.rgb(0xFD, 0xEC, 0xEA), "失败", Color.rgb(0xC0, 0x20, 0x20))
            result?.verdict == Verdict.SKIP ->
                CaseStyle(Color.rgb(0xFF, 0xF8, 0xE1), "跳过", Color.rgb(0xA0, 0x70, 0x00))
            unmetRequires(testCase).isNotEmpty() ->
                CaseStyle(Color.rgb(0xF5, 0xF5, 0xF5), "不适用", Color.GRAY)
            else -> CaseStyle(Color.TRANSPARENT, "待执行", Color.GRAY)
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            return when (val row = rows[position]) {
                is Row.Header -> TextView(activity).apply {
                    text = (if (row.title in collapsed) "▸ " else "▾ ") + row.title
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.rgb(0x0A, 0x50, 0x9A))
                    setBackgroundColor(Color.rgb(0xE8, 0xEF, 0xF5))
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                }
                is Row.CaseRow -> caseView(row.testCase)
            }
        }

        private fun caseView(testCase: TestCase): View {
            val result = results[testCase.id]
            val style = styleOf(testCase, result)
            val root = LinearLayout(activity).apply {
                orientation = VERTICAL
                setPadding(dp(8), dp(5), dp(8), dp(5))
                setBackgroundColor(style.background)
            }
            val top = LinearLayout(activity).apply { orientation = HORIZONTAL }
            top.addView(TextView(activity).apply {
                text = "${testCase.id}  ${testCase.priority}/${testCase.mode.label}"
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                if (style.running) setTextColor(style.labelColor)
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            top.addView(TextView(activity).apply {
                text = style.label
                textSize = 12f
                gravity = Gravity.END
                setTextColor(style.labelColor)
                if (style.running) setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            root.addView(top)
            root.addView(TextView(activity).apply {
                text = testCase.title
                textSize = 12f
            })
            val unmet = unmetRequires(testCase)
            if (unmet.isNotEmpty()) {
                root.addView(TextView(activity).apply {
                    text = "不适用：当前设备 spec 不支持 ${unmet.joinToString(",")}"
                    textSize = 10f
                    setTextColor(Color.rgb(0xA0, 0x70, 0x00))
                })
            }
            if (!result?.detail.isNullOrBlank()) {
                root.addView(TextView(activity).apply {
                    text = result?.detail
                    textSize = 10f
                    maxLines = 2
                    setTextColor(Color.DKGRAY)
                })
            }
            return root
        }
    }
}
