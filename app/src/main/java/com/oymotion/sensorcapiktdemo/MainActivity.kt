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
import android.widget.RadioGroup
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

/** Logcat tag for headless verification (data counters, replay lifecycle). */
private const val TAG = "SensorCapiKtDemo"

/** Queue-mode UI refresh period (ms); the views redraw from their rings. */
private const val UI_REFRESH_INTERVAL_MS = 50L

/** Queue-mode backlog cap; a full queue drops the oldest batch. */
private const val DATA_QUEUE_CAPACITY = 1000

/** Spectrum recompute interval (ms); the FFT runs on a worker thread. */
private const val FFT_UPDATE_INTERVAL_MS = 500L

/** The demo's own version (Qt demo DEMO_VERSION parity), shown in the title. */
private const val DEMO_VERSION = "0.0.9"

/** EEG sample rate options offered on the Device page (Hz). */
private val SAMPLE_RATE_CANDIDATES = listOf(250, 500)

/** Battery reading stable band (%, Qt POWER_STABLE_BAND parity): hold the
 * displayed value while a valid reading differs by less than this. */
private const val POWER_STABLE_BAND = 4

/** Bio page slot count (Qt MainWindow::_bioWidgets parity): 8 single-channel
 * waveform slots per device, paged in EEG mode. */
private const val BIO_SLOT_COUNT = 8

// Impedance array kinds (per-device, indexed by channel).
private const val IMP_EMG = 0
private const val IMP_EEG = 1
private const val IMP_ECG = 2
private const val IMP_BRTH = 3

/** Per-type stats display order and labels (Qt DeviceState::buildStatusText
 * parity); only types that have delivered data are shown. */
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

/**
 * Device-list row highlight: subtle blue-gray background on the SELECTED row
 * (the Connect/Disconnect target). Unselected rows stay transparent so the
 * list keeps its plain look.
 */
private val ROW_SELECTED_BG = Color.rgb(0xDC, 0xE9, 0xF5)
private val ROW_DEFAULT_BG = Color.TRANSPARENT

/** Gesture box text before the first gesture sample (Qt parity; the
 * "possiblity" spelling is intentional). */
private const val GESTURE_EMPTY_TEXT =
    "Gesture:\n  gesture: -- (0-8)\n  raw gesture: -- (0-8)\n" +
    "  possiblity: -- (0-100)\n  strength: -- (0-100)"

