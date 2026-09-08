package com.oymotion.sensorcapiktdemo

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.oymotion.sensor.capi.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

// Logcat tag.
private const val TAG = "SensorCapiKtDemo"

// UI refresh period (ms).
private const val UI_REFRESH_INTERVAL_MS = 50L

// Data queue backlog cap.
private const val DATA_QUEUE_CAPACITY = 1000

// Spectrum recompute interval (ms).
private const val FFT_UPDATE_INTERVAL_MS = 200L

// File picker request code.
private const val REQUEST_PICK_REPLAY_FILE = 42

// The demo's own version, shown in the title.
private const val DEMO_VERSION = "0.1.24"

// Sample rate options offered on the Device page (Hz).
private val SAMPLE_RATE_CANDIDATES = listOf(250, 500, 1000, 2000)
private val EMG_SAMPLE_RATE_CANDIDATES = listOf(500, 1000)
private val IMU_SAMPLE_RATE_CANDIDATES = listOf(50, 100, 200, 250, 400, 500, 1000, 2000)
private val PPG_SAMPLE_RATE_CANDIDATES = listOf(50, 100, 200, 400, 800, 1000, 1600, 3200)

// Battery reading stable band (%).
private const val POWER_STABLE_BAND = 4

// Bio page slot count.
private const val BIO_SLOT_COUNT = 8

// Impedance array kinds (per-device, indexed by channel).
private const val IMP_EMG = 0
private const val IMP_EEG = 1
private const val IMP_ECG = 2
private const val IMP_BRTH = 3

// Per-type stats display order and labels.
private val TYPE_DISPLAY_ORDER = listOf(
    DataType.NTF_ACC to "ACC",
    DataType.NTF_GYRO to "Gyro",
    DataType.NTF_IMU to "IMU",
    DataType.NTF_QUATERNION to "Quat",
    DataType.NTF_EULER to "Euler",
    DataType.NTF_EMG to "EMG",
    DataType.NTF_EEG to "EEG",
    DataType.NTF_PPG to "PPG",
    DataType.NTF_SPO2 to "SpO2",
    DataType.NTF_ECG to "ECG",
    DataType.NTF_BRTH to "BRTH",
    DataType.NTF_GEST to "GEST",
)

// Device-list row highlight: the SELECTED row (Connect/Disconnect target).
private val ROW_SELECTED_BG = Color.rgb(0xDC, 0xE9, 0xF5)
private val ROW_DEFAULT_BG = Color.TRANSPARENT

// Gesture box text before the first gesture sample.
private const val GESTURE_EMPTY_TEXT =
    "Gesture:\n  gesture: -- (0-8)\n  raw gesture: -- (0-8)\n" +
    "  possiblity: -- (0-100)\n  strength: -- (0-100)"

