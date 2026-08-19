package com.oymotion.sensorcapiktdemo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Quaternion-driven 3D cube (port of the Qt demo's CubeWidget): the
 * quaternion is normalized, converted to a rotation matrix, the 8 unit-cube
 * vertices are rotated and perspective-projected, and the 6 faces are filled
 * far-to-near (painter's algorithm) plus the 12 edges.
 *
 * setQuaternion/clearQuaternion are synchronized and safe to call from the
 * data worker thread; drawing runs on the UI thread, driven by a periodic
 * UI timer.
 */
class CubeView(context: Context) : View(context) {

    private val lock = Any()
    private val quat = doubleArrayOf(1.0, 0.0, 0.0, 0.0) // w, x, y, z
    private var hasQuaternion = false
    var placeholder = "No quaternion data"

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 150, 150)
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }
    private val bgPaint = Paint().apply { color = Color.rgb(28, 28, 30) }
    private val path = Path()

    fun setQuaternion(w: Double, x: Double, y: Double, z: Double) {
        synchronized(lock) {
            quat[0] = w; quat[1] = x; quat[2] = y; quat[3] = z
            hasQuaternion = true
        }
    }

    fun clearQuaternion() {
        synchronized(lock) {
            hasQuaternion = false
            quat[0] = 1.0; quat[1] = 0.0; quat[2] = 0.0; quat[3] = 0.0
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val q: DoubleArray
        val draw: Boolean
        synchronized(lock) {
            q = quat.copyOf()
            draw = hasQuaternion
        }
        if (!draw) {
            canvas.drawText(placeholder, width / 2f, height / 2f, textPaint)
            return
        }

        // Rotation matrix from the quaternion (same formula as the Qt demo).
        var w = q[0]; var x = q[1]; var y = q[2]; var z = q[3]
        val norm = sqrt(w * w + x * x + y * y + z * z)
        if (norm <= 0.0) return
        w /= norm; x /= norm; y /= norm; z /= norm
        val r = arrayOf(
            doubleArrayOf(1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)),
            doubleArrayOf(2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)),
            doubleArrayOf(2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y))
        )

        val scale = min(width, height) * 0.28
        val cx = width / 2.0
        val cy = height / 2.0
        val cameraDist = 4.5

        val rotated = Array(8) { DoubleArray(3) }
        for (i in 0 until 8) {
            for (row in 0 until 3) {
                rotated[i][row] = r[row][0] * VERTICES[i][0] +
                        r[row][1] * VERTICES[i][1] + r[row][2] * VERTICES[i][2]
            }
        }
        val projected = Array(8) { i ->
            val persp = cameraDist / (cameraDist + rotated[i][2])
            floatArrayOf(
                (cx + rotated[i][0] * scale * persp).toFloat(),
                (cy - rotated[i][1] * scale * persp).toFloat()
            )
        }

        // Painter's algorithm: far faces first (larger z = farther).
        val order = (0 until 6).sortedByDescending { f ->
            FACES[f].sumOf { rotated[it][2] }
        }
        for (f in order) {
            path.rewind()
            for (k in 0 until 4) {
                val pt = projected[FACES[f][k]]
                if (k == 0) path.moveTo(pt[0], pt[1]) else path.lineTo(pt[0], pt[1])
            }
            path.close()
            fillPaint.color = FACE_COLORS[f]
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, edgePaint)
        }
    }

    private companion object {
        // Unit cube vertices (same layout as the Qt demo).
        val VERTICES = arrayOf(
            doubleArrayOf(-1.0, -1.0, -1.0), doubleArrayOf(1.0, -1.0, -1.0),
            doubleArrayOf(1.0, 1.0, -1.0), doubleArrayOf(-1.0, 1.0, -1.0),
            doubleArrayOf(-1.0, -1.0, 1.0), doubleArrayOf(1.0, -1.0, 1.0),
            doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(-1.0, 1.0, 1.0)
        )
        val FACES = arrayOf(
            intArrayOf(0, 1, 2, 3), intArrayOf(4, 5, 6, 7), intArrayOf(0, 1, 5, 4),
            intArrayOf(2, 3, 7, 6), intArrayOf(0, 3, 7, 4), intArrayOf(1, 2, 6, 5)
        )
        val FACE_COLORS = intArrayOf(
            Color.rgb(0, 180, 180), Color.rgb(200, 60, 200), Color.rgb(220, 200, 40),
            Color.rgb(200, 60, 60), Color.rgb(60, 160, 60), Color.rgb(60, 100, 220)
        )
    }
}
