package com.oymotion.sensorcapiktdemo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.oymotion.sensor.capi.SensorData
import kotlin.math.max

/**
 * Multi-channel scrolling waveform view (RingBuffer semantics follow the Qt
 * demo's DeviceState): one float ring per channel, batches are appended
 * sequentially, a forward jump in startSampleIndex is filled with zeros, and
 * stale/out-of-range slots read as zero (the batch path never throws).
 *
 * Threading: appendBatch/clear are synchronized and safe to call from the
 * data worker thread; the drawing itself always runs on the UI thread,
 * driven by a periodic UI timer.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    private var viewChannels: Int = 8,
    capacity: Int = 1250
) : View(context) {

    // Ring length; mutable so rebuildForRate can re-size the time window.
    private var capacity: Int = capacity

    private val lock = Any()
    private var rings = Array(viewChannels) { FloatArray(capacity) }
    private var writePos = 0
    private var filled = 0
    private var lastSampleIndex = -1

    // Nominal rate of the appended data (drives the FFT spectra); 0 until
    // the first batch or rebuildForRate.
    @Volatile private var sampleRate = 0f

    // Bumped on every ring reset/rebuild; a spectrum result computed from an
    // older generation is stale and dropped.
    @Volatile var ringGeneration = 0
        private set

    var title: String = ""

    /**
     * Curve color override (Qt WaveformWidget setSource colorIndex parity):
     * >= 0 picks the palette slot for the curve instead of the channel index,
     * so a paged/single-channel view keeps the color of the real channel.
     * Set from the UI thread.
     */
    @Volatile var colorIndex = -1

    /**
     * Right-side annotation (Qt WaveformWidget setSideText parity): drawn in
     * the reserved right margin, vertically centered (the bio slots use it
     * for the impedance readout). Empty = hidden. Set from the UI thread.
     */
    @Volatile var sideText: String = ""
    @Volatile var sideColor: Int = Color.WHITE

    /**
     * Centered text shown while the ring is still empty (an unbound bio slot
     * sets it to "Waiting for data ...", a blank slot leaves it empty).
     */
    @Volatile var placeholder: String = ""

    /** Current channel count (for channel-count mismatch checks). */
    val channels: Int
        get() = viewChannels

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val zeroPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.DKGRAY
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textSize = 22f
    }
    private val bgPaint = Paint().apply { color = Color.rgb(28, 28, 30) }
    private val sideTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
    }
    // Reserved right margin for the side text (Qt WaveformWidget kSideMargin
    // parity, density-scaled); the plot area never uses it.
    private val sideMargin = 66f * context.resources.displayMetrics.density
    private val paths = Array(viewChannels) { Path() }

    private val channelColors = intArrayOf(
        Color.rgb(0, 200, 200), Color.rgb(230, 80, 200), Color.rgb(230, 210, 60),
        Color.rgb(230, 90, 60), Color.rgb(90, 200, 90), Color.rgb(90, 130, 240),
        Color.rgb(200, 140, 240), Color.rgb(240, 160, 60),
        Color.rgb(120, 220, 160), Color.rgb(220, 120, 140),
        Color.rgb(160, 200, 230), Color.rgb(200, 230, 140),
        Color.rgb(250, 120, 120)
    )

    /** Reconfigures the channel count (drops current content). */
    fun setChannels(channels: Int) {
        synchronized(lock) {
            viewChannels = channels
            rings = Array(channels) { FloatArray(capacity) }
            writePos = 0
            filled = 0
            lastSampleIndex = -1
            ringGeneration++
        }
        postInvalidate()
    }

    fun clear() {
        synchronized(lock) {
            for (r in rings) r.fill(0f)
            writePos = 0
            filled = 0
            lastSampleIndex = -1
            ringGeneration++
        }
        postInvalidate()
    }

    /**
     * Rebuilds the rings for a new nominal sample rate, keeping the
     * channel count: zeroed content, write position reset, and length =
     * rate * windowSeconds so the horizontal time window stays consistent
     * with the actual data rate. No-op when the rate is not positive or
     * the computed length already matches. Drawing reads the ring length
     * at paint time, so no further refresh is needed.
     */
    fun rebuildForRate(rate: Float, windowSeconds: Double) {
        if (rate <= 0f) return
        val newCapacity = max(1, (rate * windowSeconds).toInt())
        synchronized(lock) {
            sampleRate = rate
            if (newCapacity == capacity) return
            capacity = newCapacity
            rings = Array(viewChannels) { FloatArray(newCapacity) }
            writePos = 0
            filled = 0
            lastSampleIndex = -1
            ringGeneration++
        }
        postInvalidate()
    }

    /**
     * Appends one batch: reads `channels` channels of `data` starting at
     * `srcChannelOffset`. One isDataValid() probe decides the whole batch
     * (false = stale batch: Your data process runs too slow cause it);
     * isChannelEnabled gates masked-out channels to zero; the remaining
     * single values go through getData. `channelFilter` (when given) runs
     * per channel on the extracted batch before the samples enter the ring
     * (the bio waveforms pass the Live Filter here).
     */
    fun appendBatch(data: SensorData, srcChannelOffset: Int, channels: Int,
                    channelFilter: ((channel: Int, vals: FloatArray) -> Unit)? = null) {
        val n = minOf(channels, viewChannels, data.channelCount - srcChannelOffset)
        if (n <= 0 || data.sampleCount <= 0) return
        if (data.sampleRate > 0) sampleRate = data.sampleRate
        val fresh = data.isDataValid()
        val enabled = BooleanArray(n) { data.isChannelEnabled(srcChannelOffset + it) }
        // Channel-major values first, so the optional channel filter sees one
        // channel's whole batch at once.
        val vals = Array(n) { ch ->
            FloatArray(data.sampleCount) { i ->
                if (fresh && enabled[ch]) data.getData(srcChannelOffset + ch, i) else 0f
            }
        }
        if (channelFilter != null) {
            for (ch in 0 until n) channelFilter(srcChannelOffset + ch, vals[ch])
        }
        synchronized(lock) {
            // Zero-fill a forward sample-index gap (lost packages).
            if (lastSampleIndex >= 0 && data.startSampleIndex > lastSampleIndex + 1) {
                val gap = minOf(data.startSampleIndex - lastSampleIndex - 1, capacity)
                repeat(gap) { pushColumn(FloatArray(n)) }
            }
            val column = FloatArray(n)
            for (i in 0 until data.sampleCount) {
                for (ch in 0 until n) {
                    column[ch] = vals[ch][i]
                }
                pushColumn(column)
            }
            lastSampleIndex = data.startSampleIndex + data.sampleCount - 1
        }
    }

    /** Time-ordered ring snapshot for the spectrum worker. */
    class SpectrumSnapshot(
        val channels: Array<FloatArray>,
        val rate: Float,
        val generation: Int,
    )

    /**
     * Returns a time-ordered (oldest -> newest) copy of the full ring plus
     * the nominal rate and ring generation, or null while the rate is
     * unknown (no data appended yet). The snapshot is a private copy, so the
     * worker thread can compute on it while the ring keeps filling.
     */
    fun snapshotForSpectrum(): SpectrumSnapshot? {
        synchronized(lock) {
            if (sampleRate <= 0f || capacity < 16) return null
            val snap = Array(viewChannels) { ch ->
                FloatArray(capacity) { i -> rings[ch][(writePos + i) % capacity] }
            }
            return SpectrumSnapshot(snap, sampleRate, ringGeneration)
        }
    }

    // Caller must hold lock.
    private fun pushColumn(column: FloatArray) {
        for (ch in column.indices) {
            rings[ch][writePos] = column[ch]
        }
        writePos = (writePos + 1) % capacity
        if (filled < capacity) filled++
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val channels: Int
        val count: Int
        val end: Int
        val cap: Int
        val ringsSnap: Array<FloatArray>
        synchronized(lock) {
            channels = viewChannels
            count = filled
            end = writePos
            cap = capacity
            ringsSnap = rings
        }
        if (channels <= 0) return
        // The plot area leaves the right margin for the side text.
        val plotW = width.toFloat() - sideMargin
        if (count == 0) {
            if (placeholder.isNotEmpty()) {
                canvas.drawText(placeholder, 8f, height / 2f, textPaint)
            }
            return
        }
        val rowH = height.toFloat() / channels
        if (title.isNotEmpty()) {
            canvas.drawText(title, 8f, textPaint.textSize + 2f, textPaint)
        }
        for (ch in 0 until channels) {
            val top = ch * rowH
            val mid = top + rowH / 2f
            canvas.drawLine(0f, mid, plotW, mid, zeroPaint)

            var maxAbs = 1e-6f
            val snapshot: FloatArray
            synchronized(lock) {
                snapshot = FloatArray(count)
                for (k in 0 until count) {
                    val idx = (end - count + k + cap) % cap
                    val v = ringsSnap[ch][idx]
                    snapshot[k] = v
                    val a = if (v < 0) -v else v
                    if (a > maxAbs) maxAbs = a
                }
            }
            val path = paths[ch % paths.size]
            path.rewind()
            val scale = (rowH / 2f - 4f) / maxAbs
            for (k in 0 until count) {
                val x = k * plotW / max(1, cap - 1)
                val y = mid - snapshot[k] * scale
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val colorIdx = if (colorIndex >= 0) colorIndex else ch
            linePaint.color = channelColors[colorIdx % channelColors.size]
            canvas.drawPath(path, linePaint)
            // Single-channel slots carry the channel label in `title`.
            if (channels > 1) canvas.drawText("ch$ch", 6f, mid - 4f, textPaint)
        }
        if (sideText.isNotEmpty()) {
            sideTextPaint.color = sideColor
            val ty = height / 2f - (sideTextPaint.descent() + sideTextPaint.ascent()) / 2f
            canvas.drawText(sideText, plotW + 4f, ty, sideTextPaint)
        }
    }
}
