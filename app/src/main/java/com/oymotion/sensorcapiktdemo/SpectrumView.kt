package com.oymotion.sensorcapiktdemo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

// One-sided magnitude spectrum of the snapshot channels.
internal object SpectrumCompute {

    class Result(val freqs: FloatArray, val mags: Array<FloatArray>)

    private const val PI = 3.14159265358979323846

    fun compute(channels: Array<FloatArray>, rate: Float): Result? {
        if (channels.isEmpty() || rate <= 0f) {
            return null
        }
        val n = channels[0].size
        if (n < 16) {
            return null
        }
        var nfft = 1
        while (nfft < n) {
            nfft = nfft shl 1
        }
        // Hann window.
        val window = DoubleArray(n)
        var winSum = 0.0
        for (i in 0 until n) {
            window[i] = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
            winSum += window[i]
        }
        val freqs = FloatArray(nfft / 2 + 1) { k -> k * rate / nfft }
        val mags = ArrayList<FloatArray>(channels.size)
        val re = DoubleArray(nfft)
        val im = DoubleArray(nfft)
        for (ch in channels) {
            val m = minOf(n, ch.size)
            for (i in 0 until nfft) {
                re[i] = if (i < m) ch[i] * window[i] else 0.0
                im[i] = 0.0
            }
            fft(re, im, nfft)
            val scale = 2.0 / maxOf(winSum, 1e-12)
            val row = FloatArray(nfft / 2 + 1) { k ->
                (scale * hypot(re[k], im[k])).toFloat()
            }
            mags.add(row)
        }
        return Result(freqs, mags.toTypedArray())
    }

    // In-place iterative radix-2 FFT.
    private fun fft(re: DoubleArray, im: DoubleArray, n: Int) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wlenR = cos(ang)
            val wlenI = sin(ang)
            var i = 0
            while (i < n) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                    val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val nwr = wr * wlenR - wi * wlenI
                    wi = wr * wlenI + wi * wlenR
                    wr = nwr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

// Magnitude-spectrum strip below a waveform view.
class SpectrumView(context: Context) : View(context) {

    private val lock = Any()
    private var freqs = FloatArray(0)
    private var mags: Array<FloatArray> = emptyArray()
    private var placeholder = "Waiting for data ..."

    // Per-channel labels drawn top-left.
    @Volatile var labels: Array<String> = emptyArray()

    // Channel colors.
    private val channelColors = intArrayOf(
        Color.rgb(0, 200, 200), Color.rgb(230, 80, 200), Color.rgb(230, 210, 60),
        Color.rgb(230, 90, 60), Color.rgb(90, 200, 90), Color.rgb(90, 130, 240),
        Color.rgb(200, 140, 240), Color.rgb(240, 160, 60),
        Color.rgb(120, 220, 160), Color.rgb(220, 120, 140),
        Color.rgb(160, 200, 230), Color.rgb(200, 230, 140),
        Color.rgb(250, 120, 120)
    )

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.rgb(90, 90, 90)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 150, 150)
        textSize = 22f
    }
    private val bgPaint = Paint().apply { color = Color.rgb(28, 28, 30) }
    private val path = Path()

    fun setResult(f: FloatArray, m: Array<FloatArray>) {
        synchronized(lock) {
            freqs = f
            mags = m
        }
        postInvalidate()
    }

    fun clear() {
        synchronized(lock) {
            freqs = FloatArray(0)
            mags = emptyArray()
        }
        postInvalidate()
    }

    fun setPlaceholder(text: String) {
        placeholder = text
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val axisStrip = axisPaint.textSize + 8f   // bottom strip: axis text
        val plotL = 2f
        val plotT = 2f
        val plotR = (width - 2).toFloat()
        val plotB = height - axisStrip
        if (plotR - plotL < 2 || plotB - plotT < 2) return
        canvas.drawRect(plotL, plotT, plotR - 1, plotB - 1, borderPaint)

        val f: FloatArray
        val m: Array<FloatArray>
        synchronized(lock) {
            f = freqs
            m = mags
        }
        if (f.size < 2 || m.isEmpty()) {
            axisPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(placeholder, (plotL + plotR) / 2f,
                (plotT + plotB) / 2f + axisPaint.textSize / 3f, axisPaint)
            axisPaint.textAlign = Paint.Align.LEFT
            return
        }

        val fMax = f[f.size - 1].toDouble()
        if (fMax <= 0.0) {
            return
        }

        // Auto Y range across all channels.
        var peak = 0.0
        for (row in m) {
            for (v in row) {
                peak = max(peak, v.toDouble())
            }
        }
        val yMax = if (peak > 0.0) peak * 1.1 else 1.0

        canvas.save()
        canvas.clipRect(plotL, plotT, plotR, plotB)
        for (ch in m.indices) {
            val row = m[ch]
            val color = channelColors[ch % channelColors.size]
            path.rewind()
            val count = minOf(row.size, f.size)
            for (i in 0 until count) {
                val x = (plotL + f[i] / fMax * (plotR - plotL - 1)).toFloat()
                val y = ((plotB - 1) - row[i] / yMax * (plotB - plotT - 2)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            linePaint.color = color
            canvas.drawPath(path, linePaint)

            labelPaint.color = color
            val label = if (ch < labels.size) labels[ch] else "ch$ch"
            canvas.drawText(label, plotL + 4,
                plotT + labelPaint.textSize * (ch + 1), labelPaint)
        }
        canvas.restore()

        // Frequency axis endpoints.
        val axisY = plotB + axisPaint.textSize
        canvas.drawText("0", plotL + 2, axisY, axisPaint)
        axisPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(String.format(Locale.US, "%.1f Hz", fMax), plotR - 2, axisY, axisPaint)
        axisPaint.textAlign = Paint.Align.LEFT
    }
}
