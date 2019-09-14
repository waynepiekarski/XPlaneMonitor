// ---------------------------------------------------------------------
//
// XPlaneMonitor
//
// Copyright (C) 2017-2018 Wayne Piekarski
// wayne@tinmith.net http://tinmith.net/wayne
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.
//
// ---------------------------------------------------------------------


package net.waynepiekarski.xplanemonitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class GraphView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private val foreground = Paint()
    private val background = Paint()
    private val leader = Paint()
    private var mMax: Double = -1.0
    private var step: Int = 0
    private var stepNext: Int = 0
    private var stepPrev: Int = 0
    private lateinit var current: DoubleArray
    private lateinit var prev: DoubleArray
    private lateinit var bitmap: Bitmap
    private lateinit var canvas: Canvas
    private val palette = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.CYAN, Color.MAGENTA, Color.YELLOW)
    private val paint = Array(palette.size, { _ -> Paint() })

    init {
        foreground.color = Color.LTGRAY
        background.color = Color.BLACK
        leader.color = Color.DKGRAY
        for (i in palette.indices) {
            paint[i].color = palette[i]
        }
        resetLimits()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentWidth = View.MeasureSpec.getSize(widthMeasureSpec)
        val parentHeight = View.MeasureSpec.getSize(heightMeasureSpec)
        this.setMeasuredDimension(parentWidth, parentHeight)
    }

    private fun clearBitmap() {
        if (::canvas.isInitialized) {
            canvas.drawColor(background.color)
            canvas.drawRect(0f, 0f, (canvas.width - 1).toFloat(), 0f, foreground)
            canvas.drawRect((canvas.width - 1).toFloat(), 0f, (canvas.width - 1).toFloat(), (canvas.height - 1).toFloat(), foreground)
            canvas.drawRect((canvas.width - 1).toFloat(), (canvas.height - 1).toFloat(), 0f, (canvas.height - 1).toFloat(), foreground)
            canvas.drawRect(0f, (canvas.height - 1).toFloat(), 0f, 0f, foreground)
        }
    }

    override fun onDraw(liveCanvas: Canvas) {
        if (!::bitmap.isInitialized || bitmap.width != liveCanvas.width || bitmap.height != liveCanvas.height) {
            bitmap = Bitmap.createBitmap(liveCanvas.width, liveCanvas.height, Bitmap.Config.ARGB_8888)
            canvas = Canvas(bitmap)
            clearBitmap()
        }

        super.onDraw(canvas)

        // Clear out pixels on the current column before we draw here
        canvas.drawLine(step.toFloat(), 0f, step.toFloat(), canvas.height.toFloat(), background)
        canvas.drawLine(stepNext.toFloat(), 0f, stepNext.toFloat(), canvas.height.toFloat(), leader)

        // Plot the latest data at the current column
        if (::current.isInitialized) {
            for (i in current.indices) {
                val x1 = stepPrev
                val y1 = (canvas.height / 2.0 + prev[i] / mMax * canvas.height / 2.0).toInt()
                val x2 = step
                val y2 = (canvas.height / 2.0 + current[i] / mMax * canvas.height / 2.0).toInt()

                // Only draw if there is no wrap-around
                if (x2 > x1)
                    canvas.drawLine(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), paint[i])
            }
        }

        liveCanvas.drawBitmap(bitmap, 0f, 0f, foreground)

        step += 1
        if (step > canvas.width)
            step = 0
        stepNext += 1
        if (stepNext > canvas.width)
            stepNext = 0
        stepPrev += 1
        if (stepPrev > canvas.width)
            stepPrev = 0

        // Save the current values as previous values for the next run
        val temp = prev
        prev = current
        current = temp
    }

    fun setValues(arg: FloatArray) {
        assert(min(arg.size, palette.size) == current.size) {"Mismatch between incoming length " + current.size + " with existing " + arg.size }
        for (i in 0 until min(arg.size, palette.size))
            current[i] = arg[i].toDouble()
        invalidate()
    }

    fun set1Value(arg: Double) {
        assert(1 == current.size) { "Mismatch between incoming length " + current.size + " with existing 1" }
        current[0] = arg
        invalidate()
    }

    fun resetLimits(max: Double = 1.0, size: Int = 1) {
        step = 0
        stepNext = step + 1
        stepPrev = step - 1
        mMax = 1.0

        var length = size
        if (length > palette.size)
            length = palette.size
        if (!::current.isInitialized || length != current.size) {
            current = DoubleArray(length)
            prev = DoubleArray(length)
        }

        clearBitmap()
        mMax = max
        invalidate()
    }

    companion object {

        fun min(a: Int, b: Int): Int {
            return if (a < b)
                a
            else
                b
        }
    }
}
