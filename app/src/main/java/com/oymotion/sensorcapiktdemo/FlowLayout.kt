package com.oymotion.sensorcapiktdemo

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

// Horizontal flow layout: wraps children to the next line when full.
class FlowLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    var horizontalSpacing = 0
    var verticalSpacing = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            if (x > 0 && x + cw > width) {
                x = 0
                y += lineHeight + verticalSpacing
                lineHeight = 0
            }
            x += cw + horizontalSpacing
            lineHeight = maxOf(lineHeight, ch)
        }
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(y + lineHeight + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            if (x > paddingLeft && x - paddingLeft + cw > width) {
                x = paddingLeft
                y += lineHeight + verticalSpacing
                lineHeight = 0
            }
            child.layout(x, y, x + cw, y + ch)
            x += cw + horizontalSpacing
            lineHeight = maxOf(lineHeight, ch)
        }
    }
}