// Kotlin sen_capi demo: multi-device scan -> connect -> init -> stream, with
// Device / Bio / IMU pages and bin replay.
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var controller: SensorController

    private enum class BioMode { NONE, EMG, EEG, PPG }

    private class SpectrumFeed(val view: WaveformView, val spectrum: SpectrumView) {
        var lastSubmitMs = 0L
        val inFlight = AtomicBoolean(false)
    }

    // One bio slot binding: a slot view fed from channel `channel` of the
    // `dataType` stream, with an optional impedance side-text target
    // (impKind < 0 = none).
    private data class BioFeed(
        val view: WaveformView,
        val dataType: Int,
        val channel: Int,
        val impKind: Int = -1,
        val impChannel: Int = 0,
    )

    // Per-device UI and data state, keyed by mac in deviceStates. `profile`
    // is null only for a state whose link is already torn down; replay
    // sessions get a state too (isReplay), keyed by the bin's mac.
    private inner class DeviceUiState(
        val mac: String,
        val profile: SensorProfile?,
        val isReplay: Boolean,
    ) {
        var info: DeviceInfo? = null
        var lastPower = -1

        // True while the stream is on.
        @Volatile var transferring = false

        // Bio mode detection: observed dataType -> channelCount.
        val bioChannels = HashMap<Int, Int>()
        @Volatile var bioMode = BioMode.NONE
        @Volatile var bioFeeds: List<BioFeed> = emptyList()
        // Current binding of each bio slot (null = unbound).
        val slotFeeds = arrayOfNulls<BioFeed>(BIO_SLOT_COUNT)
        // Current EEG page (0-based) and the channel counts the current bio
        // layout was built from.
        var bioPageIndex = 0
        var lastBioCounts = IntArray(6) // eeg, ecg, brth, ppg, spo2, emg

        // Per-device data counters.
        var batches = 0L
        var samples = 0L
        var lostPackages = 0L
        var lastLogMs = 0L
        val diagTypes = java.util.Collections.synchronizedSet(java.util.HashSet<Int>())

        // Per-type accounting for the stats/rate labels.
        val rateLock = Any()
        val rateCounts = HashMap<Int, Long>()      // valid samples in the current window
        val actualRates = HashMap<Int, Double>()   // rotated once per second
        val nominalRates = HashMap<Int, Float>()
        val nominalChannels = HashMap<Int, Int>()
        val lostCounts = HashMap<Int, Long>()      // latest per-type lostPackageCount
        val typeBatches = HashMap<Int, Long>()
        var rateWindowStartMs = System.currentTimeMillis()

        // Rotates the accumulated per-type sample counts into actualRates;
        // call once per second.
        fun updateActualRates() {
            val now = System.currentTimeMillis()
            synchronized(rateLock) {
                val elapsed = (now - rateWindowStartMs) / 1000.0
                if (elapsed <= 0.0) return
                actualRates.clear()
                for ((t, c) in rateCounts) actualRates[t] = c / elapsed
                rateCounts.clear()
                rateWindowStartMs = now
            }
        }
        // Stream-start wall clock (s) + first-packet delay (ms) of the
        // current session, carried by every batch; 0 until known.
        @Volatile var streamStartTimeSec = 0.0
        @Volatile var streamDelayMs = 0

        // Latest impedance per channel (ohms; -1 = unknown), by IMP_* kind.
        val impLock = Any()
        val impedances = arrayOf(
            ArrayList<Float>(), ArrayList<Float>(), ArrayList<Float>(), ArrayList<Float>())

        // Latest gesture readout; -1 = nothing received yet.
        @Volatile var gesture = -1
        @Volatile var rawGesture = -1
        @Volatile var possibility = -1
        @Volatile var strength = -1

        // Per-device bio live filter.
        val liveFilter = LiveFilter()

        // Cached sample-rate control state: param key -> (options, current).
        val sampleRateStates = HashMap<String, Pair<List<Int>, Int>>()

        // Bumped on every clearDataViews.
        @Volatile var dataSession = 0

        // Page contents (see buildStateViews).
        lateinit var bioPage: LinearLayout
        lateinit var bioHeader: TextView
        lateinit var bioContainer: LinearLayout
        lateinit var bioSlots: List<WaveformView>
        lateinit var bioSpectra: List<SpectrumView>
        lateinit var bioSpectrumFeeds: List<SpectrumFeed>
        lateinit var imuPage: LinearLayout
        lateinit var accView: WaveformView
        lateinit var gyroView: WaveformView
        lateinit var eulerView: WaveformView
        lateinit var quatView: WaveformView
        lateinit var cubeView: CubeView
        lateinit var accSpectrum: SpectrumView
        lateinit var gyroSpectrum: SpectrumView
        lateinit var eulerSpectrum: SpectrumView
        lateinit var quatSpectrum: SpectrumView
        lateinit var spectrumFeeds: List<SpectrumFeed>
    }

    // Connected/streaming devices plus at most one replay state, keyed by
    // mac.
    private val deviceStates = LinkedHashMap<String, DeviceUiState>()

    // The device whose pages are shown and whose profile the setParam
    // controls target; null when nothing is connected.
    @Volatile private var currentMac: String? = null

    // Bin replay state; replayMac keys the replay DeviceUiState.
    private var replayProfile: SensorProfile? = null
    private var replayMac: String? = null
    // Group replay member macs (';'-separated paths in the path field).
    private val replayMacs = mutableListOf<String>()

    // Page widgets.
    private lateinit var pageContainer: FrameLayout
    private lateinit var tabButtons: List<Button>
    private lateinit var bioPageContainer: FrameLayout
    private lateinit var imuPageContainer: FrameLayout

    // Live Filter band spinner selection (Bio page).
    private var filterBand = 0
    private lateinit var filterSpinner: Spinner

    // Spectrum worker.
    private val fftExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SpectrumWorker").apply { isDaemon = true }
    }

    // Device page widgets.
    private lateinit var scanBtn: Button
    private lateinit var connectBtn: Button
    private lateinit var multiBtn: Button
    // True while a multi start/stop op is in flight.
    private var multiOpActive = false
    private lateinit var statusText: TextView
    private lateinit var rateText: TextView
    private lateinit var batteryText: TextView
    private lateinit var linkText: TextView
    private lateinit var mtuText: TextView
    private lateinit var selectedText: TextView
    private lateinit var statsText: TextView
    private lateinit var sdkText: TextView
    private lateinit var gestureText: TextView
    private lateinit var deviceListView: ListView
    private lateinit var listAdapter: ArrayAdapter<String>
    // Mac of each visible list row, in row order.
    private var rowMacs: List<String> = emptyList()
    private lateinit var cloneCheck: CheckBox
    private lateinit var autoReconnectCheck: CheckBox
    private lateinit var debugLogCheck: CheckBox
    private lateinit var binDataCheck: CheckBox
    private lateinit var replayPathEdit: EditText
    private var sessionLogDir: String? = null
    private lateinit var replayBtn: Button
    private lateinit var multiReplayBtn: Button
    private lateinit var pauseReplayBtn: Button
    private lateinit var stopReplayBtn: Button
    private lateinit var analyzeBtn: Button
    private lateinit var eegSampleRate: SampleRateControl
    private lateinit var emgSampleRate: SampleRateControl
    private lateinit var imuSampleRate: SampleRateControl
    private lateinit var ppgSampleRate: SampleRateControl
    private val sampleRateControls
        get() = listOf(eegSampleRate, emgSampleRate, imuSampleRate, ppgSampleRate)
    private val ntfChecks = LinkedHashMap<String, CheckBox>()
    private val filterChecks = LinkedHashMap<String, CheckBox>()

    // Bio page widgets (EEG paging).
    private lateinit var pageControlsRow: LinearLayout
    private lateinit var prevPageBtn: Button
    private lateinit var nextPageBtn: Button
    private lateinit var pageLabel: TextView

    private val devices = LinkedHashMap<String, BleDevice>()
    private val sortedDevices = ArrayList<BleDevice>()
    // Consecutive scan rounds each mac was absent (three-round eviction).
    private val absentRounds = HashMap<String, Int>()
    private var selectedMac: String? = null

    @Volatile private var scanning = false
    @Volatile private var cloneData = false
    private var suppressNtfCallbacks = false

    // Session-wide toggles.
    private var autoReconnect = true
    private val savedParamsByMac = mutableMapOf<String, MutableMap<String, String>>()
    private var debugLogEnabled = true
    private var binDataEnabled = true
    private var replayPaused = false
    private var analyzing = false

    // Last 1 Hz rotation of the per-type rate windows.
    private var lastRateTickMs = 0L

    // Data queue (mac + batch) drained by the data worker.
    private class QueuedBatch(val mac: String, val data: SensorData)
    private val dataQueue = ArrayBlockingQueue<QueuedBatch>(DATA_QUEUE_CAPACITY)
    private val dataWorkerStop = AtomicBoolean(false)
    private var dataWorker: Thread? = null

    // Periodic UI refresh.
    private val uiRefreshTimer = object : Runnable {
        override fun run() {
            val st = currentState()
            if (st != null) {
                for (v in st.bioSlots) v.invalidate()
                st.accView.invalidate()
                st.gyroView.invalidate()
                st.eulerView.invalidate()
                st.quatView.invalidate()
                st.cubeView.invalidate()
                refreshBioSideTexts(st)
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastRateTickMs >= 1000) {
                    lastRateTickMs = nowMs
                    st.updateActualRates()
                }
                updateStats()
            }
            maybeSubmitSpectra()
            mainHandler.postDelayed(this, UI_REFRESH_INTERVAL_MS)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pageContainer = FrameLayout(this)
        root.addView(pageContainer, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        pageContainer.addView(buildDevicePage())
        pageContainer.addView(buildWaveformPage())
        pageContainer.addView(buildImuPage())
        root.addView(buildTabBar(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            val m = dp(20)
            setMargins(m, m, m, m)
        })
        setContentView(root)
        showPage(0)
        startDataWorker()
        mainHandler.postDelayed(uiRefreshTimer, UI_REFRESH_INTERVAL_MS)

        controller = SensorController.getInstance()
        title = "SensorCapiKtDemo v$DEMO_VERSION (SDK ${controller.version})"
        sdkText.text = "SDK: ${controller.version}"
        // Session log dir: set before enabling the debug log and registering
        // the BLE bridge.
        if (debugLogEnabled) {
            applySdkDebugLog()
        }
        // Default to the session debug log dir.
        replayPathEdit.setText(defaultReplayPath())
        controller.registerBleBridge(applicationContext)
        controller.setOnScanResultListener { found ->
            runOnUiThread { mergeDevices(found) }
        }
        controller.setOnEnableChangedListener { enabled ->
            runOnUiThread {
                if (!enabled) {
                    scanning = false
                    scanBtn.text = "Start Scan"
                    setStatus("Bluetooth turned off")
                }
            }
        }
        setStatus("SDK version: ${controller.version}")

        requestBlePermissions()
        requestStorageAccess()
    }

    // ---- UI construction ---------------------------------------------------

    private fun buildTabBar(): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabButtons = listOf("Device", "Bio", "IMU").mapIndexed { index, title ->
            Button(this).apply {
                text = title
                setOnClickListener { showPage(index) }
                bar.addView(this, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
        }
        return bar
    }

    private fun showPage(index: Int) {
        for (i in 0 until pageContainer.childCount) {
            pageContainer.getChildAt(i).visibility =
                if (i == index) View.VISIBLE else View.GONE
        }
        tabButtons.forEachIndexed { i, b -> b.isEnabled = i != index }
    }

    // One labeled sample-rate radio row (wrapping FlowLayout of radios).
    private inner class SampleRateControl(
        val key: String,
        label: String,
        candidates: List<Int>,
    ) {
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val radios = LinkedHashMap<Int, RadioButton>()
        private var suppress = false

        init {
            row.addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 13f
            })
            val flow = FlowLayout(this@MainActivity).apply {
                horizontalSpacing = dp(8)
                verticalSpacing = dp(0)
            }
            for (rate in candidates) {
                val rb = RadioButton(this@MainActivity).apply {
                    text = "$rate Hz"
                    isEnabled = false
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked && !suppress) {
                            check(rate)
                            onSampleRateSelected(key, rate)
                        }
                    }
                }
                radios[rate] = rb
                flow.addView(rb, ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }
            row.addView(flow)
        }

        // Checked state only.
        fun check(rate: Int) {
            suppress = true
            for ((r, rb) in radios) rb.isChecked = (r == rate)
            suppress = false
        }

        // Options + current rate sync.
        fun sync(options: List<Int>, current: Int) {
            row.visibility = if (options.isEmpty()) View.GONE else View.VISIBLE
            suppress = true
            for ((r, rb) in radios) {
                val supported = r in options
                rb.visibility = if (supported) View.VISIBLE else View.GONE
                rb.isEnabled = supported
                rb.isChecked = r == current
            }
            suppress = false
        }

        fun greyOut() {
            suppress = true
            for (rb in radios.values) {
                rb.isEnabled = false
                rb.isChecked = false
            }
            suppress = false
        }
    }

    private fun buildDevicePage(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scanBtn = Button(this).apply {
            text = "Start Scan"
            setOnClickListener { toggleScan() }
        }
        connectBtn = Button(this).apply {
            text = "Connect"
            isEnabled = false
            setOnClickListener { toggleConnect() }
        }
        btnRow.addView(scanBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        btnRow.addView(connectBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        page.addView(btnRow)

        // Synchronized multi-device stream start.
        val multiRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        multiBtn = Button(this).apply {
            text = "Multi Start"
            isEnabled = false
            setOnClickListener { onMultiSyncClicked() }
        }
        multiRow.addView(multiBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        page.addView(multiRow)

        statusText = TextView(this).apply { textSize = 13f }
        sdkText = TextView(this).apply { textSize = 13f }
        rateText = TextView(this).apply { textSize = 13f }
        batteryText = TextView(this).apply { textSize = 13f; text = "Battery: -" }
        linkText = TextView(this).apply { textSize = 13f; text = "Link: --" }
        mtuText = TextView(this).apply { textSize = 13f; text = "MTU: --" }
        selectedText = TextView(this).apply { textSize = 13f; text = "Selected: -" }
        statsText = TextView(this).apply { textSize = 13f; text = "Batches: 0 (lost pkgs: 0)" }
        page.addView(statusText)
        page.addView(sdkText)
        page.addView(rateText)
        page.addView(batteryText)
        page.addView(linkText)
        page.addView(mtuText)
        page.addView(selectedText)
        page.addView(statsText)

        // Device list: tapping a row selects it; tapping a connected row
        // also makes it the current device whose data the Bio/IMU pages show.
        listAdapter = object : ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, ArrayList()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val mac = rowMacs.getOrNull(position)
                v.setBackgroundColor(
                    if (mac != null && mac == selectedMac) ROW_SELECTED_BG else ROW_DEFAULT_BG)
                return v
            }
        }
        deviceListView = ListView(this).apply {
            adapter = listAdapter
            setOnItemClickListener { _, _, position, _ ->
                // Rows past the scan list are state-only entries (bin replay).
                val mac = rowMacs.getOrNull(position) ?: return@setOnItemClickListener
                val d = sortedDevices.getOrNull(position)
                selectedMac = mac
                selectedText.text = "Selected: ${d?.name ?: "replay"} [$mac]"
                listAdapter.notifyDataSetChanged()
                synchronized(deviceStates) {
                    if (deviceStates.containsKey(mac) && mac != currentMac) {
                        setCurrentDevice(mac)
                    }
                }
                updateConnectButton()
            }
        }
        // Fixed height: the page scrolls.
        page.addView(deviceListView, LinearLayout.LayoutParams(MATCH_PARENT, dp(150)))

        // Clone Data checkbox, default OFF.
        cloneCheck = CheckBox(this).apply {
            text = "Clone Data"
            isChecked = false
            setOnCheckedChangeListener { _, isChecked -> cloneData = isChecked }
        }
        page.addView(cloneCheck)

        // Auto Reconnect checkbox, default ON.
        autoReconnectCheck = CheckBox(this).apply {
            text = "Auto Reconnect"
            isChecked = true
            setOnCheckedChangeListener { _, isChecked -> onAutoReconnectToggled(isChecked) }
        }
        page.addView(autoReconnectCheck)

        // Debug log toggles, both default ON.
        val debugRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        debugLogCheck = CheckBox(this).apply {
            text = "Enable SDK Debug Log"
            isChecked = true
            setOnCheckedChangeListener { _, isChecked -> onDebugLogToggled(isChecked) }
        }
        binDataCheck = CheckBox(this).apply {
            text = "Enable Debug Bin Data"
            isChecked = true
            setOnCheckedChangeListener { _, isChecked -> onBinDataToggled(isChecked) }
        }
        debugRow.addView(debugLogCheck)
        debugRow.addView(binDataCheck)
        page.addView(debugRow)

        // NTF switches targeting the current device.
        val ntfKeys = listOf("NTF_EEG", "NTF_EMG", "NTF_GEST", "NTF_PPG", "NTF_SPO2", "NTF_IMU")
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var row: LinearLayout? = null
        ntfKeys.forEachIndexed { i, key ->
            if (i % 3 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row)
            }
            val cb = CheckBox(this).apply {
                text = key
                isEnabled = false
                setOnCheckedChangeListener { _, isChecked -> onNtfToggled(key, isChecked) }
            }
            ntfChecks[key] = cb
            row!!.addView(cb, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        page.addView(grid)

        // FILTER switches targeting the current device.
        val filterKeys = listOf("FILTER_50HZ", "FILTER_60HZ", "FILTER_HPF", "FILTER_LPF")
        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (key in filterKeys) {
            val cb = CheckBox(this).apply {
                text = key
                isEnabled = false
                setOnCheckedChangeListener { _, isChecked -> onFilterToggled(key, isChecked) }
            }
            filterChecks[key] = cb
            filterRow.addView(cb, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        page.addView(filterRow)

        // Sample rate options.
        eegSampleRate = SampleRateControl("EEG_SAMPLE_RATE", "EEG Sample Rate", SAMPLE_RATE_CANDIDATES)
        emgSampleRate = SampleRateControl("EMG_SAMPLE_RATE", "EMG Sample Rate", EMG_SAMPLE_RATE_CANDIDATES)
        imuSampleRate = SampleRateControl("IMU_SAMPLE_RATE", "IMU Sample Rate", IMU_SAMPLE_RATE_CANDIDATES)
        ppgSampleRate = SampleRateControl("PPG_SAMPLE_RATE", "PPG Sample Rate", PPG_SAMPLE_RATE_CANDIDATES)
        for (c in sampleRateControls) page.addView(c.row)

        // Gesture readout.
        gestureText = TextView(this).apply {
            textSize = 13f
            text = GESTURE_EMPTY_TEXT
        }
        page.addView(gestureText)

        // Bin replay section.
        replayPathEdit = EditText(this).apply {
            setSingleLine()
        }
        page.addView(replayPathEdit, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val replayRow = FlowLayout(this).apply {
            horizontalSpacing = dp(8)
            verticalSpacing = dp(4)
        }
        replayBtn = Button(this).apply {
            text = "Replay Bin"
            setOnClickListener { pickReplayFile() }
        }
        multiReplayBtn = Button(this).apply {
            text = "Multi Replay Bin"
            setOnClickListener { onMultiReplay() }
        }
        pauseReplayBtn = Button(this).apply {
            text = "Pause Replay"
            isEnabled = false
            setOnClickListener { onReplayPauseResume() }
        }
        stopReplayBtn = Button(this).apply {
            text = "Stop Replay"
            setOnClickListener { stopReplay() }
        }
        analyzeBtn = Button(this).apply {
            text = "Analyze Bin"
            setOnClickListener { onAnalyzeBin() }
        }
        replayRow.addView(replayBtn, ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        replayRow.addView(multiReplayBtn, ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        replayRow.addView(pauseReplayBtn, ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        replayRow.addView(stopReplayBtn, ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        replayRow.addView(analyzeBtn, ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        page.addView(replayRow)
        // The page scrolls.
        return ScrollView(this).apply { addView(page) }
    }

    private fun buildWaveformPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Live Filter band spinner.
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), 0)
        }
        val filterLabel = TextView(this).apply {
            text = "Live Filter:"
            textSize = 13f
        }
        filterRow.addView(filterLabel)
        filterSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item,
                LiveFilter.bandLabels()).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?,
                                            position: Int, id: Long) {
                    // Skip the initial selection event fired at layout time.
                    if (position != filterBand) {
                        appLog("User: live filter -> ${LiveFilter.bandLabels()[position]}")
                    }
                    filterBand = position
                    val states = synchronized(deviceStates) { deviceStates.values.toList() }
                    for (st in states) st.liveFilter.setBand(position)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        filterRow.addView(filterSpinner, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        page.addView(filterRow)

        // EEG paging controls.
        pageControlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
            visibility = View.GONE
        }
        prevPageBtn = Button(this).apply {
            text = "Prev"
            setOnClickListener { onBioPage(-1) }
        }
        pageLabel = TextView(this).apply {
            text = "Page 1 / 1"
            textSize = 13f
            gravity = android.view.Gravity.CENTER
        }
        nextPageBtn = Button(this).apply {
            text = "Next"
            setOnClickListener { onBioPage(1) }
        }
        pageControlsRow.addView(prevPageBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        pageControlsRow.addView(pageLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        pageControlsRow.addView(nextPageBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        page.addView(pageControlsRow)

        // The current device's bio page content is swapped in here.
        bioPageContainer = FrameLayout(this)
        page.addView(bioPageContainer, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        showPagePlaceholder(bioPageContainer, "Not connected")
        return page
    }

    private fun buildImuPage(): View {
        // The current device's IMU page content (waveforms + spectrum strips
        // + cube) is swapped in here.
        imuPageContainer = FrameLayout(this)
        showPagePlaceholder(imuPageContainer, "Not connected")
        return imuPageContainer
    }

    // Placeholder text shown while a page has no current device.
    private fun showPagePlaceholder(container: FrameLayout, text: String) {
        container.removeAllViews()
        val tv = TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(dp(8), dp(16), dp(8), 0)
        }
        container.addView(tv, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    // ---- per-device state ----------------------------------------------------

    private fun currentState(): DeviceUiState? {
        val mac = currentMac ?: return null
        return synchronized(deviceStates) { deviceStates[mac] }
    }

    // Builds one device state's Bio/IMU page contents.
    private fun buildStateViews(st: DeviceUiState) {
        st.bioPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        st.bioHeader = TextView(this).apply {
            textSize = 13f
            setPadding(dp(8), dp(4), dp(8), 0)
        }
        st.bioContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        st.bioPage.addView(st.bioHeader)
        st.bioPage.addView(st.bioContainer, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        // One row per slot: [spectrum | waveform] 50/50 in split rows; a
        // hidden spectrum leaves the waveform full-width.
        val slots = ArrayList<WaveformView>()
        val spectra = ArrayList<SpectrumView>()
        for (i in 0 until BIO_SLOT_COUNT) {
            val spectrum = SpectrumView(this)
            spectrum.visibility = View.GONE
            val waveform = WaveformView(this, viewChannels = 1, capacity = 1250)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(spectrum, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                addView(waveform, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            }
            st.bioContainer.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            slots.add(waveform)
            spectra.add(spectrum)
        }
        st.bioSlots = slots
        st.bioSpectra = spectra
        st.bioSpectrumFeeds = slots.mapIndexed { i, v -> SpectrumFeed(v, spectra[i]) }
        showBioPlaceholder(st, "Waiting for data ...")

        st.imuPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val accLabels = arrayOf("ACC-X", "ACC-Y", "ACC-Z")
        val gyroLabels = arrayOf("GYRO-X", "GYRO-Y", "GYRO-Z")
        val eulerLabels = arrayOf("Pitch(Y)", "Roll(X)", "Yaw(Z)")
        val quatLabels = arrayOf("W", "X", "Y", "Z")
        st.accView = WaveformView(this, viewChannels = 3, capacity = 500).apply {
            title = "ACC"; labels = accLabels
        }
        st.gyroView = WaveformView(this, viewChannels = 3, capacity = 500).apply {
            title = "GYRO"; labels = gyroLabels
        }
        st.eulerView = WaveformView(this, viewChannels = 3, capacity = 500).apply {
            title = "EULER (deg)"; labels = eulerLabels
        }
        st.quatView = WaveformView(this, viewChannels = 4, capacity = 500).apply {
            title = "QUATERNION"; labels = quatLabels
        }
        st.accSpectrum = SpectrumView(this).apply { labels = accLabels }
        st.gyroSpectrum = SpectrumView(this).apply { labels = gyroLabels }
        st.eulerSpectrum = SpectrumView(this).apply { labels = eulerLabels }
        st.quatSpectrum = SpectrumView(this).apply { labels = quatLabels }
        st.cubeView = CubeView(this)
        // One row per pair: [spectrum | waveform] 50/50, same as the bio rows.
        val imuPairs = listOf(
            st.accSpectrum to st.accView,
            st.gyroSpectrum to st.gyroView,
            st.eulerSpectrum to st.eulerView,
            st.quatSpectrum to st.quatView,
        )
        for ((spectrum, waveform) in imuPairs) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(spectrum, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                addView(waveform, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            }
            st.imuPage.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        st.imuPage.addView(st.cubeView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 2f).apply {
            val m = dp(8)
            setMargins(m, m, m, m)
        })
        st.spectrumFeeds = listOf(
            SpectrumFeed(st.accView, st.accSpectrum),
            SpectrumFeed(st.gyroView, st.gyroSpectrum),
            SpectrumFeed(st.eulerView, st.eulerSpectrum),
            SpectrumFeed(st.quatView, st.quatSpectrum),
        )
    }

    // Registers a device (live or replay) and builds its page contents.
    private fun registerDeviceState(mac: String, profile: SensorProfile?,
                                    isReplay: Boolean): DeviceUiState {
        val st = DeviceUiState(mac, profile, isReplay)
        st.liveFilter.setBand(filterBand)
        buildStateViews(st)
        synchronized(deviceStates) { deviceStates[mac] = st }
        refreshDeviceList()
        return st
    }

    // Drops a device state (disconnect / replay end).
    private fun removeDeviceState(mac: String) {
        val st = synchronized(deviceStates) { deviceStates.remove(mac) } ?: return
        bioPageContainer.removeView(st.bioPage)
        imuPageContainer.removeView(st.imuPage)
        refreshDeviceList()
        if (currentMac == mac) {
            val next = synchronized(deviceStates) { deviceStates.keys.firstOrNull() }
            setCurrentDevice(next)
        }
        updateConnectButton()
    }

    // Makes a device the current one: its page contents are swapped into the
    // Bio/IMU page containers and the Device page controls re-sync from its
    // profile.
    private fun setCurrentDevice(mac: String?) {
        currentMac = mac
        bioPageContainer.removeAllViews()
        imuPageContainer.removeAllViews()
        val st = mac?.let { synchronized(deviceStates) { deviceStates[it] } }
        if (st == null) {
            showPagePlaceholder(bioPageContainer, "Not connected")
            showPagePlaceholder(imuPageContainer, "Not connected")
            batteryText.text = "Battery: -"
            linkText.text = "Link: --"
            mtuText.text = "MTU: --"
            greyOutControls()
        } else {
            bioPageContainer.addView(st.bioPage)
            imuPageContainer.addView(st.imuPage)
            batteryText.text =
                if (st.lastPower >= 0) "Battery: ${st.lastPower}%" else "Battery: -"
            st.info?.let { updateLinkInfo(it) } ?: run {
                linkText.text = "Link: --"
                mtuText.text = "MTU: --"
            }
            if (st.isReplay) {
                // No parameter control over a replay session.
                greyOutControls()
                syncSampleRateCheckedFromInfo(st, st.info)
            } else if (st.profile != null) {
                syncSwitches(st.profile)
                syncSampleRateControls(st.profile)
            }
        }
        updatePageControls()
        updateStats()
        refreshDeviceList()
        Log.i(TAG, "current device -> ${mac ?: "-"}")
    }

    // Greys the per-device setParam controls (disconnect / replay / none).
    private fun greyOutControls() {
        suppressNtfCallbacks = true
        for (cb in ntfChecks.values) {
            cb.isEnabled = false
            cb.isChecked = false
        }
        for (cb in filterChecks.values) {
            cb.isEnabled = false
            cb.isChecked = false
        }
        suppressNtfCallbacks = false
        for (c in sampleRateControls) c.greyOut()
    }

    // Idle state of one device's bio page: all slots unbound, placeholder.
    private fun showBioPlaceholder(st: DeviceUiState, text: String) {
        st.bioMode = BioMode.NONE
        st.bioFeeds = emptyList()
        st.bioHeader.text = "Waveform (auto: EMG / EEG / PPG)"
        for (v in st.bioSlots) {
            v.title = ""
            v.placeholder = text
            v.sideText = ""
        }
        for (v in st.bioSpectra) {
            v.visibility = View.GONE
            v.clear()
        }
    }

    // Binds one bio slot to a (stream, channel) source; withSpectrum shows
    // the slot's spectrum half.
    private fun bindSlot(st: DeviceUiState, slot: Int, dataType: Int, channel: Int,
                         label: String, colorIndex: Int, impKind: Int,
                         feeds: MutableList<BioFeed>, withSpectrum: Boolean = false) {
        val v = st.bioSlots[slot]
        val feed = BioFeed(v, dataType, channel, impKind, channel)
        if (st.slotFeeds[slot] != feed) {
            v.clear()
            st.bioSpectra[slot].clear()
        }
        st.slotFeeds[slot] = feed
        v.title = label
        v.colorIndex = colorIndex
        v.placeholder = ""
        v.sideText = ""
        val spectrum = st.bioSpectra[slot]
        if (withSpectrum) {
            spectrum.labels = arrayOf(label)
            spectrum.colorIndex = colorIndex
            spectrum.visibility = View.VISIBLE
        } else {
            spectrum.visibility = View.GONE
        }
        feeds.add(feed)
    }

    // Unbinds one bio slot.
    private fun unbindSlot(st: DeviceUiState, slot: Int, placeholder: String) {
        val v = st.bioSlots[slot]
        st.slotFeeds[slot] = null
        v.title = ""
        v.colorIndex = -1
        v.placeholder = placeholder
        v.sideText = ""
        st.bioSpectra[slot].visibility = View.GONE
        st.bioSpectra[slot].clear()
    }

    // Re-layouts one device's bio slots for one mode.
    private fun layoutBio(st: DeviceUiState, mode: BioMode, eegCh: Int, ecgCh: Int,
                          brthCh: Int, ppgCh: Int, spo2Ch: Int, emgCh: Int) {
        st.bioMode = mode
        st.lastBioCounts = intArrayOf(eegCh, ecgCh, brthCh, ppgCh, spo2Ch, emgCh)
        Log.i(TAG, "layoutBio[${st.mac}]: $mode eeg=$eegCh ecg=$ecgCh brth=$brthCh " +
                "ppg=$ppgCh spo2=$spo2Ch emg=$emgCh page=${st.bioPageIndex}")
        val feeds = ArrayList<BioFeed>()
        val waiting = "Waiting for data ..."
        when (mode) {
            BioMode.EMG -> {
                st.bioHeader.text = "EMG Waveform"
                val count = minOf(if (emgCh > 0) emgCh else 0, BIO_SLOT_COUNT)
                for (i in 0 until BIO_SLOT_COUNT) {
                    if (i < count) {
                        bindSlot(st, i, DataType.NTF_EMG, i, "EMG-${i + 1}", i, IMP_EMG,
                            feeds, withSpectrum = true)
                    } else {
                        unbindSlot(st, i, waiting)
                    }
                }
            }
            BioMode.EEG -> {
                st.bioHeader.text = "EEG + ECG + BRTH Waveform"
                val hasECG = ecgCh > 0
                val hasBRTH = brthCh > 0
                val perPage = BIO_SLOT_COUNT - (if (hasECG) 1 else 0) - (if (hasBRTH) 1 else 0)
                val pages = bioPageCount(perPage, eegCh)
                st.bioPageIndex = st.bioPageIndex.coerceIn(0, pages - 1)
                val startCh = st.bioPageIndex * perPage
                val ecgIndex = BIO_SLOT_COUNT - 1 - (if (hasBRTH) 1 else 0)
                val brthIndex = BIO_SLOT_COUNT - 1
                for (i in 0 until BIO_SLOT_COUNT) {
                    val ch = startCh + i
                    if (i < perPage && ch < eegCh) {
                        bindSlot(st, i, DataType.NTF_EEG, ch, "EEG-${ch + 1}", ch, IMP_EEG,
                            feeds, withSpectrum = true)
                    } else if (hasECG && i == ecgIndex) {
                        bindSlot(st, i, DataType.NTF_ECG, 0, "ECG", -1, IMP_ECG, feeds,
                            withSpectrum = true)
                    } else if (hasBRTH && i == brthIndex) {
                        bindSlot(st, i, DataType.NTF_BRTH, 0, "BRTH", -1, IMP_BRTH, feeds)
                    } else {
                        // Slots past the last EEG channel stay blank.
                        val noSuchChannel = i < perPage && ch >= eegCh
                        unbindSlot(st, i, if (noSuchChannel) "" else waiting)
                    }
                }
            }
            BioMode.PPG -> {
                // Fixed plot set: EEG fp1/fp2 + PPG red/ir led + SpO2
                // spo2/heart_rate.
                st.bioHeader.text = "EEG + PPG + SpO2 Waveform"
                val plots = arrayOf(
                    intArrayOf(DataType.NTF_EEG, 0), intArrayOf(DataType.NTF_EEG, 1),
                    intArrayOf(DataType.NTF_PPG, 0), intArrayOf(DataType.NTF_PPG, 1),
                    intArrayOf(DataType.NTF_SPO2, 0), intArrayOf(DataType.NTF_SPO2, 1),
                )
                val labels = arrayOf("fp1", "fp2", "red_led", "ir_led", "spo2", "heart_rate")
                val counts = intArrayOf(eegCh, ppgCh, spo2Ch)
                for (i in 0 until BIO_SLOT_COUNT) {
                    if (i < plots.size) {
                        val type = plots[i][0]
                        val ch = plots[i][1]
                        val available = when (type) {
                            DataType.NTF_EEG -> counts[0]
                            DataType.NTF_PPG -> counts[1]
                            else -> counts[2]
                        }
                        if (ch < available) {
                            val imp = if (type == DataType.NTF_EEG) IMP_EEG else -1
                            // SpO2 rows (low-rate derived values) stay
                            // full-width.
                            bindSlot(st, i, type, ch, labels[i], i, imp, feeds,
                                withSpectrum = type != DataType.NTF_SPO2)
                        } else {
                            unbindSlot(st, i, waiting)
                        }
                    } else {
                        unbindSlot(st, i, "")
                    }
                }
            }
            BioMode.NONE -> {
                showBioPlaceholder(st, waiting)
            }
        }
        st.bioFeeds = feeds
        updatePageControls()
    }

    // EEG page count for the given per-page size and channel total.
    private fun bioPageCount(perPage: Int, total: Int): Int {
        if (perPage <= 0 || total <= 0) return 1
        return maxOf(1, (total + perPage - 1) / perPage)
    }

    // Page count of a device state (1 outside EEG mode).
    private fun bioPageCount(st: DeviceUiState?): Int {
        if (st == null || st.bioMode != BioMode.EEG) return 1
        val c = st.lastBioCounts
        val perPage = BIO_SLOT_COUNT - (if (c[1] > 0) 1 else 0) - (if (c[2] > 0) 1 else 0)
        return bioPageCount(perPage, c[0])
    }

    // Syncs the Bio page paging row with the current device.
    private fun updatePageControls() {
        val st = currentState()
        val pages = bioPageCount(st)
        pageControlsRow.visibility = if (pages > 1) View.VISIBLE else View.GONE
        pageLabel.text = "Page ${(st?.bioPageIndex ?: 0) + 1} / $pages"
        prevPageBtn.isEnabled = (st?.bioPageIndex ?: 0) > 0
        nextPageBtn.isEnabled = (st?.bioPageIndex ?: 0) < pages - 1
    }

    // Prev/Next page.
    private fun onBioPage(delta: Int) {
        val st = currentState() ?: return
        if (st.bioMode != BioMode.EEG) return
        val pages = bioPageCount(st)
        val newPage = (st.bioPageIndex + delta).coerceIn(0, pages - 1)
        if (newPage == st.bioPageIndex) return
        st.bioPageIndex = newPage
        appLog("User: ${if (delta < 0) "prev" else "next"} page -> $newPage", "D", st.profile)
        val c = st.lastBioCounts
        layoutBio(st, BioMode.EEG, c[0], c[1], c[2], c[3], c[4], c[5])
    }

    // Mode from DeviceInfo (PPG > EEG > EMG).
    private fun detectBioMode(info: DeviceInfo): BioMode = when {
        info.ppgSampleRate > 0 || info.ppgChannelCount > 0 -> BioMode.PPG
        info.eegChannelCount > 0 -> BioMode.EEG
        info.emgChannelCount > 0 -> BioMode.EMG
        else -> BioMode.NONE
    }

    // Data-driven fallback/repair of one device's bio mode from observed
    // batches.
    private fun maybeReconfigureBio(st: DeviceUiState, d: SensorData) {
        val t = d.dataType
        if (t != DataType.NTF_EMG && t != DataType.NTF_EEG && t != DataType.NTF_ECG &&
            t != DataType.NTF_BRTH && t != DataType.NTF_PPG && t != DataType.NTF_SPO2) return
        synchronized(st.bioChannels) {
            val prev = st.bioChannels.put(t, d.channelCount)
            val desired = when {
                st.bioChannels.containsKey(DataType.NTF_PPG) ||
                    st.bioChannels.containsKey(DataType.NTF_SPO2) -> BioMode.PPG
                st.bioChannels.containsKey(DataType.NTF_EEG) -> BioMode.EEG
                st.bioChannels.containsKey(DataType.NTF_EMG) -> BioMode.EMG
                else -> BioMode.NONE
            }
            val needsRebuild = desired != st.bioMode ||
                (prev != null && prev != d.channelCount) ||
                (desired == BioMode.EEG && (t == DataType.NTF_ECG || t == DataType.NTF_BRTH) &&
                    st.bioFeeds.none { it.dataType == t })
            if (!needsRebuild) return
        }
        val rebuild = Runnable {
            synchronized(st.bioChannels) {
                layoutBio(st,
                    if (st.bioChannels.containsKey(DataType.NTF_PPG) ||
                        st.bioChannels.containsKey(DataType.NTF_SPO2)) BioMode.PPG
                    else if (st.bioChannels.containsKey(DataType.NTF_EEG)) BioMode.EEG
                    else BioMode.EMG,
                    st.bioChannels[DataType.NTF_EEG] ?: 0,
                    st.bioChannels[DataType.NTF_ECG] ?: 0,
                    st.bioChannels[DataType.NTF_BRTH] ?: 0,
                    st.bioChannels[DataType.NTF_PPG] ?: 0,
                    st.bioChannels[DataType.NTF_SPO2] ?: 0,
                    st.bioChannels[DataType.NTF_EMG] ?: 0)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) rebuild.run() else mainHandler.post(rebuild)
    }

    // Back to the mode-less placeholder (per device).
    private fun resetBio(st: DeviceUiState) {
        synchronized(st.bioChannels) { st.bioChannels.clear() }
        showBioPlaceholder(st, "Waiting for data ...")
    }

    // FFT spectra of the current device's IMU waveforms and split bio rows,
    // computed on the worker executor.
    private fun maybeSubmitSpectra() {
        val st = currentState() ?: return
        val now = System.currentTimeMillis()
        val session = st.dataSession
        for (feed in st.spectrumFeeds) submitSpectrum(st, feed, session, now)
        for (feed in st.bioSpectrumFeeds) {
            // Split rows only.
            if (feed.spectrum.visibility == View.VISIBLE) {
                submitSpectrum(st, feed, session, now)
            }
        }
    }

    // One spectrum strip compute, throttled per strip.
    private fun submitSpectrum(st: DeviceUiState, feed: SpectrumFeed, session: Int, now: Long) {
        if (feed.inFlight.get() || now - feed.lastSubmitMs < FFT_UPDATE_INTERVAL_MS) return
        // Skips rings that have not received data yet (rate unknown).
        val snap = feed.view.snapshotForSpectrum() ?: return
        feed.lastSubmitMs = now
        feed.inFlight.set(true)
        try {
            fftExecutor.execute {
                val result = try {
                    SpectrumCompute.compute(snap.channels, snap.rate)
                } catch (t: Throwable) {
                    Log.w(TAG, "spectrum compute failed", t)
                    null
                }
                mainHandler.post {
                    feed.inFlight.set(false)
                    if (session == st.dataSession &&
                        snap.generation == feed.view.ringGeneration) {
                        if (result != null) feed.spectrum.setResult(result.freqs, result.mags)
                        else feed.spectrum.clear()
                    }
                }
            }
        } catch (e: RejectedExecutionException) {
            feed.inFlight.set(false)
        }
    }

    // ---- permissions ---------------------------------------------------------

    private fun requestBlePermissions() {
        val perms = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        } + if (Build.VERSION.SDK_INT <= 28) {
            listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            emptyList()
        }
        val missing = perms.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
        }
    }

    // All-files access for the SDK session logs and the .bin export under
    // /sdcard/Documents/sensorsdklog.
    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) {
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    // ---- scan (startScan + OnScanResultListener) ----------------------------

    private fun toggleScan() {
        if (controller.isScanning) {
            appLog("Stop scan")
            controller.stopScan()
            scanning = false
            scanBtn.text = "Start Scan"
            setStatus("Scan stopped (${devices.size} devices)")
        } else {
            if (!controller.isEnable) {
                appLog("User: start scan rejected (Bluetooth disabled)", "W")
                setStatus("Bluetooth is not enabled")
                return
            }
            appLog("User: start scan")
            scanning = controller.startScan(6000)
            scanBtn.text = if (scanning) "Stop Scan" else "Start Scan"
            setStatus(if (scanning) "Scanning ..." else "Error: start scan failed")
        }
    }

    private fun mergeDevices(found: List<BleDevice>) {
        // New entries insert at their RSSI-sorted position; known entries
        // refresh in place and keep their position.
        val seen = HashSet<String>()
        for (d in found) {
            if (d.mac.isEmpty()) continue
            seen.add(d.mac)
            val idx = sortedDevices.indexOfFirst { it.mac == d.mac }
            if (idx >= 0) {
                sortedDevices[idx] = d
            } else {
                var pos = sortedDevices.indexOfFirst { it.rssi < d.rssi }
                if (pos < 0) pos = sortedDevices.size
                sortedDevices.add(pos, d)
            }
            devices[d.mac] = d
        }
        // Three-round absence eviction; connected devices and replay members
        // stay.
        var evicted = false
        val it = sortedDevices.iterator()
        while (it.hasNext()) {
            val d = it.next()
            if (d.mac in seen) {
                absentRounds[d.mac] = 0
                continue
            }
            val st = synchronized(deviceStates) { deviceStates[d.mac] }
            val exempt = (st != null && !st.isReplay) || d.mac == replayMac ||
                    replayMacs.contains(d.mac)
            if (exempt) {
                absentRounds[d.mac] = 0
                continue
            }
            val rounds = (absentRounds[d.mac] ?: 0) + 1
            if (rounds < 4) {
                absentRounds[d.mac] = rounds
                continue
            }
            it.remove()
            devices.remove(d.mac)
            absentRounds.remove(d.mac)
            evicted = true
            Log.i(TAG, "scan list: evicted ${d.mac} after $rounds absent rounds")
            if (d.mac == currentMac) setCurrentDevice(null)
        }
        if (evicted) updateConnectButton()
        refreshDeviceList()
    }

    // Rebuilds the list rows.
    private fun refreshDeviceList() {
        listAdapter.clear()
        listAdapter.addAll(sortedDevices.map { d ->
            val st = synchronized(deviceStates) { deviceStates[d.mac] }
            val linked = st?.profile?.let {
                val s = it.getDeviceState()
                s == DeviceState.READY || s == DeviceState.CONNECTED || s == DeviceState.CONNECTING
            } == true
            buildString {
                append("rssi=${d.rssi}  ${d.name}  [${d.mac}]")
                if (st != null && st.isReplay) append("  [replay]")
                else if (linked) append("  [connected]")
                if (st?.transferring == true) append("  [streaming]")
                if (d.mac == currentMac) append("  <- showing")
            }
        })
        // States without a scan-list entry (bin replay).
        val scanned = sortedDevices.map { it.mac }.toSet()
        val extra = synchronized(deviceStates) {
            deviceStates.values.filter { it.mac !in scanned }
        }
        listAdapter.addAll(extra.map { st ->
            buildString {
                append("${st.mac}  [replay]")
                if (st.transferring) append("  [streaming]")
                if (st.mac == currentMac) append("  <- showing")
            }
        })
        rowMacs = sortedDevices.map { it.mac } + extra.map { it.mac }
        listAdapter.notifyDataSetChanged()
    }

    // ---- connect / init / stream (all suspend extensions) ----------------------

    // Connects the selected device or, when it is already linked,
    // disconnects just that one.
    private fun toggleConnect() {
        val mac = selectedMac
        if (mac == null) {
            appLog("User: connect rejected (no device selected)", "W")
            return
        }
        val existing = synchronized(deviceStates) { deviceStates[mac] }
        val p0 = existing?.profile
        if (existing != null && !existing.isReplay && p0 != null) {
            val ds = p0.getDeviceState()
            if (ds == DeviceState.READY || ds == DeviceState.CONNECTED
                || ds == DeviceState.CONNECTING) {
                scope.launch {
                    setStatus("Disconnecting $mac ...")
                    connectBtn.isEnabled = false
                    appLog("User: disconnect $mac", "I", p0)
                    p0.disconnect()
                    updateConnectButton()
                }
                return
            }
        }
        connectDevice(mac, restoreParams = false)
    }

    // Connect + init + stream; each step skips itself when already done.
    private fun connectDevice(mac: String, restoreParams: Boolean) {
        // Stop the scan loop before connecting.
        if (scanning) {
            scanning = false
            controller.stopScan()
            scanBtn.text = "Start Scan"
        }
        // No live connect while a replay is running.
        if (replayMac != null || replayMacs.isNotEmpty()) {
            appLog("User: connect rejected (replay running)", "W")
            setStatus("Connect refused while a replay is running")
            return
        }
        connectBtn.isEnabled = false
        scope.launch {
            try {
                val dev = devices[mac]
                setStatus("Connecting ${dev?.name ?: ""} [$mac] ...")
                val prof = controller.requireSensor(mac)
                appLog("User: connect ${dev?.name ?: ""} ($mac)", "I", prof)
                if (prof == null) {
                    appLog("App: failed to create SensorProfile for $mac", "E")
                    setStatus("requireSensor failed")
                    updateConnectButton()
                    return@launch
                }
                // New connections inherit the current Auto Reconnect toggle.
                prof.setAutoReconnect(autoReconnect)
                val kept = synchronized(deviceStates) { deviceStates[mac] }
                val st = if (kept != null && !kept.isReplay && kept.profile === prof) {
                    kept
                } else {
                    registerDeviceState(mac, prof, isReplay = false).also { attachListeners(it) }
                }

                if (!prof.isReady() && !prof.connect()) {
                    appLog("App: failed to connect to ${dev?.name ?: ""} ($mac)", "E", prof)
                    setStatus("Connect failed")
                    removeDeviceState(mac)
                    updateConnectButton()
                    return@launch
                }
                if (!prof.hasInited()) {
                    setStatus("Connected, init ...")
                    // batch 32 samples/channel, 5 s init timeout, battery poll 60 s
                    if (!prof.init(32, 5000, 60000)) {
                        appLog("App: failed to initialize ${dev?.name ?: ""} ($mac)", "E", prof)
                        setStatus("Init failed")
                        removeDeviceState(mac)
                        updateConnectButton()
                        return@launch
                    }
                }
                val info = prof.getDeviceInfo()
                st.info = info
                setStatus("Init ok: $info")
                Log.i(TAG, "init ok[$mac]: name=${info.deviceName} EMG=${info.emgChannelCount}ch@${info.emgSampleRate} " +
                        "EEG=${info.eegChannelCount}ch@${info.eegSampleRate} ECG=${info.ecgChannelCount} " +
                        "BRTH=${info.brthChannelCount} PPG=${info.ppgChannelCount}ch@${info.ppgSampleRate} " +
                        "SPO2=${info.spo2ChannelCount}ch@${info.spo2SampleRate} IMU=${info.imuChannelCount}ch@${info.imuSampleRate}")
                // Session params follow the debug toggles.
                if (debugLogEnabled) {
                    val rc = prof.setParam("DEBUG_LOG_PATH", "True")
                    Log.i(TAG, "setParam DEBUG_LOG_PATH=True -> $rc")
                    prof.log("App: setParam(DEBUG_LOG_PATH, True) -> $rc", "I")
                }
                if (binDataEnabled) {
                    val rc = prof.setParam("DEBUG_BLE_DATA_PATH", "True")
                    Log.i(TAG, "setParam DEBUG_BLE_DATA_PATH=True -> $rc")
                    prof.log("App: setParam(DEBUG_BLE_DATA_PATH, True) -> $rc", "I")
                }
                // Bio page mode from DeviceInfo.
                synchronized(st.bioChannels) {
                    st.bioChannels.clear()
                    if (info.eegChannelCount > 0) st.bioChannels[DataType.NTF_EEG] = info.eegChannelCount
                    if (info.ecgChannelCount > 0) st.bioChannels[DataType.NTF_ECG] = info.ecgChannelCount
                    if (info.brthChannelCount > 0) st.bioChannels[DataType.NTF_BRTH] = info.brthChannelCount
                    if (info.ppgChannelCount > 0) st.bioChannels[DataType.NTF_PPG] = info.ppgChannelCount
                    if (info.spo2ChannelCount > 0) st.bioChannels[DataType.NTF_SPO2] = info.spo2ChannelCount
                    if (info.emgChannelCount > 0) st.bioChannels[DataType.NTF_EMG] = info.emgChannelCount
                }
                val mode = detectBioMode(info)
                if (mode != BioMode.NONE) {
                    layoutBio(st, mode, info.eegChannelCount, info.ecgChannelCount,
                        info.brthChannelCount, info.ppgChannelCount, info.spo2ChannelCount,
                        info.emgChannelCount)
                } else {
                    resetBio(st)
                }
                // The just-connected device becomes the current one.
                setCurrentDevice(mac)

                if (!prof.isDataTransfering() && !prof.startDataNotification()) {
                    appLog("App: failed to start data stream on $mac", "E", prof)
                    setStatus("startDataNotification failed")
                    removeDeviceState(mac)
                    updateConnectButton()
                    return@launch
                }
                // New data session: clear the device's views.
                clearDataViews(st)
                appLog("App: device connected and streaming: ${dev?.name ?: ""} ($mac)", "I", prof)
                setStatus("Streaming $mac ...")
                if (restoreParams) {
                    for ((key, value) in savedParamsByMac[mac].orEmpty()) {
                        val result = prof.setParam(key, value)
                        prof.log("App: restore setParam($key, $value) -> $result", "I")
                    }
                    syncSwitches(prof)
                    syncSampleRateControls(prof)
                }
            } finally {
                updateConnectButton()
            }
        }
    }

    // Auto-reconnect: the app drives the normal connect flow itself.
    private fun pressConnectForAutoReconnect(mac: String, restore: Boolean) {
        val idx = rowMacs.indexOf(mac)
        if (idx >= 0) {
            selectedMac = mac
            deviceListView.setSelection(idx)
            selectedText.text =
                "Selected: ${sortedDevices.getOrNull(idx)?.name ?: "replay"} [$mac]"
            listAdapter.notifyDataSetChanged()
        }
        connectDevice(mac, restoreParams = restore)
    }

    private fun attachListeners(st: DeviceUiState) {
        val p = st.profile ?: return
        val mac = st.mac
        p.setOnStateChangeListener { _, newState ->
            mainHandler.post {
                if (newState == DeviceState.DISCONNECTED) {
                    // Single teardown path for user-initiated and abnormal
                    // drops. The event also fires after a failed connect
                    // already removed the state; skip the log then.
                    if (synchronized(deviceStates) { deviceStates.containsKey(mac) }) {
                        appLog("App: device disconnected, removed from UI: $mac", "I", st.profile)
                    }
                    removeDeviceState(mac)
                    if (currentMac == null) setStatus("Disconnected $mac")
                } else if (mac == currentMac) {
                    setStatus("State -> ${DeviceState.nameOf(newState)}")
                }
            }
        }
        p.setOnAutoReconnectListener { _, hasLastSession, answer ->
            p.log("App: auto reconnect callback received, restore=$hasLastSession", "I")
            mainHandler.post { pressConnectForAutoReconnect(mac, hasLastSession) }
            answer.answer(true)
        }
        p.setOnErrorListener { _, errorMsg ->
            p.log("App: error callback: $errorMsg", "E")
            mainHandler.post { if (mac == currentMac) setStatus("Error: $errorMsg") }
        }
        p.setOnPowerListener { _, power ->
            // Battery stable band: hold the displayed value while a valid
            // reading differs by less than POWER_STABLE_BAND.
            if (power < 0) return@setOnPowerListener
            val prev = st.lastPower
            if (prev >= 0 && kotlin.math.abs(power - prev) < POWER_STABLE_BAND) {
                return@setOnPowerListener
            }
            st.lastPower = power
            mainHandler.post { if (mac == currentMac) batteryText.text = "Battery: $power%" }
        }
        p.setOnDeviceInfoUpdateListener { _, info ->
            // Fired after the cached DeviceInfo changed.
            st.info = info
            seedSampleRateCurrent(st, info)
            mainHandler.post {
                syncSampleRateBuffers(st, info)
                if (mac == currentMac) {
                    updateLinkInfo(info)
                    applyCheckedFromCache(st)
                }
            }
        }
        p.setOnDataTransferStateChangeListener { _, isTransferring ->
            // Stream on/off signal.
            st.transferring = isTransferring
            appLog("App: data stream ${if (isTransferring) "ON" else "OFF"} $mac", "I", p)
            mainHandler.post { refreshDeviceList(); updateConnectButton() }
        }
        p.setOnDataListener { _, dataList -> onSensorData(mac, dataList) }
    }

    // Data listener entry for live and replay profiles alike: enqueue only.
    private fun onSensorData(mac: String, dataList: List<SensorData>) {
        if (cloneData) {
            for (d in dataList) enqueueData(QueuedBatch(mac, d.clone()))
        } else {
            for (d in dataList) enqueueData(QueuedBatch(mac, d))
        }
    }

    // Data-queue sink: enqueue only; a full backlog drops the oldest batch.
    private fun enqueueData(q: QueuedBatch) {
        while (!dataQueue.offer(q)) {
            dataQueue.poll()
        }
    }

    // Starts the data worker draining the queue through processBatches.
    private fun startDataWorker() {
        dataWorker = Thread({
            while (!dataWorkerStop.get()) {
                val q = try {
                    dataQueue.take()
                } catch (e: InterruptedException) {
                    break
                }
                processBatches(q.mac, listOf(q.data))
            }
        }, "SensorDataWorker").apply {
            isDaemon = true
            start()
        }
    }

    private fun updateConnectButton() {
        val st = selectedMac?.let { synchronized(deviceStates) { deviceStates[it] } }
        val p = st?.profile
        val ds = p?.getDeviceState() ?: DeviceState.DISCONNECTED
        val linked = st != null && !st.isReplay && p != null &&
                (ds == DeviceState.READY || ds == DeviceState.CONNECTED || ds == DeviceState.CONNECTING)
        connectBtn.text = if (linked) "Disconnect" else "Connect"
        // No live connect while a replay is running.
        val replaying = replayMac != null || replayMacs.isNotEmpty()
        connectBtn.isEnabled =
            selectedMac != null && (st == null || !st.isReplay) && !replaying
        val states = synchronized(deviceStates) { deviceStates.values.toList() }
        val anyStreaming = states.any { !it.isReplay && it.transferring }
        multiBtn.text = if (anyStreaming) "Multi Stop" else "Multi Start"
        multiBtn.isEnabled = !multiOpActive && !replaying && states.any { !it.isReplay }
    }

    // ---- synchronized multi-device stream start/stop -------------------------

    // Multi Start/Stop toggle: dispatches by the any-streaming state.
    private fun onMultiSyncClicked() {
        val anyStreaming = synchronized(deviceStates) {
            deviceStates.values.any { !it.isReplay && it.transferring }
        }
        if (anyStreaming) onMultiStop() else onMultiStart()
    }

    // Multi Start button.
    private fun onMultiStart() {
        val sensors = synchronized(deviceStates) {
            deviceStates.values.filter {
                !it.isReplay && it.profile?.let { pr -> pr.isReady() && pr.hasInited() } == true
            }.map { it.profile!! }
        }
        if (sensors.isEmpty()) {
            appLog("User: multi start rejected (no connected device)", "W")
            setStatus("No connected device to sync-start")
            return
        }
        appLog("User: multi start on ${sensors.size} device(s)")
        multiOpActive = true
        multiBtn.isEnabled = false
        val transferring = sensors.filter { it.isDataTransfering() }
        if (transferring.isNotEmpty()) {
            controller.multiStopDataNotification(transferring, 10000) { results, _ ->
                val stopFailed = results.filterValues { !it }.keys
                if (stopFailed.isNotEmpty()) {
                    val macs = stopFailed.joinToString(", ")
                    appLog("App: multi stop failed on: $macs", "W")
                    mainHandler.post {
                        setStatus("Multi stop failed on: $macs")
                        multiOpActive = false
                        updateConnectButton()
                    }
                    return@multiStopDataNotification
                }
                doMultiStart(sensors)
            }
            return
        }
        doMultiStart(sensors)
    }

    // Same model name on all devices -> default sync params; mixed/unknown
    // models -> relaxed params.
    private fun doMultiStart(sensors: List<SensorProfile>) {
        val names = sensors.map { it.getDeviceInfo()?.modelName }.toSet()
        val sameModel = names.size == 1 && null !in names
        val timeoutMs = if (sameModel) 30000 else 60000
        val maxDelayDispersionMs = if (sameModel) 5 else -1
        val maxAttempts = if (sameModel) 3 else 5
        controller.multiStartDataNotification(sensors, timeoutMs, maxDelayDispersionMs,
                maxAttempts) { results, errors ->
            mainHandler.post {
                reportMultiResult("start", "started", results, errors)
                multiOpActive = false
                updateConnectButton()
            }
        }
    }

    // Multi-device stream stop flow.
    private fun onMultiStop() {
        val sensors = synchronized(deviceStates) {
            deviceStates.values.filter {
                !it.isReplay && it.profile?.isDataTransfering() == true
            }.map { it.profile!! }
        }
        if (sensors.isEmpty()) {
            appLog("User: multi stop rejected (no streaming device)", "W")
            setStatus("No streaming device to sync-stop")
            return
        }
        appLog("User: multi stop on ${sensors.size} device(s)")
        multiOpActive = true
        multiBtn.isEnabled = false
        controller.multiStopDataNotification(sensors, 10000) { results, errors ->
            mainHandler.post {
                reportMultiResult("stop", "stopped", results, errors)
                multiOpActive = false
                updateConnectButton()
            }
        }
    }

    // Status + per-mac log lines for a multi start/stop result.
    private fun reportMultiResult(action: String, past: String,
                                  results: Map<String, Boolean>,
                                  errors: Map<String, String>) {
        val failed = results.filterValues { !it }.keys
        for (mac in failed) {
            val pr = synchronized(deviceStates) { deviceStates[mac] }?.profile
            appLog("App: multi $action failed[$mac]: ${errors[mac] ?: ""}", "W", pr)
        }
        if (failed.isEmpty()) {
            appLog("App: multi $action OK: ${results.size} device(s) $past")
            setStatus("Multi $action: ${results.size} device(s) $past")
        } else {
            setStatus("Multi $action failed on: ${failed.joinToString(", ")}")
        }
    }

    // ---- NTF / FILTER switches (suspend setParam / getParam) -----------------

    // Records a successful setParam for the auto-reconnect restore.
    private fun recordSavedParam(mac: String, key: String, value: String, result: String) {
        if (result.startsWith("Error")) return
        savedParamsByMac.getOrPut(mac) { mutableMapOf() }[key] = value
    }

    private fun onNtfToggled(key: String, on: Boolean) {
        if (suppressNtfCallbacks) return
        val st = currentState()
        val p = st?.profile ?: return
        if (st.isReplay) return
        scope.launch {
            val result = p.setParam(key, if (on) "ON" else "OFF")
            setStatus("setParam $key=${if (on) "ON" else "OFF"} -> $result")
            appLog("User: setParam($key, ${if (on) "ON" else "OFF"}) -> $result", "I", p)
            recordSavedParam(st.mac, key, if (on) "ON" else "OFF", result)
            if (!result.startsWith("Error")) clearDataViews(st)
            // Re-query all switches after every toggle.
            syncSwitches(p)
        }
    }

    private fun onFilterToggled(key: String, on: Boolean) {
        if (suppressNtfCallbacks) return
        val st = currentState()
        val p = st?.profile ?: return
        if (st.isReplay) return
        scope.launch {
            val result = p.setParam(key, if (on) "ON" else "OFF")
            setStatus("setParam $key=${if (on) "ON" else "OFF"} -> $result")
            appLog("User: setParam($key, ${if (on) "ON" else "OFF"}) -> $result", "I", p)
            recordSavedParam(st.mac, key, if (on) "ON" else "OFF", result)
            if (!result.startsWith("Error")) clearDataViews(st)
            syncSwitches(p)
        }
    }

    // Parses a "K|V|K|V" aggregate answer; empty map on an error string.
    private fun parseAggregate(result: String): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        if (result.startsWith("Error")) return values
        val parts = result.split("|")
        var i = 0
        while (i + 1 < parts.size) {
            values[parts[i]] = parts[i + 1]
            i += 2
        }
        return values
    }

    // Reads back the NTF and FILTER aggregates ("K|V|K|V") and syncs the
    // boxes.
    private fun syncSwitches(p: SensorProfile) {
        scope.launch {
            val ntfResult = p.getParam("NTF")
            val filterResult = p.getParam("FILTER")
            val info = p.getDeviceInfo()
            val channelMap = mapOf(
                "NTF_EEG" to info.eegChannelCount,
                "NTF_EMG" to info.emgChannelCount,
                // Gesture depends on EMG sampling, so it shares the EMG count.
                "NTF_GEST" to info.emgChannelCount,
                "NTF_PPG" to info.ppgChannelCount,
                "NTF_SPO2" to info.spo2ChannelCount,
                "NTF_IMU" to maxOf(info.accChannelCount, info.gyroChannelCount),
            )
            val ntfValues = parseAggregate(ntfResult)
            val filterValues = parseAggregate(filterResult)
            val hasState = ntfValues.isNotEmpty()
            val hasFilter = !filterResult.startsWith("Error") && filterResult.isNotEmpty()
            suppressNtfCallbacks = true
            for ((key, cb) in ntfChecks) {
                val supported = (channelMap[key] ?: 0) > 0
                cb.visibility = if (supported || !hasState) View.VISIBLE else View.GONE
                cb.isEnabled = supported
                cb.isChecked = supported && ntfValues[key] == "ON"
            }
            for ((key, cb) in filterChecks) {
                cb.isEnabled = hasFilter
                cb.isChecked = hasFilter && filterValues[key] == "ON"
            }
            suppressNtfCallbacks = false
            Log.i(TAG, "ntf readback: \"$ntfResult\" -> " + ntfChecks.map { (k, cb) ->
                "$k=${if (cb.isEnabled) "en" else "dis"}/${if (cb.isChecked) "on" else "off"}" +
                        "${if (cb.visibility != View.VISIBLE) "/gone" else ""}"
            } + " | filter readback: \"$filterResult\" -> " + filterChecks.map { (k, cb) ->
                "$k=${if (cb.isEnabled) "en" else "dis"}/${if (cb.isChecked) "on" else "off"}"
            })
        }
    }

    // ---- debug log / bin data / auto reconnect toggles ------------------------

    // Applies the session log path + DEBUG_ENABLED=True.
    private fun applySdkDebugLog() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val version = controller.version.replace('.', '_')
        sessionLogDir = "/sdcard/Documents/sensorsdklog/${stamp}_$version"
        controller.setParam("LOG_PATH", sessionLogDir!!)
        controller.setParam("DEBUG_ENABLED", "True")
        Log.i(TAG, "LOG_PATH -> $sessionLogDir")
    }

    private fun defaultReplayPath(): String {
        val dir = sessionLogDir ?: getExternalFilesDir(null)?.absolutePath ?: "/sdcard"
        return "$dir/test.bin"
    }

    private fun pickReplayFile() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    initialReplayDirUri()?.let {
                        putExtra(DocumentsContract.EXTRA_INITIAL_URI, it)
                    }
                }, REQUEST_PICK_REPLAY_FILE)
        } catch (e: Exception) {
            setStatus("File picker unavailable: ${e.message}")
        }
    }

    // Initial picker location: the session debug log dir.
    private fun initialReplayDirUri(): Uri? {
        val dir = sessionLogDir ?: getExternalFilesDir(null)?.absolutePath ?: return null
        val rel = dir.removePrefix("/sdcard/")
        if (rel == dir) return null
        return try {
            val tree = DocumentsContract.buildTreeDocumentUri(
                "com.android.externalstorage.documents", "primary:$rel")
            DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree))
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_REPLAY_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val path = importReplayFile(uri)
        if (path == null) {
            setStatus("Unsupported file: $uri")
            return
        }
        replayPathEdit.setText(path)
        startReplay(path, true)
    }

    // "primary:Download/x.bin" -> "/sdcard/Download/x.bin" when reachable,
    // otherwise the content is copied into the app cache dir.
    private fun importReplayFile(uri: Uri): String? {
        val docId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (e: Exception) {
            null
        }
        if (docId != null) {
            val split = docId.split(':', limit = 2)
            if (split.size == 2) {
                val path = if (split[0].equals("primary", ignoreCase = true)) {
                    "/sdcard/${split[1]}"
                } else {
                    "/storage/${split[0]}/${split[1]}"
                }
                if (File(path).exists()) return path
            }
        }
        return try {
            val name = (uri.lastPathSegment ?: "picked.bin").substringAfterLast('/')
                .substringAfterLast(':').ifEmpty { "picked.bin" }
            val out = File(cacheDir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            } ?: return null
            out.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    // Live (non-replay) states whose link is Ready.
    private fun readyProfiles(): List<SensorProfile> {
        val states = synchronized(deviceStates) { deviceStates.values.toList() }
        return states.mapNotNull { st ->
            val p = st.profile
            if (!st.isReplay && p != null && p.getDeviceState() == DeviceState.READY) p else null
        }
    }

    private fun onDebugLogToggled(checked: Boolean) {
        debugLogEnabled = checked
        appLog("User: SDK debug log ${if (checked) "ON" else "OFF"}")
        if (checked) {
            applySdkDebugLog()
        } else {
            controller.setParam("DEBUG_ENABLED", "False")
        }
        val value = if (checked) "True" else "False"
        for (p in readyProfiles()) {
            scope.launch {
                val result = p.setParam("DEBUG_LOG_PATH", value)
                p.log("App: setParam(DEBUG_LOG_PATH, $value) -> $result", "I")
            }
        }
    }

    private fun onBinDataToggled(checked: Boolean) {
        binDataEnabled = checked
        appLog("User: data debug log ${if (checked) "ON" else "OFF"}")
        val value = if (checked) "True" else "False"
        for (p in readyProfiles()) {
            scope.launch {
                val result = p.setParam("DEBUG_BLE_DATA_PATH", value)
                p.log("App: setParam(DEBUG_BLE_DATA_PATH, $value) -> $result", "I")
            }
        }
    }

    private fun onAutoReconnectToggled(checked: Boolean) {
        autoReconnect = checked
        appLog("User: auto reconnect ${if (checked) "ON" else "OFF"}")
        val states = synchronized(deviceStates) { deviceStates.values.toList() }
        for (st in states) {
            if (!st.isReplay) st.profile?.setAutoReconnect(checked)
        }
    }

    // ---- sample rate controls (suspend setParam / getParam) ----------------

    private fun onSampleRateSelected(key: String, rate: Int) {
        val st = currentState()
        val p = st?.profile ?: return
        if (st.isReplay) return
        scope.launch {
            val result = p.setParam(key, rate.toString())
            setStatus("setParam $key=$rate -> $result")
            appLog("User: setParam($key, $rate) -> $result", "I", p)
            recordSavedParam(st.mac, key, rate.toString(), result)
            if (!result.startsWith("Error")) clearDataViews(st)
            // Re-query so the buttons track the device state after the switch.
            syncSampleRateControls(p)
        }
    }

    // Reads back the sample rate options and the bound rates, caches them
    // per device and syncs the radio rows of the current device.
    private fun syncSampleRateControls(p: SensorProfile) {
        scope.launch {
            val read = LinkedHashMap<String, Pair<List<Int>, Int>>()
            for (c in sampleRateControls) {
                val options = p.getParam("${c.key}_LIST")
                    .split("|").mapNotNull { it.toIntOrNull() }
                val current = p.getParam(c.key).toIntOrNull() ?: 0
                read[c.key] = options to current
            }
            val st = synchronized(deviceStates) {
                deviceStates.values.firstOrNull { it.profile === p }
            }
            if (st != null) st.sampleRateStates.putAll(read)
            if (st != null && st.mac == currentMac) {
                for (c in sampleRateControls) {
                    val (options, current) = read[c.key] ?: continue
                    c.sync(options, current)
                }
            }
        }
    }

    // Current rates from DeviceInfo into the per-device cache (> 0 only).
    private fun seedSampleRateCurrent(st: DeviceUiState, info: DeviceInfo) {
        seedRate(st, "EEG_SAMPLE_RATE", info.eegSampleRate)
        seedRate(st, "EMG_SAMPLE_RATE", info.emgSampleRate)
        seedRate(st, "IMU_SAMPLE_RATE", info.accSampleRate)
        seedRate(st, "PPG_SAMPLE_RATE", info.ppgSampleRate)
    }

    private fun seedRate(st: DeviceUiState, key: String, rate: Float) {
        if (rate <= 0) return
        val cached = st.sampleRateStates[key]
        st.sampleRateStates[key] = (cached?.first ?: emptyList()) to rate.toInt()
    }

    // Checked state of every row from the per-device cache.
    private fun applyCheckedFromCache(st: DeviceUiState) {
        for (c in sampleRateControls) {
            c.check(st.sampleRateStates[c.key]?.second ?: 0)
        }
    }

    // Replay path: checked state seeded from the capture's DeviceInfo.
    private fun syncSampleRateCheckedFromInfo(st: DeviceUiState, info: DeviceInfo?) {
        if (info != null) seedSampleRateCurrent(st, info)
        applyCheckedFromCache(st)
    }

    // Link/MTU display from DeviceInfo; "--" for values the link did not report.
    private fun updateLinkInfo(info: DeviceInfo) {
        linkText.text =
            if (info.peripheralLatency < 0 || info.connectionIntervalMs <= 0) "Link: --"
            else "Link: ${info.connectionIntervalMs}ms / " +
                    "latency ${info.peripheralLatency} / " +
                    "timeout ${info.supervisionTimeoutMs}ms"
        mtuText.text = if (info.mtuSize <= 0) "MTU: --" else "MTU: ${info.mtuSize}"
    }

    // Sample-rate change handling for one device: rebuilds every waveform
    // ring whose nominal rate changed.
    private fun syncSampleRateBuffers(st: DeviceUiState, info: DeviceInfo) {
        val eegWindow = if (st.bioMode == BioMode.PPG) 5.0 else 1.0
        for (f in st.bioFeeds) {
            when (f.dataType) {
                DataType.NTF_EEG -> f.view.rebuildForRate(info.eegSampleRate, eegWindow)
                DataType.NTF_ECG -> f.view.rebuildForRate(info.ecgSampleRate, 1.0)
            }
        }
        st.accView.rebuildForRate(info.accSampleRate, 5.0)
        st.gyroView.rebuildForRate(info.gyroSampleRate, 5.0)
        st.eulerView.rebuildForRate(info.eulerSampleRate, 5.0)
        st.quatView.rebuildForRate(info.quatSampleRate, 5.0)
    }

    // ---- bin replay -----------------------------------------------------------

    // Multi Replay Bin button: the path field is a ';'-separated path list.
    private fun onMultiReplay() {
        val paths = replayPathEdit.text.toString()
            .split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (paths.size < 2) {
            setStatus("Multi replay needs multiple ';'-separated bin paths")
            return
        }
        startGroupReplay(paths, true)
    }

    // Starts a realtime (or full-speed) bin replay. The replay profile gets
    // its own DeviceUiState (keyed by the bin's mac) and becomes the current
    // device while live devices keep streaming in the background.
    private fun startReplay(path: String, realtime: Boolean) {
        if (path.isEmpty()) {
            setStatus("Replay failed: empty bin path")
            return
        }
        if (!File(path).exists()) {
            setStatus("Replay failed: file not found: $path")
            return
        }
        if (replayMac != null || replayMacs.isNotEmpty()) {
            setStatus("Stop the running replay first")
            return
        }
        if (synchronized(deviceStates) { deviceStates.values.any { !it.isReplay } }) {
            appLog("User: replay rejected (live devices connected)", "W")
            setStatus("Replay refused while devices are connected")
            return
        }
        appLog("User: replay bin file: $path")
        val info = controller.getBinFileInfo(path)
        if (info == null) {
            appLog("App: invalid bin file (no config record): $path", "W")
        }
        val mac = info?.mac?.takeIf { it.isNotEmpty() } ?: "REPLAY"
        val p = controller.replayBinFile(path, mac, realtime, 5000)
        if (p == null) {
            setStatus("Replay failed: replayBinFile returned null ($path)")
            return
        }
        replayProfile = p
        replayMac = mac
        replayPaused = false
        pauseReplayBtn.isEnabled = true
        pauseReplayBtn.text = "Pause Replay"
        setupReplayMember(mac, p, info)
        setCurrentDevice(mac)
        updateConnectButton()
        Log.i(TAG, "replay started: path=$path mac=$mac realtime=$realtime info=$info")
        setStatus("Replaying $path (mac=$mac, realtime=$realtime) ...")
    }

    // Starts a synchronized replay of several bins on a shared clock. Each
    // member gets the same DeviceUiState / [Replay] row setup as a single
    // replay; the first started member becomes the current device.
    private fun startGroupReplay(paths: List<String>, realtime: Boolean) {
        if (replayMac != null || replayMacs.isNotEmpty()) {
            setStatus("Stop the running replay first")
            return
        }
        if (synchronized(deviceStates) { deviceStates.values.any { !it.isReplay } }) {
            appLog("User: replay rejected (live devices connected)", "W")
            setStatus("Replay refused while devices are connected")
            return
        }
        appLog("User: replay ${paths.size} bin files: ${paths.joinToString("; ")}")
        val infos = ArrayList<BinFileInfo?>()
        val macs = ArrayList<String>()
        for (path in paths) {
            if (!File(path).exists()) {
                setStatus("Replay failed: file not found: $path")
                return
            }
            val info = controller.getBinFileInfo(path)
            if (info == null) {
                appLog("App: invalid bin file (no config record): $path", "W")
            }
            infos.add(info)
            macs.add(info?.mac?.takeIf { it.isNotEmpty() } ?: "REPLAY")
        }
        val profiles = controller.multiReplayBinFile(paths, macs, realtime, 5000)
        for (i in paths.indices) {
            val p = profiles[i]
            if (p == null) {
                appLog("App: replay failed to start: ${paths[i]} (mac=${macs[i]})", "W")
                continue
            }
            replayMacs.add(macs[i])
            setupReplayMember(macs[i], p, infos[i])
        }
        if (replayMacs.isEmpty()) {
            setStatus("Replay failed: multiReplayBinFile started no member")
            return
        }
        replayPaused = false
        pauseReplayBtn.isEnabled = true
        pauseReplayBtn.text = "Pause Replay"
        setCurrentDevice(replayMacs.first())
        updateConnectButton()
        Log.i(TAG, "group replay started: members=${replayMacs.size} realtime=$realtime")
        setStatus("Replaying ${replayMacs.size} bin files (realtime=$realtime) ...")
    }

    // Registers one replay profile (single replay or group member): its
    // DeviceUiState / [Replay] row and the replay listeners.
    private fun setupReplayMember(mac: String, p: SensorProfile, info: BinFileInfo?) {
        val st = registerDeviceState(mac, p, isReplay = true)
        st.info = info?.deviceInfo
        clearDataViews(st)
        p.setOnDataListener { _, dataList -> onSensorData(mac, dataList) }
        p.setOnErrorListener { _, errorMsg ->
            p.log("App: error callback: $errorMsg", "E")
            mainHandler.post { if (mac == currentMac) setStatus("Replay: $errorMsg") }
        }
        p.setOnDataTransferStateChangeListener { _, isTransferring ->
            // Stream on/off signal; replay EOF arrives as the OFF push (a
            // valid bin always pairs the ON from loadReplayConfig with the
            // OFF from endReplay).
            st.transferring = isTransferring
            appLog("App: data stream ${if (isTransferring) "ON" else "OFF"} $mac", "I", p)
            mainHandler.post {
                refreshDeviceList()
                if (!isTransferring) {
                    Log.i(TAG, "replay finished: batches=${st.batches} " +
                            "samples=${st.samples} lostPkgs=${st.lostPackages}")
                    appLog("App: replay done: Replay finished (${st.batches} batches, ${st.samples} samples)",
                        "I", st.profile)
                    setStatus("Replay finished (${st.batches} batches, ${st.samples} samples)")
                    replayMacs.remove(mac)
                    if (replayMac == mac) {
                        replayProfile = null
                        replayMac = null
                    }
                    if (replayMac == null && replayMacs.isEmpty()) {
                        replayPaused = false
                        pauseReplayBtn.isEnabled = false
                    }
                    removeDeviceState(mac)
                }
            }
        }
        p.setOnDeviceInfoUpdateListener { _, updated ->
            // A config switch mid-capture re-rates the streams.
            Log.i(TAG, "replay device info update: eeg=${updated.eegSampleRate} " +
                    "ecg=${updated.ecgSampleRate} imu=${updated.imuSampleRate}")
            st.info = updated
            mainHandler.post {
                syncSampleRateBuffers(st, updated)
                if (mac == currentMac) {
                    updateLinkInfo(updated)
                    syncSampleRateCheckedFromInfo(st, updated)
                }
            }
        }
    }

    private fun stopReplay() {
        val macs = mutableListOf<String>()
        replayMac?.let { macs.add(it) }
        macs.addAll(replayMacs)
        if (macs.isEmpty()) {
            setStatus("No replay running")
            return
        }
        val rp = replayProfile
        replayProfile = null
        replayMac = null
        replayMacs.clear()
        replayPaused = false
        pauseReplayBtn.isEnabled = false
        // Keep the join off the UI thread.
        scope.launch(Dispatchers.IO) {
            var result = "OK"
            for (m in macs) {
                val r = controller.stopBinReplay(m)
                if (r != "OK") result = r
                val member = synchronized(deviceStates) { deviceStates[m] }?.profile
                appLog("User: stop replay -> $r", if (r == "OK") "I" else "W", member ?: rp)
                Log.i(TAG, "stopBinReplay($m) -> $r")
            }
            withContext(Dispatchers.Main) {
                setStatus("stopBinReplay -> $result")
                updateConnectButton()
            }
        }
    }

    // Pause/resume the running replay (one call covers a whole group).
    private fun onReplayPauseResume() {
        val mac = replayMac ?: replayMacs.firstOrNull() ?: return
        val action = if (replayPaused) "resume" else "pause"
        val result = if (replayPaused) controller.resumeBinReplay(mac)
                     else controller.pauseBinReplay(mac)
        appLog("User: $action replay -> $result", if (result == "OK") "I" else "W",
               replayProfile ?: synchronized(deviceStates) { deviceStates[mac] }?.profile)
        if (result != "OK") {
            setStatus("Replay pause/resume failed: $result")
            return
        }
        replayPaused = !replayPaused
        pauseReplayBtn.text = if (replayPaused) "Resume Replay" else "Pause Replay"
        setStatus(if (replayPaused) "Replay paused" else "Replaying ...")
    }

    // Parses the bin file in the path field to CSV.
    private fun onAnalyzeBin() {
        if (analyzing) return
        val path = replayPathEdit.text.toString().trim()
        if (path.isEmpty()) {
            setStatus("Analyze failed: empty bin path")
            return
        }
        if (!File(path).exists()) {
            setStatus("Analyze failed: file not found: $path")
            return
        }
        appLog("User: analyze bin file: $path")
        analyzing = true
        analyzeBtn.isEnabled = false
        setStatus("Analyzing: ${File(path).name} ...")
        val csv = if (path.endsWith(".bin", ignoreCase = true)) {
            path.dropLast(4) + ".csv"
        } else {
            "$path.csv"
        }
        scope.launch {
            val result = controller.parseBinToCsvAsync(path, csv)
            analyzing = false
            analyzeBtn.isEnabled = true
            if (result.startsWith("Error")) {
                appLog("App: analyze failed: $result", "E")
                setStatus("Analyze failed: $result")
            } else {
                appLog("App: CSV saved: $result")
                setStatus("CSV saved: $result")
            }
        }
    }

    // ---- data processing ---------------------------------------------------------

    // Feeds one device's batch list into its views.
    private fun processBatches(mac: String, dataList: List<SensorData>) {
        val st = synchronized(deviceStates) { deviceStates[mac] } ?: return
        for (d in dataList) {
            st.batches++
            st.samples += d.channelCount.toLong() * d.sampleCount
            st.lostPackages += d.lostPackageCount
            // Probe the batch once up front.
            val fresh = d.isDataValid()
            // Per-type rate/loss bookkeeping.
            accountBatch(st, d, fresh)
            if (d.dataType == DataType.NTF_IMU) accountImuSegments(st, d, fresh)
            // One-time per-type probe: dump metadata and the first sample.
            if (st.diagTypes.add(d.dataType)) {
                val s = d.getChannelSample(0, 0)
                Log.i(TAG, "diag[$mac] type=${d.dataType} ch=${d.channelCount} n=${d.sampleCount} " +
                        "mask=${d.channelMask} startIdx=${d.startSampleIndex} rate=${d.sampleRate} " +
                        "fresh(0,0)=${d.isDataValid()} | " +
                        "sample(0,0)=" + (if (s != null) "idx=${s.sampleIndex} data=${s.data}" else "null"))
            }
            // Bio page: auto mode routing (EMG/EEG/PPG).
            maybeReconfigureBio(st, d)
            // Bio slots bound to this stream; bio types pass through the
            // device's Live Filter before entering the ring.
            val feeds = st.bioFeeds
            for (f in feeds) {
                if (f.dataType != d.dataType || f.channel >= d.channelCount) continue
                f.view.appendBatch(d, f.channel, 1) { ch, vals ->
                    st.liveFilter.apply(d.dataType, ch, vals, d.sampleRate)
                }
            }
            updateImpedances(st, d, fresh)
            when (d.dataType) {
                DataType.NTF_IMU -> {
                    // Aggregate stream: acc 0-2 / gyro 3-5 / euler 6-8 /
                    // quat 9-12.
                    st.accView.appendBatch(d, 0, 3)
                    st.gyroView.appendBatch(d, 3, 3)
                    if (d.channelCount >= 13) {
                        st.eulerView.appendBatch(d, 6, 3)
                        st.quatView.appendBatch(d, 9, 4)
                        feedCube(st, d, 9)
                    }
                }
                DataType.NTF_ACC -> st.accView.appendBatch(d, 0, 3)
                DataType.NTF_GYRO -> st.gyroView.appendBatch(d, 0, 3)
                DataType.NTF_EULER -> st.eulerView.appendBatch(d, 0, 3)
                DataType.NTF_QUATERNION -> {
                    st.quatView.appendBatch(d, 0, 4) // ch 0-3 = w,x,y,z
                    feedCube(st, d, 0)
                }
                DataType.NTF_GEST -> {
                    // Gesture box: the last sample carries the current data.
                    val n = d.sampleCount
                    if (fresh && d.isChannelEnabled(0) && n > 0) {
                        val s = d.getChannelSample(0, n - 1)
                        if (s != null) {
                            st.gesture = s.data.toInt()
                            st.rawGesture = s.rawData
                            st.possibility = s.impedance.toInt()
                            st.strength = s.saturation.toInt()
                        }
                    }
                }
                else -> {} // impedance/ADS/MAG streams: counters only
            }
        }
        // Throttled per-batch logcat heartbeat.
        val now = System.currentTimeMillis()
        if (now - st.lastLogMs >= 1000) {
            st.lastLogMs = now
            Log.i(TAG, "data[$mac]: batches=${st.batches} samples=${st.samples} lostPkgs=${st.lostPackages}")
        }
    }

    // Per-type stats bookkeeping for one batch.
    private fun accountBatch(st: DeviceUiState, d: SensorData, fresh: Boolean) {
        synchronized(st.rateLock) {
            if (d.lostPackageCount > 0) {
                st.lostCounts[d.dataType] = d.lostPackageCount.toLong()
            }
            st.typeBatches[d.dataType] = (st.typeBatches[d.dataType] ?: 0L) + 1
            if (d.channelCount > 0 && d.sampleCount > 0) {
                var valid = 0L
                for (i in 0 until d.sampleCount) {
                    if (fresh && d.isChannelEnabled(0) && !d.isLost(0, i)) valid++
                }
                if (valid > 0) {
                    st.rateCounts[d.dataType] = (st.rateCounts[d.dataType] ?: 0L) + valid
                }
            }
            if (d.sampleRate > 0) st.nominalRates[d.dataType] = d.sampleRate
            if (d.channelCount > 0) st.nominalChannels[d.dataType] = d.channelCount
            if (d.startTimeSec > 0) st.streamStartTimeSec = d.startTimeSec
            if (d.delay > 0) st.streamDelayMs = d.delay
        }
    }

    // Per-segment rate bookkeeping of an NTF_IMU aggregate batch (acc 0-2 /
    // gyro 3-5 / euler 6-8 / quat 9-12).
    private fun accountImuSegments(st: DeviceUiState, d: SensorData, fresh: Boolean) {
        val segs = arrayOf(
            intArrayOf(DataType.NTF_ACC, 0, 3),
            intArrayOf(DataType.NTF_GYRO, 3, 3),
            intArrayOf(DataType.NTF_EULER, 6, 3),
            intArrayOf(DataType.NTF_QUATERNION, 9, 4),
        )
        synchronized(st.rateLock) {
            for (seg in segs) {
                if (d.channelCount < seg[1] + seg[2]) continue
                var valid = 0L
                for (i in 0 until d.sampleCount) {
                    if (fresh && d.isChannelEnabled(0) && !d.isLost(seg[1], i)) valid++
                }
                if (valid > 0) {
                    st.rateCounts[seg[0]] = (st.rateCounts[seg[0]] ?: 0L) + valid
                }
                if (d.sampleRate > 0) st.nominalRates[seg[0]] = d.sampleRate
                st.nominalChannels[seg[0]] = seg[2]
            }
        }
    }

    // Latest impedance per bio channel: the last sample of the batch is the
    // newest.
    private fun updateImpedances(st: DeviceUiState, d: SensorData, fresh: Boolean) {
        val kind = when (d.dataType) {
            DataType.NTF_EMG -> IMP_EMG
            DataType.NTF_EEG -> IMP_EEG
            DataType.NTF_ECG -> IMP_ECG
            DataType.NTF_BRTH -> IMP_BRTH
            else -> return
        }
        if (d.sampleCount <= 0) return
        val arr = st.impedances[kind]
        synchronized(st.impLock) {
            for (ch in 0 until d.channelCount) {
                if (!fresh || !d.isChannelEnabled(ch)) continue
                while (arr.size <= ch) arr.add(-1f)
                arr[ch] = d.getImpedance(ch, d.sampleCount - 1)
            }
        }
    }

    // Impedance side texts of one device's bio slots.
    private fun refreshBioSideTexts(st: DeviceUiState) {
        for (f in st.bioFeeds) {
            if (f.impKind < 0) continue
            val v = synchronized(st.impLock) {
                st.impedances[f.impKind].getOrNull(f.impChannel) ?: -1f
            }
            if (v < 0f) continue
            val kOhm = v / 1000f
            f.view.sideColor = when {
                kOhm <= 500f -> Color.rgb(60, 200, 60)
                kOhm <= 999f -> Color.rgb(230, 160, 40)
                else -> Color.rgb(220, 60, 60)
            }
            f.view.sideText = String.format(Locale.US, "%.2f KOhm", kOhm)
        }
    }

    // Latest sample of a 4-channel quaternion segment drives the cube.
    private fun feedCube(st: DeviceUiState, d: SensorData, quatChannelOffset: Int) {
        val last = d.sampleCount - 1
        if (last < 0) return
        // Probe the batch once; skip the pose update on failure.
        if (!d.isDataValid()) return
        st.cubeView.setQuaternion(
            d.getData(quatChannelOffset + 0, last).toDouble(),
            d.getData(quatChannelOffset + 1, last).toDouble(),
            d.getData(quatChannelOffset + 2, last).toDouble(),
            d.getData(quatChannelOffset + 3, last).toDouble()
        )
    }

    private fun updateStats() {
        val st = currentState()
        if (st == null) {
            statsText.text = "Batches: 0 (lost pkgs: 0)"
            rateText.text = ""
            gestureText.text = GESTURE_EMPTY_TEXT
            return
        }
        // Per-type batch/loss lines.
        val sb = StringBuilder("Batches: ${st.batches} (lost pkgs: ${st.lostPackages})")
        synchronized(st.rateLock) {
            for ((type, name) in TYPE_DISPLAY_ORDER) {
                val b = st.typeBatches[type] ?: continue
                sb.append("\n$name: $b batches, lost ${st.lostCounts[type] ?: 0L}")
            }
        }
        statsText.text = sb.toString()
        rateText.text = buildRateText(st)
        gestureText.text = if (st.gesture >= 0) {
            "Gesture:\n  gesture: ${st.gesture} (0-8)\n  raw gesture: ${st.rawGesture} (0-8)\n" +
                "  possiblity: ${st.possibility} (0-100)\n  strength: ${st.strength} (0-100)"
        } else {
            GESTURE_EMPTY_TEXT
        }
    }

    // Measured-vs-nominal rate line.
    private fun buildRateText(st: DeviceUiState): String {
        val entries = ArrayList<String>()
        synchronized(st.rateLock) {
            for ((type, name) in TYPE_DISPLAY_ORDER) {
                val actual = st.actualRates[type] ?: continue
                val nominal = st.nominalRates[type] ?: 0f
                val nominalText = if (nominal > 0f) "$nominal" else "--"
                entries.add("$name ${String.format(Locale.US, "%.1f", actual)} / ${nominalText}Hz")
            }
        }
        if (st.streamStartTimeSec > 0) {
            val startMs = (st.streamStartTimeSec * 1000.0).toLong()
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            entries.add("start ${fmt.format(Date(startMs))}")
        }
        if (st.streamDelayMs > 0) {
            entries.add("delay ${st.streamDelayMs}ms")
        }
        return if (entries.isEmpty()) "" else "Actual: " + entries.joinToString(" | ")
    }

    // Clears one device's data views (connect/replay start).
    private fun clearDataViews(st: DeviceUiState) {
        st.dataSession++
        st.liveFilter.reset()
        for (v in st.bioSlots) v.clear()
        for (v in st.bioSpectra) v.clear()
        st.accView.clear()
        st.gyroView.clear()
        st.eulerView.clear()
        st.quatView.clear()
        st.accSpectrum.clear()
        st.gyroSpectrum.clear()
        st.eulerSpectrum.clear()
        st.quatSpectrum.clear()
        st.cubeView.clearQuaternion()
        synchronized(st.impLock) { st.impedances.forEach { it.clear() } }
        for (v in st.bioSlots) v.sideText = ""
        st.gesture = -1
        st.rawGesture = -1
        st.possibility = -1
        st.strength = -1
        st.streamStartTimeSec = 0.0
        st.streamDelayMs = 0
    }

    private fun setStatus(s: String) {
        statusText.text = s
    }

    // Writes one application event line into the SDK log: into the given
    // device's profile log when a device is the subject, else into the
    // controller log.
    private fun appLog(message: String, level: String = "I", profile: SensorProfile? = null) {
        if (profile != null) {
            profile.log(message, level)
        } else if (::controller.isInitialized) {
            controller.log(message, level)
        }
    }

    // ---- lifecycle ---------------------------------------------------------------

    override fun onStop() {
        // Background: flush the SDK capture and log files.
        if (::controller.isInitialized) controller.onSuspend()
        super.onStop()
    }

    override fun onDestroy() {
        appLog("App: demo window closing")
        scope.cancel()
        mainHandler.removeCallbacks(uiRefreshTimer)
        fftExecutor.shutdownNow()
        dataWorkerStop.set(true)
        dataWorker?.interrupt()
        // Fire-and-forget teardown; keep the replay stop join off the UI thread.
        val stopMacs = mutableListOf<String>()
        replayMac?.let { stopMacs.add(it) }
        stopMacs.addAll(replayMacs)
        replayMacs.clear()
        replayProfile = null
        replayMac = null
        if (stopMacs.isNotEmpty()) {
            Thread({
                for (m in stopMacs) controller.stopBinReplay(m)
            }, "ReplayStopper").apply { isDaemon = true }.start()
        }
        val states = synchronized(deviceStates) { deviceStates.values.toList() }
        for (st in states) {
            if (!st.isReplay) st.profile?.disconnect(null)
        }
        SensorController.terminate()
        super.onDestroy()
    }
}
