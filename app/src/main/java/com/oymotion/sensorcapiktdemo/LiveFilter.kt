package com.oymotion.sensorcapiktdemo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Real-time band filter for the bio waveforms (EMG/EEG/ECG/BRTH/PPG/SpO2),
 * ported from the Qt demo's LiveFilter (DemoNewMulti Live Filter combo
 * parity): a causal 4th-order Butterworth bandpass whose per-channel state
 * carries over between data batches, applied on the data thread right
 * before the samples enter the display rings. The selected band is a UI
 * choice shared by every device; switching the band (or a stream sample-rate
 * change) rebuilds the filter state, and the rings replace old samples
 * naturally as new data arrives.
 */
class LiveFilter {

    companion object {
        // (label, low Hz, high Hz); index 0 = Off. The Greek letters are
        // written as unicode escapes to keep the source file ASCII-only.
        private val BAND_LABELS = arrayOf(
            "Off",
            "\u03B4 0.5-4Hz",
            "\u03B8 4-8Hz",
            "\u03B1 8-13Hz",
            "\u03B2 13-30Hz",
            "\u03B3 30-45Hz",
        )
        private val BAND_LO = doubleArrayOf(0.0, 0.5, 4.0, 8.0, 13.0, 30.0)
        private val BAND_HI = doubleArrayOf(0.0, 4.0, 8.0, 13.0, 30.0, 45.0)
        private const val ORDER = 4
        private const val PI = 3.14159265358979323846

        /** Band index 0 is Off. bandLabels() feeds the filter spinner. */
        fun bandLabels(): List<String> = BAND_LABELS.toList()
    }

    private class Biquad(var b0: Double, var b1: Double, var b2: Double,
                         val a1: Double, val a2: Double)

    private class StreamState {
        var band = 0
        var rate = 0f
        var sections: List<Biquad> = emptyList()

        // Per-section unit-step steady state, used as each channel's initial
        // filter state (scipy sosfilt_zi equivalent).
        var zi0: List<DoubleArray> = emptyList()

        // channel -> per-section running state.
        val channels = HashMap<Int, Array<DoubleArray>>()
    }

    private val lock = Any()
    private var band = 0
    private val streams = HashMap<Int, StreamState>()    // key: data type

    /** UI thread. Out-of-range indexes select Off. */
    fun setBand(bandIndex: Int) {
        synchronized(lock) {
            band = if (bandIndex > 0 && bandIndex < BAND_LABELS.size) bandIndex else 0
            // A band switch rebuilds every stream's filter state; old ring
            // contents scroll out with new data, so the rings are not cleared.
            streams.clear()
        }
    }

    /** Currently selected band index (0 = Off). */
    fun band(): Int = synchronized(lock) { band }

    /** Drops all designed filters and channel states (device switch / clear). */
    fun reset() {
        synchronized(lock) { streams.clear() }
    }

    /**
     * Data thread: filters one channel batch in place. Pass-through when Off,
     * when the rate is unknown/invalid, or when the band top exceeds the
     * Nyquist frequency (e.g. the 1 Hz SpO2 stream).
     */
    fun apply(dataType: Int, channel: Int, vals: FloatArray, sampleRate: Float) {
        if (vals.isEmpty()) {
            return
        }
        synchronized(lock) {
            if (band == 0) {
                return
            }
            val st = streams.getOrPut(dataType) { StreamState() }
            if (st.band != band || st.rate != sampleRate) {
                // Band or sample-rate change: redesign and reset every
                // channel state.
                st.sections = emptyList()
                st.zi0 = emptyList()
                st.channels.clear()
                st.band = band
                st.rate = sampleRate
                val designed = design(band, sampleRate.toDouble())
                if (designed == null) {
                    // Invalid band/rate (e.g. the band top exceeds Nyquist):
                    // keep the empty section list as the pass-through marker
                    // until the band or rate changes again.
                    return
                }
                st.sections = designed
                // Unit-step steady state per section (scipy sosfilt_zi
                // equivalent): with a constant input u, a section settles at
                // output y = G*u where G is its DC gain, and the states
                // follow from the fixed point.
                val zi0 = ArrayList<DoubleArray>(designed.size)
                var u = 1.0
                for (s in designed) {
                    val g = (s.b0 + s.b1 + s.b2) / (1.0 + s.a1 + s.a2)
                    val y = g * u
                    zi0.add(doubleArrayOf(y - s.b0 * u, s.b2 * u - s.a2 * y))
                    u = y
                }
                st.zi0 = zi0
            }
            if (st.sections.isEmpty()) {
                return
            }
            val chState = st.channels.getOrPut(channel) {
                Array(st.zi0.size) { i -> st.zi0[i].copyOf() }
            }
            for (i in vals.indices) {
                var x = vals[i].toDouble()
                for (s in st.sections.indices) {
                    val q = st.sections[s]
                    val z = chState[s]
                    val y = q.b0 * x + z[0]
                    z[0] = q.b1 * x - q.a1 * y + z[1]
                    z[1] = q.b2 * x - q.a2 * y
                    x = y
                }
                vals[i] = x.toFloat()
            }
        }
    }