/**
 * Kotlin sen_capi demo: scan -> connect -> init -> stream, with a
 * QTabWidget-style mobile layout (Device / Bio / IMU pages) mirroring
 * the Qt demo. MULTI-DEVICE (Qt MainWindow parity): any number of devices
 * can be connected and streaming at the same time; each connected device
 * gets a DeviceUiState keyed by mac holding its own page contents (bio
 * waveforms, IMU waveforms + spectrum strips, cube), data counters, bio
 * mode detection, DeviceInfo and Live Filter state. Clicking a connected
 * device in the scan list makes it the CURRENT device whose pages are
 * shown and whose profile the NTF/sample-rate controls target; switching
 * the current device swaps the page contents, so a backgrounded device
 * keeps streaming into its own views. The Bio page auto-selects the bio
 * mode (EMG / EEG / PPG plot set) per device from DeviceInfo and observed
 * batches - Qt layoutBio parity: 8 single-channel slots, EEG paging
 * (Prev/Next + "Page X / Y"), impedance side texts, and the PPG fixed plot
 * set (EEG fp1/fp2 + PPG red/ir + SpO2 spo2/hr). Every async SDK call goes
 * through the stdlib suspend extensions in SensorCapiAsync.kt (scan /
 * connect / disconnect / init / startDataNotification / setParam / getParam /
 * parseBinToCsvAsync); the listeners (data, state, power, error, device-info
 * update) fire on the binding's callback thread and are marshalled to the UI
 * as needed.
 *
 * Data path ("Clone Data" checkbox, Qt MainWindow parity; runtime
 * switchable, default unchecked). The queue is always there: each
 * SensorData batch is enqueued on the SDK callback thread together with
 * its device mac (bounded, drop-oldest), a daemon worker thread drains
 * the queue through the append path, and a periodic UI timer refreshes
 * the current device's views and the stats label (no per-batch UI work).
 *  - checked  : the batch is clone()d before enqueueing, so the queue
 *               carries owned data;
 *  - unchecked: the borrowed batch is enqueued directly - its sample and
 *               info buffers stay readable until teardown; a batch
 *               rewritten before the worker reads it is caught by the
 *               isDataValid() probe and reads as zeros.
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var controller: SensorController

    private enum class BioMode { NONE, EMG, EEG, PPG }

    private class SpectrumFeed(val view: WaveformView, val spectrum: SpectrumView) {
        var lastSubmitMs = 0L
        val inFlight = AtomicBoolean(false)
    }

    /**
     * One bio slot binding (Qt MainWindow::_bioTargets / setSource parity):
     * a slot view fed from channel `channel` of the `dataType` stream, with
     * an optional impedance side-text target (impKind < 0 = none).
     */
    private data class BioFeed(
        val view: WaveformView,
        val dataType: Int,
        val channel: Int,
        val impKind: Int = -1,
        val impChannel: Int = 0,
    )

    /**
     * Per-device UI and data state (Qt DeviceState parity), keyed by mac in
     * deviceStates. Owns the device's Bio/IMU page contents (built once on
     * the UI thread at registration and swapped into the page containers
     * when the device becomes current), the bio mode detection data, the
     * per-device Live Filter, the data counters plus the per-type rate/loss
     * accounting for the stats labels, the impedance side-text values, the
     * gesture readout, and the last DeviceInfo / battery reading used to
     * rebind the Device page labels on a switch.
     * `profile` is null only for a state whose link is already torn down;
     * replay sessions get a state too (isReplay), keyed by the bin's mac.
     */
    private inner class DeviceUiState(
        val mac: String,
        val profile: SensorProfile?,
        val isReplay: Boolean,
    ) {
        var info: DeviceInfo? = null
        var lastPower = -1

        // Driven by the SDK's data-transfer state notification (authoritative,
        // covers auto-reconnect stream restores and replay start/EOF): true
        // while the stream is actually on.
        @Volatile var transferring = false

        // Bio mode detection: observed dataType -> channelCount. bioFeeds
        // binds the 8 bio slots to (dataType, channel) sources; the list
        // reference is replaced atomically on re-layout so the data worker
        // thread never sees a half-built binding.
        val bioChannels = HashMap<Int, Int>()
        @Volatile var bioMode = BioMode.NONE
        @Volatile var bioFeeds: List<BioFeed> = emptyList()
        // Current binding of each bio slot (null = unbound); a slot re-bound
        // to a different source drops its ring content.
        val slotFeeds = arrayOfNulls<BioFeed>(BIO_SLOT_COUNT)
        // Current EEG page (0-based) and the channel counts the current bio
        // layout was built from (a page flip re-layouts with the same counts).
        var bioPageIndex = 0
        var lastBioCounts = IntArray(6) // eeg, ecg, brth, ppg, spo2, emg

        // Per-device data counters.
        var batches = 0L
        var samples = 0L
        var lostPackages = 0L
        var lastLogMs = 0L
        val diagTypes = java.util.Collections.synchronizedSet(java.util.HashSet<Int>())

        // Per-type accounting for the stats/rate labels (Qt DeviceState
        // rateCounts/lostCounts parity): written on the data thread under
        // rateLock, read on the UI thread.
        val rateLock = Any()
        val rateCounts = HashMap<Int, Long>()      // valid samples in the current window
        val actualRates = HashMap<Int, Double>()   // rotated once per second
        val nominalRates = HashMap<Int, Float>()
        val nominalChannels = HashMap<Int, Int>()
        val lostCounts = HashMap<Int, Long>()      // latest per-type lostPackageCount
        val typeBatches = HashMap<Int, Long>()
        var rateWindowStartMs = System.currentTimeMillis()

        /**
         * Rotates the accumulated per-type sample counts into actualRates
         * (Qt DeviceState::updateActualRates parity); call once per second.
         */
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

        // Latest impedance per channel (ohms; -1 = unknown), by IMP_* kind;
        // written on the data thread under impLock, read on the UI thread.
        val impLock = Any()
        val impedances = arrayOf(
            ArrayList<Float>(), ArrayList<Float>(), ArrayList<Float>(), ArrayList<Float>())

        // Latest gesture readout (Qt DeviceState gesture fields parity);
        // -1 = nothing received yet.
        @Volatile var gesture = -1
        @Volatile var rawGesture = -1
        @Volatile var possibility = -1
        @Volatile var strength = -1

        // Per-device bio live filter; the spinner band is applied at
        // registration and re-applied to every state on a band switch.
        val liveFilter = LiveFilter()

        // Bumped on every clearDataViews so an in-flight spectrum result
        // from the old session is dropped.
        @Volatile var dataSession = 0

        // Page contents (see buildStateViews).
        lateinit var bioPage: LinearLayout
        lateinit var bioHeader: TextView
        lateinit var bioContainer: LinearLayout
        lateinit var bioSlots: List<WaveformView>
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
    // mac. Structural changes happen on the UI thread; data-thread lookups
    // synchronize on the map.
    private val deviceStates = LinkedHashMap<String, DeviceUiState>()

    // The device whose pages are shown and whose profile the setParam
    // controls target (Qt "current device" parity); null when nothing is
    // connected.
    @Volatile private var currentMac: String? = null

    // Bin replay state: the replay profile is a replay-mode profile returned
    // by replayBinFile; replayMac is the mac the replay session was started
    // with (stopBinReplay is keyed by it) and keys the replay DeviceUiState.
    private var replayProfile: SensorProfile? = null
    private var replayMac: String? = null

    // Page widgets.
    private lateinit var pageContainer: FrameLayout
    private lateinit var tabButtons: List<Button>
    private lateinit var bioPageContainer: FrameLayout
    private lateinit var imuPageContainer: FrameLayout

    // Live Filter band spinner selection (Bio page): an activity-level UI
    // choice applied to every device state's filter (Python demo
    // _filter_band parity).
    private var filterBand = 0
    private lateinit var filterSpinner: Spinner

    // The spectrum FFT runs on a shared single-thread executor, throttled
    // per strip; a result computed from a superseded data session or ring
    // generation is dropped.
    private val fftExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SpectrumWorker").apply { isDaemon = true }
    }

    // Device page widgets.
    private lateinit var scanBtn: Button
    private lateinit var connectBtn: Button
    private lateinit var statusText: TextView
    private lateinit var rateText: TextView
    private lateinit var batteryText: TextView
    private lateinit var linkText: TextView
    private lateinit var mtuText: TextView
    private lateinit var selectedText: TextView
    private lateinit var statsText: TextView
    private lateinit var gestureText: TextView
    private lateinit var deviceListView: ListView
    private lateinit var listAdapter: ArrayAdapter<String>
    // Mac of each visible list row, in row order (scan entries, then the
    // synthetic state-only rows); rebuilt by refreshDeviceList and read by
    // the adapter to tint the selected row.
    private var rowMacs: List<String> = emptyList()
    private lateinit var cloneCheck: CheckBox
    private lateinit var autoReconnectCheck: CheckBox
    private lateinit var debugLogCheck: CheckBox
    private lateinit var binDataCheck: CheckBox
    private lateinit var replayPathEdit: EditText
    private lateinit var replayBtn: Button
    private lateinit var pauseReplayBtn: Button
    private lateinit var stopReplayBtn: Button
    private lateinit var analyzeBtn: Button
    private lateinit var sampleRateGroup: RadioGroup
    private val ntfChecks = LinkedHashMap<String, CheckBox>()
    private val filterChecks = LinkedHashMap<String, CheckBox>()

    // Bio page widgets (EEG paging, Qt MainWindow::_pageControls parity):
    // visible only for the current device in EEG mode with more than one
    // page.
    private lateinit var pageControlsRow: LinearLayout
    private lateinit var prevPageBtn: Button
    private lateinit var nextPageBtn: Button
    private lateinit var pageLabel: TextView

    private val devices = LinkedHashMap<String, BleDevice>()
    private var sortedDevices: List<BleDevice> = emptyList()
    private var selectedMac: String? = null

    @Volatile private var scanning = false
    @Volatile private var cloneData = false
    private var suppressNtfCallbacks = false
    private var suppressSampleRateCallbacks = false

    // Session-wide toggles (Qt MainWindow parity): auto reconnect applies to
    // every connected device and is inherited by new connections; the debug
    // toggles gate the SDK file logging and the per-device bin recording.
    private var autoReconnect = true
    private var debugLogEnabled = true
    private var binDataEnabled = true
    private var replayPaused = false
    private var analyzing = false

    // Last 1 Hz rotation of the per-type rate windows (UI timer driven).
    private var lastRateTickMs = 0L

    // Data queue (always on): the SDK callback thread only enqueues
    // (mac + batch) -- an owned clone when Clone Data is checked, the
    // borrowed batch otherwise; a daemon worker drains the queue, and the
    // UI timer below refreshes the views, so neither the SDK thread nor
    // the UI thread does the per-batch processing.
    private class QueuedBatch(val mac: String, val data: SensorData)
    private val dataQueue = ArrayBlockingQueue<QueuedBatch>(DATA_QUEUE_CAPACITY)
    private val dataWorkerStop = AtomicBoolean(false)
    private var dataWorker: Thread? = null

    // Periodic UI refresh: redraws the current device's views from their
    // rings and updates the stats labels; the impedance side texts and the
    // 1 Hz rate-window rotation run here too.
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
        title = "SensorCapiKtDemo v$DEMO_VERSION"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(buildTabBar(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        pageContainer = FrameLayout(this)
        root.addView(pageContainer, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        pageContainer.addView(buildDevicePage())
        pageContainer.addView(buildWaveformPage())
        pageContainer.addView(buildImuPage())
        setContentView(root)
        showPage(0)
        startDataWorker()
        mainHandler.postDelayed(uiRefreshTimer, UI_REFRESH_INTERVAL_MS)

        controller = SensorController.getInstance()
        // Python demo parity (_apply_sdk_debug_log): session logs and bin
        // exports go into a "<yyyyMMdd_HHmmss>_<sdk version>" subdir of the
        // SDK log base dir; the path must be set BEFORE setDebugEnabled(true),
        // and both must precede registerBleBridge -- the bridge registration
        // already emits SDK log records, which would otherwise open the
        // controller log at the default base dir.
        if (debugLogEnabled) {
            applySdkDebugLog()
        }
        controller.registerBleBridge(applicationContext)
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

    private fun buildDevicePage(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scanBtn = Button(this).apply {
            text = "Scan"
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

        statusText = TextView(this).apply { textSize = 13f }
        rateText = TextView(this).apply { textSize = 13f }
        batteryText = TextView(this).apply { textSize = 13f; text = "Battery: -" }
        linkText = TextView(this).apply { textSize = 13f; text = "Link: --" }
        mtuText = TextView(this).apply { textSize = 13f; text = "MTU: --" }
        selectedText = TextView(this).apply { textSize = 13f; text = "Selected: -" }
        statsText = TextView(this).apply { textSize = 13f; text = "Batches: 0 (lost pkgs: 0)" }
        page.addView(statusText)
        page.addView(rateText)
        page.addView(batteryText)
        page.addView(linkText)
        page.addView(mtuText)
        page.addView(selectedText)
        page.addView(statsText)

        // Device list: each row shows the connection state and which device
        // is currently displayed ("<- showing" mark), and the SELECTED row
        // (Connect/Disconnect target) carries a subtle background tint.
        // Tapping a row selects it; tapping a connected row also makes it the
        // current device whose data the Bio/IMU pages show (Qt demo parity).
        listAdapter = object : ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, ArrayList()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val mac = rowMacs.getOrNull(position)
                // Recycled views: set the background in both branches.
                v.setBackgroundColor(
                    if (mac != null && mac == selectedMac) ROW_SELECTED_BG else ROW_DEFAULT_BG)
                return v
            }
        }
        deviceListView = ListView(this).apply {
            adapter = listAdapter
            setOnItemClickListener { _, _, position, _ ->
                // Rows past the scan list are state-only entries (replay of
                // an off-air device's bin); they have no BleDevice.
                val mac = rowMacs.getOrNull(position) ?: return@setOnItemClickListener
                val d = sortedDevices.getOrNull(position)
                selectedMac = mac
                selectedText.text = "Selected: ${d?.name ?: "replay"} [$mac]"
                // Move the row highlight immediately (a current-device switch
                // below also rebuilds the list, but a plain selection of an
                // unconnected row does not).
                listAdapter.notifyDataSetChanged()
                synchronized(deviceStates) {
                    if (deviceStates.containsKey(mac) && mac != currentMac) {
                        setCurrentDevice(mac)
                    }
                }
                updateConnectButton()
            }
        }
        // Fixed height: the merged settings controls below stay reachable and
        // the whole page scrolls (Qt mobile layout parity).
        page.addView(deviceListView, LinearLayout.LayoutParams(MATCH_PARENT, dp(150)))

        // Clone Data (Qt MainWindow parity), default OFF: checked = every
        // batch is clone()d before entering the data queue; unchecked =
        // zero-copy, the queue carries the borrowed batch.
        cloneCheck = CheckBox(this).apply {
            text = "Clone Data"
            isChecked = false
            setOnCheckedChangeListener { _, isChecked -> cloneData = isChecked }
        }
        page.addView(cloneCheck)

        // Auto Reconnect (Qt MainWindow parity): default ON; applies to every
        // connected device and is inherited by new connections.
        autoReconnectCheck = CheckBox(this).apply {
            text = "Auto Reconnect"
            isChecked = true
            setOnCheckedChangeListener { _, isChecked -> onAutoReconnectToggled(isChecked) }
        }
        page.addView(autoReconnectCheck)

        // Debug log toggles (Qt "Debug Log" group parity), both default ON.
        val debugRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
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
        debugRow.addView(debugLogCheck, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        debugRow.addView(binDataCheck, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        page.addView(debugRow)

        // NTF switches (Qt demo setParam panel parity); they target the
        // current device and are re-synced on every current-device switch.
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
                // Enabled by syncSwitches() once a device is initialized;
                // before that a tap would flip the box without any effect.
                isEnabled = false
                setOnCheckedChangeListener { _, isChecked -> onNtfToggled(key, isChecked) }
            }
            ntfChecks[key] = cb
            row!!.addView(cb, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        page.addView(grid)

        // FILTER switches (Qt "Filter" group parity): same readback pattern
        // as the NTF switches, but capability comes from the FILTER aggregate
        // query alone - old EMG devices answer it with an error, so the boxes
        // stay disabled (never hidden).
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

        // EEG sample rate options (Qt demo "EEG Sample Rate" group parity):
        // enabled per getParam("EEG_SAMPLE_RATE_LIST"), checked per
        // getParam("EEG_SAMPLE_RATE"); a user selection issues setParam on
        // the current device and re-syncs the buttons (syncSampleRateControl).
        val srRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val srLabel = TextView(this).apply {
            text = "EEG Sample Rate"
            textSize = 13f
        }
        srRow.addView(srLabel)
        sampleRateGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for (rate in SAMPLE_RATE_CANDIDATES) {
            val rb = RadioButton(this).apply {
                text = "$rate Hz"
                id = rate
                isEnabled = false
            }
            sampleRateGroup.addView(rb)
        }
        sampleRateGroup.setOnCheckedChangeListener { _, checkedId ->
            onSampleRateSelected(checkedId)
        }
        srRow.addView(sampleRateGroup)
        page.addView(srRow)

        // Gesture readout (Qt "Gesture" group parity); follows the current
        // device, refreshed by the UI timer.
        gestureText = TextView(this).apply {
            textSize = 13f
            text = GESTURE_EMPTY_TEXT
        }
        page.addView(gestureText)

        // Bin replay section: the replay profile gets its own DeviceUiState
        // (keyed by the bin's mac) and becomes the current device while it
        // runs; live devices keep streaming in the background.
        replayPathEdit = EditText(this).apply {
            setSingleLine()
            // Default to the app-private external files dir: on Android 11+
            // (scoped storage) the demo holds no broad storage permission, so
            // arbitrary /sdcard paths are unreadable; adb push still works:
            //   adb push test.bin /sdcard/Android/data/<package>/files/
            setText("${getExternalFilesDir(null)}/test.bin")
        }
        page.addView(replayPathEdit)
        val replayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        replayBtn = Button(this).apply {
            text = "Replay Bin"
            setOnClickListener { startReplay(replayPathEdit.text.toString().trim(), true) }
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
        replayRow.addView(replayBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        replayRow.addView(pauseReplayBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        replayRow.addView(stopReplayBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        replayRow.addView(analyzeBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        page.addView(replayRow)
        // The merged device + settings controls can exceed a phone screen;
        // wrap the page in a ScrollView (Qt mobile QScrollArea parity).
        return ScrollView(this).apply { addView(page) }
    }

    private fun buildWaveformPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Live Filter band spinner (Qt demo filter combo parity): band-passes
        // the bio waveforms (EMG/EEG/ECG/BRTH/PPG/SpO2) on the data thread
        // before they reach the display rings. The band is a UI choice shared
        // by every device; each device state applies it to its own filter.
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

        // EEG paging controls (Qt MainWindow::_pageControls parity): visible
        // only when the current device is in EEG mode with more than one
        // page; the page state is per device (DeviceUiState.bioPageIndex).
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

    /** Centered-ish placeholder text shown while a page has no current device. */
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

    /**
     * Builds one device state's Bio/IMU page contents. The Bio content is a
     * header plus 8 single-channel waveform slots (Qt MainWindow::_bioWidgets
     * parity) which layoutBio binds to (stream, channel) sources - paging an
     * EEG device re-binds the slots, the views themselves persist. The IMU
     * content is a weight-split layout (no ScrollView): each waveform view
     * (ACC/GYRO/EULER/QUATERNION) gets ~1/4 of the height, so the per-channel
     * row height stays large on phone screens, and a spectrum strip (FFT of
     * the waveform above it, computed on the spectrum worker) sits under each
     * waveform at a smaller weight - plus the 3D quaternion cube. Must run on
     * the UI thread.
     */
    private fun buildStateViews(st: DeviceUiState) {
        st.bioPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        st.bioHeader = TextView(this).apply {
            textSize = 13f
            setPadding(dp(8), dp(4), dp(8), 0)
        }
        st.bioContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        st.bioPage.addView(st.bioHeader)
        st.bioPage.addView(st.bioContainer, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        st.bioSlots = (0 until BIO_SLOT_COUNT).map {
            val v = WaveformView(this, viewChannels = 1, capacity = 1250)
            st.bioContainer.addView(v, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            v
        }
        showBioPlaceholder(st, "Waiting for data ...")

        st.imuPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        st.accView = WaveformView(this, viewChannels = 3, capacity = 500).apply { title = "ACC" }
        st.gyroView = WaveformView(this, viewChannels = 3, capacity = 500).apply { title = "GYRO" }
        st.eulerView = WaveformView(this, viewChannels = 3, capacity = 500).apply { title = "EULER (deg)" }
        st.quatView = WaveformView(this, viewChannels = 4, capacity = 500).apply { title = "QUATERNION" }
        st.accSpectrum = SpectrumView(this)
        st.gyroSpectrum = SpectrumView(this)
        st.eulerSpectrum = SpectrumView(this)
        st.quatSpectrum = SpectrumView(this)
        st.cubeView = CubeView(this)
        st.imuPage.addView(st.accView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        st.imuPage.addView(st.accSpectrum, LinearLayout.LayoutParams(MATCH_PARENT, 0, 0.5f))
        st.imuPage.addView(st.gyroView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        st.imuPage.addView(st.gyroSpectrum, LinearLayout.LayoutParams(MATCH_PARENT, 0, 0.5f))
        st.imuPage.addView(st.eulerView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        st.imuPage.addView(st.eulerSpectrum, LinearLayout.LayoutParams(MATCH_PARENT, 0, 0.5f))
        st.imuPage.addView(st.quatView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        st.imuPage.addView(st.quatSpectrum, LinearLayout.LayoutParams(MATCH_PARENT, 0, 0.5f))
        st.imuPage.addView(st.cubeView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        st.spectrumFeeds = listOf(
            SpectrumFeed(st.accView, st.accSpectrum),
            SpectrumFeed(st.gyroView, st.gyroSpectrum),
            SpectrumFeed(st.eulerView, st.eulerSpectrum),
            SpectrumFeed(st.quatView, st.quatSpectrum),
        )
    }

    /**
     * Registers a device (live or replay) and builds its page contents.
     * Must run on the UI thread. Applies the current Live Filter band.
     */
    private fun registerDeviceState(mac: String, profile: SensorProfile?,
                                    isReplay: Boolean): DeviceUiState {
        val st = DeviceUiState(mac, profile, isReplay)
        st.liveFilter.setBand(filterBand)
        buildStateViews(st)
        synchronized(deviceStates) { deviceStates[mac] = st }
        refreshDeviceList()
        return st
    }

    /**
     * Drops a device state (disconnect / replay end): removes its page
     * contents and, when it was the current device, switches the pages to
     * another remaining device (or the placeholder). UI thread.
     */
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

    /**
     * Makes a device the current one (Qt current-device parity): its page
     * contents are swapped into the Bio/IMU page containers, the Device
     * page labels (battery/link/MTU/stats) rebind to its state, and the
     * NTF/sample-rate controls re-sync from its profile (greyed out for a
     * replay state or when nothing is current). UI thread.
     */
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
                // No parameter control over a replay session; the sample
                // rate radios only mirror the capture's rate (checked state).
                greyOutControls()
                syncSampleRateChecked(st.info?.eegSampleRate ?: 0f)
            } else if (st.profile != null) {
                syncSwitches(st.profile)
                syncSampleRateControl(st.profile)
            }
        }
        updatePageControls()
        updateStats()
        refreshDeviceList()
        Log.i(TAG, "current device -> ${mac ?: "-"}")
    }

    /** Greys the per-device setParam controls (disconnect / replay / none). */
    private fun greyOutControls() {
        suppressNtfCallbacks = true
        for (cb in ntfChecks.values) {
            cb.isEnabled = false
            cb.isChecked = false
        }
        // Filter boxes are never hidden, only disabled + unchecked.
        for (cb in filterChecks.values) {
            cb.isEnabled = false
            cb.isChecked = false
        }
        suppressNtfCallbacks = false
        suppressSampleRateCallbacks = true
        for (i in 0 until sampleRateGroup.childCount) {
            sampleRateGroup.getChildAt(i).isEnabled = false
        }
        sampleRateGroup.clearCheck()
        suppressSampleRateCallbacks = false
    }

    /** Idle state of one device's bio page: all slots unbound, placeholder. */
    private fun showBioPlaceholder(st: DeviceUiState, text: String) {
        st.bioMode = BioMode.NONE
        st.bioFeeds = emptyList()
        st.bioHeader.text = "Waveform (auto: EMG / EEG / PPG)"
        for (v in st.bioSlots) {
            v.title = ""
            v.placeholder = text
            v.sideText = ""
        }
    }

    /**
     * Binds one bio slot to a (stream, channel) source (Qt
     * WaveformWidget::setSource parity): label, curve color (stable per real
     * channel across EEG pages), and the impedance side-text target. A slot
     * re-bound to a different source drops its ring content.
     */
    private fun bindSlot(st: DeviceUiState, slot: Int, dataType: Int, channel: Int,
                         label: String, colorIndex: Int, impKind: Int,
                         feeds: MutableList<BioFeed>) {
        val v = st.bioSlots[slot]
        val feed = BioFeed(v, dataType, channel, impKind, channel)
        if (st.slotFeeds[slot] != feed) v.clear()
        st.slotFeeds[slot] = feed
        v.title = label
        v.colorIndex = colorIndex
        v.placeholder = ""
        v.sideText = ""
        feeds.add(feed)
    }

    /** Unbinds one bio slot (Qt setSource(nullptr) parity). */
    private fun unbindSlot(st: DeviceUiState, slot: Int, placeholder: String) {
        val v = st.bioSlots[slot]
        st.slotFeeds[slot] = null
        v.title = ""
        v.colorIndex = -1
        v.placeholder = placeholder
        v.sideText = ""
    }

    /**
     * Re-layouts one device's bio slots for one mode (Qt MainWindow::layoutBio
     * parity): 8 single-channel slots. EMG mode fills the leading slots with
     * the EMG channels (no paging). EEG mode pages the EEG channels with
     * perPage = 8 - hasECG - hasBRTH channels per page; the ECG slot is the
     * last widget (second-to-last when BRTH follows), BRTH the last. PPG mode
     * is the fixed 6-plot set (EEG fp1/fp2 + PPG red/ir + SpO2 spo2/hr); the
     * two EEG plots carry impedance side texts. Must run on the UI thread.
     * Channel counts come from DeviceInfo or observed batches; 0 = absent.
     */
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
                        bindSlot(st, i, DataType.NTF_EMG, i, "EMG-${i + 1}", i, IMP_EMG, feeds)
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
                        // The real channel index drives the curve color so it
                        // stays stable across pages.
                        bindSlot(st, i, DataType.NTF_EEG, ch, "EEG-${ch + 1}", ch, IMP_EEG, feeds)
                    } else if (hasECG && i == ecgIndex) {
                        bindSlot(st, i, DataType.NTF_ECG, 0, "ECG", -1, IMP_ECG, feeds)
                    } else if (hasBRTH && i == brthIndex) {
                        bindSlot(st, i, DataType.NTF_BRTH, 0, "BRTH", -1, IMP_BRTH, feeds)
                    } else {
                        // Slots past the last EEG channel on the final page
                        // stay blank (Python hides those axes).
                        val noSuchChannel = i < perPage && ch >= eegCh
                        unbindSlot(st, i, if (noSuchChannel) "" else waiting)
                    }
                }
            }
            BioMode.PPG -> {
                // Fixed plot set (DemoNewMulti BIO_PLOT_CONFIG): EEG fp1/fp2 +
                // PPG red/ir led + SpO2 spo2/heart_rate; the slot index drives
                // the curve color so each plot gets its own.
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
                            bindSlot(st, i, type, ch, labels[i], i, imp, feeds)
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

    /** EEG page count for the given per-page size and channel total. */
    private fun bioPageCount(perPage: Int, total: Int): Int {
        if (perPage <= 0 || total <= 0) return 1
        return maxOf(1, (total + perPage - 1) / perPage)
    }

    /** Page count of a device state (1 outside EEG mode). */
    private fun bioPageCount(st: DeviceUiState?): Int {
        if (st == null || st.bioMode != BioMode.EEG) return 1
        val c = st.lastBioCounts
        val perPage = BIO_SLOT_COUNT - (if (c[1] > 0) 1 else 0) - (if (c[2] > 0) 1 else 0)
        return bioPageCount(perPage, c[0])
    }

    /** Syncs the Bio page paging row with the current device (Qt
     * updatePageControls parity): visible only in EEG mode with > 1 page. */
    private fun updatePageControls() {
        val st = currentState()
        val pages = bioPageCount(st)
        pageControlsRow.visibility = if (pages > 1) View.VISIBLE else View.GONE
        pageLabel.text = "Page ${(st?.bioPageIndex ?: 0) + 1} / $pages"
        prevPageBtn.isEnabled = (st?.bioPageIndex ?: 0) > 0
        nextPageBtn.isEnabled = (st?.bioPageIndex ?: 0) < pages - 1
    }

    /** Prev/Next page: re-layouts the current device's EEG slots in place. */
    private fun onBioPage(delta: Int) {
        val st = currentState() ?: return
        if (st.bioMode != BioMode.EEG) return
        val pages = bioPageCount(st)
        val newPage = (st.bioPageIndex + delta).coerceIn(0, pages - 1)
        if (newPage == st.bioPageIndex) return
        st.bioPageIndex = newPage
        appLog("User: ${if (delta < 0) "prev" else "next"} page -> $newPage", "D")
        val c = st.lastBioCounts
        layoutBio(st, BioMode.EEG, c[0], c[1], c[2], c[3], c[4], c[5])
    }

    /** Mode from DeviceInfo (Qt DeviceState::bioKind parity: PPG > EEG > EMG). */
    private fun detectBioMode(info: DeviceInfo): BioMode = when {
        info.ppgSampleRate > 0 || info.ppgChannelCount > 0 -> BioMode.PPG
        info.eegChannelCount > 0 -> BioMode.EEG
        info.emgChannelCount > 0 -> BioMode.EMG
        else -> BioMode.NONE
    }

    /**
     * Data-driven fallback/repair of one device's bio mode: records the
     * batch's channel count, then re-layouts the slots when the observed
     * types imply a different mode (PPG wins over EEG/EMG once a PPG/SPO2
     * batch arrives), when a stream's channel count changed, or when an
     * EEG-mode ECG/BRTH slot shows up that has no bound slot yet. Safe to
     * call from any thread; the re-layout itself is marshalled to the UI
     * thread.
     */
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

    /** Back to the mode-less placeholder (per device). */
    private fun resetBio(st: DeviceUiState) {
        synchronized(st.bioChannels) { st.bioChannels.clear() }
        showBioPlaceholder(st, "Waiting for data ...")
    }

    /**
     * FFT spectra of the current device's IMU waveforms (Qt demo
     * maybeSubmitFft/pollFftResult parity): the time-ordered ring snapshot
     * is computed on the shared worker executor so neither the UI thread nor
     * the data thread runs the FFT; throttled to one compute per strip every
     * FFT_UPDATE_INTERVAL_MS with a single in-flight task per strip. Results
     * whose data session or ring generation changed while computing are
     * dropped. Called from the UI refresh timer (both data path modes).
     */
    private fun maybeSubmitSpectra() {
        val st = currentState() ?: return
        val now = System.currentTimeMillis()
        val session = st.dataSession
        for (feed in st.spectrumFeeds) {
            if (feed.inFlight.get() || now - feed.lastSubmitMs < FFT_UPDATE_INTERVAL_MS) continue
            // Skips rings that have not received data yet (rate unknown).
            val snap = feed.view.snapshotForSpectrum() ?: continue
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
                // Executor already shut down (activity teardown).
                feed.inFlight.set(false)
            }
        }
    }

    // ---- permissions ---------------------------------------------------------

    private fun requestBlePermissions() {
        val perms = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        } + if (Build.VERSION.SDK_INT <= 28) {
            // Runtime /sdcard access for bin replay and the SDK log dir
            // (/sdcard/Documents/sensorsdklog); granting WRITE also grants READ
            // in the same permission group.
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

    /**
     * Android 11+ scoped storage blocks raw /sdcard access; all-files access
     * is granted by the user on a system settings page (there is no runtime
     * dialog). Needed for the SDK session logs and the .bin export under
     * /sdcard/Documents/sensorsdklog.
     */
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

    // ---- scan (suspend extension in a loop) -----------------------------------

    private fun toggleScan() {
        if (scanning) {
            appLog("Stop scan")
            scanning = false
            controller.stopScan()
            scanBtn.text = "Scan"
            setStatus("Scan stopped (${devices.size} devices)")
        } else {
            appLog("User: start scan")
            scanning = true
            scanBtn.text = "Stop Scan"
            setStatus("Scanning ...")
            scope.launch {
                // Repeated scan() rounds: each round scans 5 s, then stops and
                // returns the MAC-deduplicated list (Python scan parity).
                while (scanning) {
                    if (!controller.isEnable) {
                        appLog("User: start scan rejected (Bluetooth disabled)", "W")
                        setStatus("Bluetooth is off")
                        break
                    }
                    val found = controller.scan(5000)
                    mergeDevices(found)
                }
                scanning = false
                scanBtn.text = "Scan"
            }
        }
    }

    private fun mergeDevices(found: List<BleDevice>) {
        // Repeat results update in place (RSSI refresh), then sort by RSSI.
        for (d in found) {
            if (d.mac.isNotEmpty()) devices[d.mac] = d
        }
        sortedDevices = devices.values.sortedByDescending { it.rssi }
        refreshDeviceList()
        setStatus("Devices: ${sortedDevices.size}")
    }

    /**
     * Rebuilds the list rows: name/mac/rssi plus the connection state and a
     * "<- showing" mark on the current device (Qt demo current-device
     * display parity).
     */
    private fun refreshDeviceList() {
        listAdapter.clear()
        listAdapter.addAll(sortedDevices.map { d ->
            val st = synchronized(deviceStates) { deviceStates[d.mac] }
            val linked = st?.profile?.let {
                val s = it.getDeviceState()
                s == DeviceState.READY || s == DeviceState.CONNECTED || s == DeviceState.CONNECTING
            } == true
            buildString {
                append("${d.name}  [${d.mac}]  rssi=${d.rssi}")
                if (st != null && st.isReplay) append("  [replay]")
                else if (linked) append("  [connected]")
                if (st?.transferring == true) append("  [streaming]")
                if (d.mac == currentMac) append("  <- showing")
            }
        })
        // States without a scan-list entry (a replay of an off-air device's
        // bin): appended so the [replay] row and the "<- showing" mark are
        // visible; tapping one selects it as the connect target is moot.
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
        // Row order mirrors the adapter rows; the adapter tints the row whose
        // mac is the selection. A removed state (disconnect / replay end)
        // drops its row, which also clears the highlight when it was selected.
        rowMacs = sortedDevices.map { it.mac } + extra.map { it.mac }
        listAdapter.notifyDataSetChanged()
    }

    // ---- connect / init / stream (all suspend extensions) ----------------------

    /**
     * Connects the selected device (without dropping any already-connected
     * device) or, when the selected device is already linked, disconnects
     * just that one (Qt demo per-device connect parity).
     */
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
                    // disconnect() stops the running stream first (Python
                    // parity); the DISCONNECTED state event removes the
                    // device state and rebinds the UI.
                    p0.disconnect()
                    updateConnectButton()
                }
                return
            }
        }
        // Stop the scan loop before connecting: continuous LOW_LATENCY
        // scanning during/after connect both gets throttled by the stack
        // ("scanning too frequently") and can drop the link.
        if (scanning) {
            scanning = false
            controller.stopScan()
            scanBtn.text = "Scan"
        }
        connectBtn.isEnabled = false
        scope.launch {
            try {
                val dev = devices[mac]
                appLog("User: connect ${dev?.name ?: ""} ($mac)")
                setStatus("Connecting ${dev?.name ?: ""} [$mac] ...")
                val prof = controller.requireSensor(mac)
                if (prof == null) {
                    appLog("App: failed to create SensorProfile for $mac", "E")
                    setStatus("requireSensor failed")
                    updateConnectButton()
                    return@launch
                }
                // New connections inherit the current Auto Reconnect toggle
                // (Qt onConnectClicked parity).
                prof.setAutoReconnect(autoReconnect)
                val st = registerDeviceState(mac, prof, isReplay = false)
                attachListeners(st)

                if (!prof.connect()) {
                    appLog("App: failed to connect to ${dev?.name ?: ""} ($mac)", "E", prof)
                    setStatus("Connect failed")
                    removeDeviceState(mac)
                    updateConnectButton()
                    return@launch
                }
                setStatus("Connected, init ...")

                // batch 15 samples/channel, 30 s init timeout, battery poll 60 s
                // (example_android_capi parity).
                if (!prof.init(15, 30000, 60000)) {
                    appLog("App: failed to initialize ${dev?.name ?: ""} ($mac)", "E", prof)
                    setStatus("Init failed")
                    removeDeviceState(mac)
                    updateConnectButton()
                    return@launch
                }
                val info = prof.getDeviceInfo()
                st.info = info
                setStatus("Init ok: $info")
                Log.i(TAG, "init ok[$mac]: name=${info.deviceName} EMG=${info.emgChannelCount}ch@${info.emgSampleRate} " +
                        "EEG=${info.eegChannelCount}ch@${info.eegSampleRate} ECG=${info.ecgChannelCount} " +
                        "BRTH=${info.brthChannelCount} PPG=${info.ppgChannelCount}ch@${info.ppgSampleRate} " +
                        "SPO2=${info.spo2ChannelCount}ch@${info.spo2SampleRate} IMU=${info.imuChannelCount}ch@${info.imuSampleRate}")
                // Session params follow the debug toggles (Qt
                // applySessionParams parity): the per-device log file and the
                // raw BLE traffic .bin (recorded while streaming, exported
                // into the session log dir on stream stop) are enabled per
                // device only while the matching toggle is ON.
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
                // Bio page mode from DeviceInfo (Qt bioKind parity); seed the
                // observed-channel map with it so the data-driven repair path
                // (maybeReconfigureBio) does not transiently downgrade the mode
                // before the first batch of each type arrives.
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
                // The just-connected device becomes the current one (Qt demo
                // parity); the controls re-sync from its profile there.
                setCurrentDevice(mac)

                if (!prof.startDataNotification()) {
                    appLog("App: failed to start data stream on $mac", "E", prof)
                    setStatus("startDataNotification failed")
                    updateConnectButton()
                    return@launch
                }
                // New data session: clear the device's views and drop any
                // in-flight spectrum result from a previous session.
                clearDataViews(st)
                appLog("App: device connected and streaming: ${dev?.name ?: ""} ($mac)", "I", prof)
                setStatus("Streaming $mac ...")
            } finally {
                updateConnectButton()
            }
        }
    }

    private fun attachListeners(st: DeviceUiState) {
        val p = st.profile ?: return
        val mac = st.mac
        p.setOnStateChangeListener { _, newState ->
            mainHandler.post {
                if (newState == DeviceState.DISCONNECTED) {
                    // Single teardown path for user-initiated and abnormal
                    // drops: removes the state; when it was current, the
                    // pages switch to another device (controls grey out if
                    // none remains). The event also fires after a failed
                    // connect already removed the state; skip the log then.
                    if (synchronized(deviceStates) { deviceStates.containsKey(mac) }) {
                        appLog("App: device disconnected, removed from UI: $mac")
                    }
                    removeDeviceState(mac)
                    if (currentMac == null) setStatus("Disconnected $mac")
                } else if (mac == currentMac) {
                    setStatus("State -> ${DeviceState.nameOf(newState)}")
                }
            }
        }
        p.setOnErrorListener { _, errorMsg ->
            p.log("App: error callback: $errorMsg", "E")
            mainHandler.post { if (mac == currentMac) setStatus("Error: $errorMsg") }
        }
        p.setOnPowerListener { _, power ->
            // Battery stable band (Qt POWER_STABLE_BAND parity): ignore
            // invalid values, take the first reading directly, and hold the
            // displayed value while a valid reading differs by less than 4%.
            if (power < 0) return@setOnPowerListener
            val prev = st.lastPower
            if (prev >= 0 && kotlin.math.abs(power - prev) < POWER_STABLE_BAND) {
                return@setOnPowerListener
            }
            st.lastPower = power
            mainHandler.post { if (mac == currentMac) batteryText.text = "Battery: $power%" }
        }
        p.setOnDeviceInfoUpdateListener { _, info ->
            // Fired after the cached DeviceInfo changed (e.g. an EEG sample
            // rate switch); the callback thread is not the UI thread.
            st.info = info
            mainHandler.post {
                syncSampleRateBuffers(st, info)
                if (mac == currentMac) updateLinkInfo(info)
            }
        }
        p.setOnDataTransferStateChangeListener { _, isTransferring ->
            // Authoritative stream on/off signal (start/stop, link loss,
            // replay start/EOF); the callback thread is not the UI thread.
            st.transferring = isTransferring
            appLog("App: data stream ${if (isTransferring) "ON" else "OFF"} $mac", "I", p)
            mainHandler.post { refreshDeviceList() }
        }
        p.setOnDataListener { _, dataList -> onSensorData(mac, dataList) }
    }

    /**
     * Data listener entry for live and replay profiles alike: enqueue only.
     * Clone Data checked = the batch is clone()d on the SDK callback thread
     * so the queue carries owned data; unchecked = the borrowed batch is
     * enqueued directly (zero-copy; a stale batch reads as zeros via the
     * isDataValid() probe downstream). The worker thread drains the queue
     * through processBatches and the UI timer refreshes the views.
     */
    private fun onSensorData(mac: String, dataList: List<SensorData>) {
        if (cloneData) {
            for (d in dataList) enqueueData(QueuedBatch(mac, d.clone()))
        } else {
            for (d in dataList) enqueueData(QueuedBatch(mac, d))
        }
    }

    /** Data-queue sink: enqueue only; a full backlog drops the oldest batch. */
    private fun enqueueData(q: QueuedBatch) {
        while (!dataQueue.offer(q)) {
            dataQueue.poll()
        }
    }

    /**
     * Starts the data worker: blocks on the queue and feeds each batch
     * through the append path. The WaveformView/CubeView append/set methods
     * take the same locks the UI thread draws under, so the worker never
     * touches a UI object itself.
     */
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
        connectBtn.isEnabled = selectedMac != null && (st == null || !st.isReplay)
    }

    // ---- NTF / FILTER switches (suspend setParam / getParam) -----------------

    private fun onNtfToggled(key: String, on: Boolean) {
        if (suppressNtfCallbacks) return
        val st = currentState()
        val p = st?.profile ?: return
        if (st.isReplay) return
        scope.launch {
            val result = p.setParam(key, if (on) "ON" else "OFF")
            setStatus("setParam $key=${if (on) "ON" else "OFF"} -> $result")
            appLog("User: setParam($key, ${if (on) "ON" else "OFF"}) -> $result")
            // Re-query all switches after every toggle: a failed setParam must
            // revert the box, and coupled keys (the NTF_IMU sub-switches, the
            // old-device GEST/EMG mutual exclusion) can change as a side
            // effect (Python demo _refresh_control_states parity).
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
            appLog("User: setParam($key, ${if (on) "ON" else "OFF"}) -> $result")
            syncSwitches(p)
        }
    }

    /** Parses a "K|V|K|V" aggregate answer; empty map on an error string. */
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

    /**
     * Reads back the NTF and FILTER aggregates ("K|V|K|V") and syncs the
     * boxes after init, after every toggle, and on every current-device
     * switch (Python demo _refresh_control_states / _apply_control_states
     * parity). The NTF aggregate always lists every known notify key (the SDK
     * cache defaults them all to ON), so presence is NOT a capability signal:
     * an NTF switch is enabled only when DeviceInfo reports channels for the
     * stream, hidden once state info exists and the stream is unsupported,
     * and checked only when supported AND the value is ON. The FILTER boxes
     * are never hidden: they are enabled + checked only when the FILTER
     * aggregate answered at all (old EMG devices answer it with an error).
     */
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
            // Headless-verification line: the raw aggregate answers plus the
            // applied per-switch state (enabled/checked/visible).
            Log.i(TAG, "ntf readback: \"$ntfResult\" -> " + ntfChecks.map { (k, cb) ->
                "$k=${if (cb.isEnabled) "en" else "dis"}/${if (cb.isChecked) "on" else "off"}" +
                        "${if (cb.visibility != View.VISIBLE) "/gone" else ""}"
            } + " | filter readback: \"$filterResult\" -> " + filterChecks.map { (k, cb) ->
                "$k=${if (cb.isEnabled) "en" else "dis"}/${if (cb.isChecked) "on" else "off"}"
            })
        }
    }

    // ---- debug log / bin data / auto reconnect toggles ------------------------

    /**
     * Applies the session log path + setDebugEnabled(true) (Python demo
     * _apply_sdk_debug_log parity): the session's controller log, per-device
     * profile logs and bin exports all go into a "<timestamp>_<sdk version>"
     * subdir of /sdcard/Documents/sensorsdklog; the path must be set BEFORE
     * setDebugEnabled(true). Each call opens a fresh timestamped subdir.
     */
    private fun applySdkDebugLog() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val version = controller.version.replace('.', '_')
        controller.setLogPath(true, "/sdcard/Documents/sensorsdklog/${stamp}_$version")
        controller.setDebugEnabled(true)
        Log.i(TAG, "setLogPath -> /sdcard/Documents/sensorsdklog/${stamp}_$version")
    }

    /** Live (non-replay) states whose link is Ready. */
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
            controller.setDebugEnabled(false)
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

    // ---- EEG sample rate control (suspend setParam / getParam) ----------------

    private fun onSampleRateSelected(rate: Int) {
        if (suppressSampleRateCallbacks) return
        val st = currentState()
        val p = st?.profile ?: return
        if (st.isReplay) return
        scope.launch {
            val result = p.setParam("EEG_SAMPLE_RATE", rate.toString())
            setStatus("setParam EEG_SAMPLE_RATE=$rate -> $result")
            appLog("User: setParam(EEG_SAMPLE_RATE, $rate) -> $result")
            // Re-query so the buttons track the device state after the switch.
            syncSampleRateControl(p)
        }
    }

    /**
     * Reads back the EEG sample rate options and the bound rate (after init /
     * a rate switch / a current-device switch) and syncs the radio buttons;
     * buttons without a device-reported option stay disabled ("Error: ..."
     * answers parse to no options / no current rate).
     */
    private fun syncSampleRateControl(p: SensorProfile) {
        scope.launch {
            val options = p.getParam("EEG_SAMPLE_RATE_LIST")
                .split("|").mapNotNull { it.toIntOrNull() }
            val current = p.getParam("EEG_SAMPLE_RATE").toIntOrNull() ?: 0
            suppressSampleRateCallbacks = true
            for (i in 0 until sampleRateGroup.childCount) {
                val rb = sampleRateGroup.getChildAt(i) as RadioButton
                rb.isEnabled = rb.id in options
            }
            if (current in SAMPLE_RATE_CANDIDATES) sampleRateGroup.check(current)
            else sampleRateGroup.clearCheck()
            suppressSampleRateCallbacks = false
        }
    }

    /**
     * Syncs only the CHECKED state of the sample rate radios without issuing
     * setParam (replay path: the buttons stay disabled, no rate switching is
     * possible). A rate outside SAMPLE_RATE_CANDIDATES clears the selection.
     */
    private fun syncSampleRateChecked(rate: Float) {
        val current = rate.toInt()
        suppressSampleRateCallbacks = true
        if (current in SAMPLE_RATE_CANDIDATES) sampleRateGroup.check(current)
        else sampleRateGroup.clearCheck()
        suppressSampleRateCallbacks = false
    }

    /** Link/MTU display from DeviceInfo; "--" for values the link did not report. */
    private fun updateLinkInfo(info: DeviceInfo) {
        linkText.text =
            if (info.peripheralLatency < 0 || info.connectionIntervalMs <= 0) "Link: --"
            else "Link: ${info.connectionIntervalMs}ms / " +
                    "latency ${info.peripheralLatency} / " +
                    "timeout ${info.supervisionTimeoutMs}ms"
        mtuText.text = if (info.mtuSize <= 0) "MTU: --" else "MTU: ${info.mtuSize}"
    }

    /**
     * Sample-rate change handling for one device (called after a DeviceInfo
     * update push, e.g. setParam "EEG_SAMPLE_RATE" rewrote the bound EEG/ECG
     * rates or a config switch mid-replay re-rated the streams):
     * rebuilds every waveform ring whose nominal rate changed so the time
     * window stays consistent with the actual data rate (a stale length
     * would stretch/compress the waveform). Windows: EEG 1 s (5 s in the
     * PPG plot set, matching the bio layout), ECG 1 s, ACC/GYRO/EULER/QUAT
     * 5 s. Rates <= 0 and unchanged lengths are skipped; the views read the
     * ring length at draw time, so no retargeting is needed.
     */
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

    /**
     * Starts a realtime (or full-speed) bin replay. The SDK's BinReplay
     * REJECTS an empty deviceMac ("Error: device_mac is required"), so the
     * mac recorded in the bin header (getBinFileInfo) is passed; "REPLAY" is
     * the fallback for headerless captures. The replay-mode profile gets its
     * own DeviceUiState (keyed by that mac; a collision with a connected
     * device replaces its display state, Python demo parity) and becomes the
     * current device; replayed batches flow into its Bio/IMU pages through
     * processBatches in both direct and queue mode while live devices keep
     * streaming in the background.
     */
    private fun startReplay(path: String, realtime: Boolean) {
        if (path.isEmpty()) {
            setStatus("Replay failed: empty bin path")
            return
        }
        if (!File(path).exists()) {
            setStatus("Replay failed: file not found: $path")
            return
        }
        if (replayMac != null) {
            setStatus("Stop the running replay first")
            return
        }
        appLog("User: replay bin file: $path")
        val info = controller.getBinFileInfo(path)
        if (info == null) {
            // Not fatal here: replayBinFile rejects a config-less capture and
            // the mac below falls back to "REPLAY".
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
        val st = registerDeviceState(mac, p, isReplay = true)
        st.info = info?.deviceInfo
        clearDataViews(st)
        p.setOnDataListener { _, dataList -> onSensorData(mac, dataList) }
        p.setOnErrorListener { _, errorMsg ->
            p.log("App: error callback: $errorMsg", "E")
            mainHandler.post { if (mac == currentMac) setStatus("Replay: $errorMsg") }
        }
        p.setOnStateChangeListener { _, newState ->
            mainHandler.post {
                if (newState == DeviceState.DISCONNECTED) {
                    // The replay profile drops back to Disconnected at EOF.
                    Log.i(TAG, "replay finished: batches=${st.batches} " +
                            "samples=${st.samples} lostPkgs=${st.lostPackages}")
                    appLog("App: replay done: Replay finished (${st.batches} batches, ${st.samples} samples)")
                    setStatus("Replay finished (${st.batches} batches, ${st.samples} samples)")
                    replayProfile = null
                    replayMac = null
                    replayPaused = false
                    pauseReplayBtn.isEnabled = false
                    removeDeviceState(mac)
                }
            }
        }
        p.setOnDeviceInfoUpdateListener { _, updated ->
            // A config switch mid-capture re-rates the streams: rebuild the
            // rate-dependent rings and re-sync the radio selection (checked
            // state only; the buttons stay disabled during replay).
            Log.i(TAG, "replay device info update: eeg=${updated.eegSampleRate} " +
                    "ecg=${updated.ecgSampleRate} imu=${updated.imuSampleRate}")
            st.info = updated
            mainHandler.post {
                syncSampleRateBuffers(st, updated)
                if (mac == currentMac) {
                    updateLinkInfo(updated)
                    syncSampleRateChecked(updated.eegSampleRate)
                }
            }
        }
        setCurrentDevice(mac)
        Log.i(TAG, "replay started: path=$path mac=$mac realtime=$realtime info=$info")
        setStatus("Replaying $path (mac=$mac, realtime=$realtime) ...")
    }

    private fun stopReplay() {
        val mac = replayMac
        if (mac == null) {
            setStatus("No replay running")
            return
        }
        val rp = replayProfile
        replayProfile = null
        replayMac = null
        replayPaused = false
        pauseReplayBtn.isEnabled = false
        // stopBinReplay joins the replay thread, so keep it off the UI thread;
        // the EOF state event removes the replay device state.
        scope.launch(Dispatchers.IO) {
            val result = controller.stopBinReplay(mac)
            appLog("User: stop replay -> $result", if (result == "OK") "I" else "W", rp)
            Log.i(TAG, "stopBinReplay($mac) -> $result")
            withContext(Dispatchers.Main) {
                setStatus("stopBinReplay -> $result")
            }
        }
    }

    /**
     * Pause/resume the running replay (Qt MainWindow::onReplayPauseResume
     * parity): the button text follows the state; a non-OK result keeps the
     * previous state.
     */
    private fun onReplayPauseResume() {
        val mac = replayMac ?: return
        val action = if (replayPaused) "resume" else "pause"
        val result = if (replayPaused) controller.resumeBinReplay(mac)
                     else controller.pauseBinReplay(mac)
        appLog("User: $action replay -> $result", if (result == "OK") "I" else "W", replayProfile)
        if (result != "OK") {
            setStatus("Replay pause/resume failed: $result")
            return
        }
        replayPaused = !replayPaused
        pauseReplayBtn.text = if (replayPaused) "Resume Replay" else "Pause Replay"
        setStatus(if (replayPaused) "Replay paused" else "Replaying ...")
    }

    /**
     * Parses the bin file in the path field to CSV (Qt "Analyze Bin" parity):
     * the CSV lands next to the bin (".bin" -> ".csv"), parseBinToCsv runs on
     * a worker thread (it blocks for the whole parse), and the button is
     * re-entry guarded while a parse runs.
     */
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

    /**
     * Feeds one device's batch list into its views. Runs on the data worker
     * thread - the WaveformView/CubeView append/set methods are
     * synchronized, and the redraw is timer-driven. Batches for a device
     * whose state is already gone (disconnect raced the queue) are dropped.
     */
    private fun processBatches(mac: String, dataList: List<SensorData>) {
        val st = synchronized(deviceStates) { deviceStates[mac] } ?: return
        for (d in dataList) {
            st.batches++
            st.samples += d.channelCount.toLong() * d.sampleCount
            st.lostPackages += d.lostPackageCount
            // Probe the batch once up front. false = stale batch: Your data
            // process runs too slow cause it.
            val fresh = d.isDataValid()
            // Per-type rate/loss bookkeeping (Qt DeviceState::appendData
            // parity); the IMU aggregate additionally counts its segments.
            accountBatch(st, d, fresh)
            if (d.dataType == DataType.NTF_IMU) accountImuSegments(st, d, fresh)
            // One-time per-type probe: dump metadata and the first sample so
            // a data/layout mismatch is visible in logcat.
            if (st.diagTypes.add(d.dataType)) {
                val s = d.getChannelSample(0, 0)
                Log.i(TAG, "diag[$mac] type=${d.dataType} ch=${d.channelCount} n=${d.sampleCount} " +
                        "mask=${d.channelMask} startIdx=${d.startSampleIndex} rate=${d.sampleRate} " +
                        "fresh(0,0)=${d.isDataValid()} | " +
                        "sample(0,0)=" + (if (s != null) "idx=${s.sampleIndex} data=${s.data}" else "null"))
            }
            // Bio page: auto mode routing (EMG/EEG/PPG). maybeReconfigureBio
            // repairs the mode from observed batches; a channel-count change
            // re-layouts the slots.
            maybeReconfigureBio(st, d)
            // Bio slots bound to this stream (Qt DeviceState ring append
            // parity): each bound slot reads its one channel; bio types pass
            // through the device's Live Filter before entering the ring.
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
                    // Aggregate stream (new EMG devices): acc 0-2 / gyro 3-5 /
                    // euler 6-8 / quat 9-12 (w=9, x=10, y=11, z=12).
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
                    // Gesture box: the last sample of the batch carries the
                    // current gesture data (Qt DeviceState parity).
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
        // The stats labels refresh with the UI timer; the per-batch logcat
        // heartbeat below is throttled for headless verification (~1 line/sec).
        val now = System.currentTimeMillis()
        if (now - st.lastLogMs >= 1000) {
            st.lastLogMs = now
            Log.i(TAG, "data[$mac]: batches=${st.batches} samples=${st.samples} lostPkgs=${st.lostPackages}")
        }
    }

    /**
     * Per-type stats bookkeeping for one batch (Qt DeviceState::appendData
     * parity): latest cumulative lost-package count per type, valid-sample
     * rate counting (masked/lost slots excluded), nominal rate/channels, and
     * the stream-start wall clock + first-packet delay for the rate line.
     */
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

    /** Per-segment rate bookkeeping of an NTF_IMU aggregate batch (Qt
     * DeviceState::appendImuSegments parity: acc 0-2 / gyro 3-5 / euler 6-8 /
     * quat 9-12). Lost-package bookkeeping stays with the top-level batch. */
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

    /**
     * Latest impedance per bio channel (Qt DeviceState appendData parity):
     * the bio batches carry the per-channel impedance readout; the last
     * sample of the batch is the newest. Masked-out channels and stale
     * batches are skipped; unknown slots stay -1.
     */
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

    /**
     * Impedance side texts of one device's bio slots (Qt
     * MainWindow::refreshBioSideTexts parity): "%.2f KOhm" on the right
     * margin, green <= 500, orange <= 999, red above; unknown (-1) keeps the
     * previous text. UI thread (called from the UI refresh timer).
     */
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

    /** Latest sample of a 4-channel quaternion segment drives the cube. */
    private fun feedCube(st: DeviceUiState, d: SensorData, quatChannelOffset: Int) {
        val last = d.sampleCount - 1
        if (last < 0) return
        // Probe the batch once; skip the pose update on failure.
        // false = stale batch: Your data process runs too slow cause it.
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
        // Per-type batch/loss lines (Qt "Packet Loss Stats" parity), in the
        // fixed display order; only types that have delivered data appear.
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

    /**
     * Measured-vs-nominal rate line (Qt DeviceState::buildRateText parity):
     * "<type> <actual> / <nominal>Hz" per type that delivered data in the
     * last window, plus the stream-start wall clock and the first-packet
     * delay; empty when nothing is known yet.
     */
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

    /**
     * Clears one device's data views (connect/replay start): a new data
     * session, so in-flight spectrum results from the old session are
     * dropped, and the device's live filter state is rebuilt. The impedance
     * readouts and the gesture box fall back to "unknown" (Qt
     * DeviceState::clearBuffers parity).
     */
    private fun clearDataViews(st: DeviceUiState) {
        st.dataSession++
        st.liveFilter.reset()
        for (v in st.bioSlots) v.clear()
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

    /**
     * Writes one application event line into the SDK log (Python demo
     * _app_log parity): into the given (or current) device's profile log
     * when a device is the subject, else into the controller log, so the
     * lines share the SDK's internal log timeline. The level is the first
     * character of `level` (D/I/W/E, case-insensitive; default "I").
     */
    private fun appLog(message: String, level: String = "I", sensor: SensorProfile? = null) {
        val target = sensor ?: currentState()?.profile
        if (target != null) {
            target.log(message, level)
        } else if (::controller.isInitialized) {
            controller.log(message, level)
        }
    }

    // ---- lifecycle ---------------------------------------------------------------

    override fun onDestroy() {
        appLog("App: demo window closing")
        scope.cancel()
        mainHandler.removeCallbacks(uiRefreshTimer)
        fftExecutor.shutdownNow()
        dataWorkerStop.set(true)
        dataWorker?.interrupt()
        // Fire-and-forget teardown (the listeners may already be dead).
        replayMac?.let { controller.stopBinReplay(it) }
        replayProfile = null
        replayMac = null
        val states = synchronized(deviceStates) { deviceStates.values.toList() }
        for (st in states) {
            if (!st.isReplay) st.profile?.disconnect(null)
        }
        SensorController.terminate()
        super.onDestroy()
    }
}