    /** Minimal complex arithmetic for the filter design math. */
    private class C(val re: Double, val im: Double) {
        operator fun plus(o: C) = C(re + o.re, im + o.im)
        operator fun minus(o: C) = C(re - o.re, im - o.im)
        operator fun times(o: C) = C(re * o.re - im * o.im, re * o.im + im * o.re)
        operator fun div(o: C): C {
            val d = o.re * o.re + o.im * o.im
            return C((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d)
        }

        operator fun plus(d: Double) = C(re + d, im)
        operator fun times(d: Double) = C(re * d, im * d)

        fun conj() = C(re, -im)
        fun abs() = hypot(re, im)

        companion object {
            fun polar(r: Double, theta: Double) = C(r * cos(theta), r * sin(theta))
            fun real(v: Double) = C(v, 0.0)
            fun sqrt(c: C): C {
                val m = sqrt(c.abs())
                val a = atan2(c.im, c.re) / 2.0
                return C(m * cos(a), m * sin(a))
            }
        }
    }

    /**
     * Butterworth bandpass design (order 4): analog prototype -> lp2bp ->
     * bilinear, grouped into second-order sections. Returns null when the
     * band is invalid for the rate.
     */
    private fun design(bandIndex: Int, fs: Double): List<Biquad>? {
        if (bandIndex <= 0 || bandIndex >= BAND_LABELS.size || fs <= 0.0) {
            return null
        }
        val lo = BAND_LO[bandIndex]
        val hi = BAND_HI[bandIndex]
        if (hi >= fs / 2.0) {
            return null   // band top beyond the Nyquist frequency
        }
        // Prewarp to the analog domain.
        val w1 = 2.0 * fs * tan(PI * lo / fs)
        val w2 = 2.0 * fs * tan(PI * hi / fs)
        val bw = w2 - w1
        val wo = sqrt(w1 * w2)
        val fs2 = 2.0 * fs

        // Analog Butterworth lowpass prototype poles (no finite zeros, gain 1).
        val poles = ArrayList<C>()
        val zeros = ArrayList<C>()   // z-plane zeros, filled below
        for (k in 0 until ORDER) {
            val ang = PI * (2.0 * k + 1 + ORDER) / (2.0 * ORDER)
            val p = C.polar(1.0, ang)
            // lp2bp: each pole maps to the roots of s^2 - bw*p*s + wo^2 = 0.
            val mid = p * (0.5 * bw)
            val disc = C.sqrt(mid * mid - C.real(wo * wo))
            val sp1 = mid + disc
            val sp2 = mid - disc
            // Bilinear transform z = (2fs + s) / (2fs - s).
            poles.add((sp1 + fs2) / (C.real(fs2) - sp1))
            poles.add((sp2 + fs2) / (C.real(fs2) - sp2))
        }
        // Prototype zeros: ORDER zeros at s=0 (-> z=+1) plus ORDER zeros at
        // infinity (-> z=-1).
        for (k in 0 until ORDER) {
            zeros.add(C.real(1.0))
            zeros.add(C.real(-1.0))
        }
        // Gain: k_bp = bw^order; k_z = k_bp * prod(2fs - z_s) / prod(2fs - p_s)
        // over the finite analog zeros (all at s=0) and poles.
        val gain = C.real(bw.pow(ORDER)).times(fs2.pow(ORDER))  // prod over the s=0 zeros
        var denom = C.real(1.0)
        for (k in 0 until ORDER) {
            val ang = PI * (2.0 * k + 1 + ORDER) / (2.0 * ORDER)
            val p = C.polar(1.0, ang)
            val mid = p * (0.5 * bw)
            val disc = C.sqrt(mid * mid - C.real(wo * wo))
            denom = denom * ((C.real(fs2) - (mid + disc)) * (C.real(fs2) - (mid - disc)))
        }
        val kz = (gain / denom).re

        // Group into biquads: pair each pole with its conjugate and hand the
        // pair its two nearest zeros (the overall response is
        // pairing-independent).
        val sections = ArrayList<Biquad>()
        while (poles.isNotEmpty()) {
            val p = poles.removeAt(poles.size - 1)
            val pc0 = p.conj()
            var best = 0
            for (i in 1 until poles.size) {
                if ((poles[i] - pc0).abs() < (poles[best] - pc0).abs()) {
                    best = i
                }
            }
            val pc = poles.removeAt(best)
            val zpair = arrayOfNulls<C>(2)
            for (j in 0 until 2) {
                var bz = 0
                for (i in 1 until zeros.size) {
                    val d = min((zeros[i] - p).abs(), (zeros[i] - pc).abs())
                    val db = min((zeros[bz] - p).abs(), (zeros[bz] - pc).abs())
                    if (d < db) {
                        bz = i
                    }
                }
                zpair[j] = zeros.removeAt(bz)
            }
            val z0 = zpair[0]!!
            val z1 = zpair[1]!!
            sections.add(
                Biquad(
                    b0 = 1.0,
                    b1 = -(z0 + z1).re,
                    b2 = (z0 * z1).re,
                    a1 = -(p + pc).re,
                    a2 = (p * pc).re,
                )
            )
        }
        // Overall gain on the first section.
        sections[0].b0 *= kz
        sections[0].b1 *= kz
        sections[0].b2 *= kz
        return sections
    }
}
