// ---------------------------------------------------------------------
//
// XPlaneMonitor
//
// Copyright (C) 2017 Wayne Piekarski
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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class BarView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private val mPaintNormal = Paint()
    private val mPaintWarning = Paint()
    private var mValue: Double = 0.toDouble()   // Current value
    private var mMax: Double = 0.toDouble()     // Maximum +/- value allowed
    private var mWarning: Double = 0.toDouble() // Warning +/- with different color
    private val mHeight = 20                    // Height of the view in pixels

    init {
        mPaintNormal.color = Color.BLUE
        mPaintWarning.color = Color.RED
        reset()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentWidth = View.MeasureSpec.getSize(widthMeasureSpec)
        this.setMeasuredDimension(parentWidth, mHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val p: Paint
        if (mValue < -mWarning || mValue > mWarning)
            p = mPaintWarning
        else
            p = mPaintNormal

        // Draw a bar from the center out to the left or right depending on the mValue
        if (mValue >= 0)
            canvas.drawRect((canvas.width / 2).toFloat(), 0f, (canvas.width / 2.0 + mValue / mMax * canvas.width / 2.0).toInt().toFloat(), (canvas.height - 1).toFloat(), p)
        else
        // Cannot render right to left so need to flip around X arguments
            canvas.drawRect((canvas.width / 2.0 + mValue / mMax * canvas.width / 2.0).toInt().toFloat(), 0f, (canvas.width / 2).toFloat(), (canvas.height - 1).toFloat(), p)
    }

    fun setValue(arg: Double) {
        if (mValue != arg) {
            mValue = arg
            invalidate()
        }
    }

    fun setMaximum(arg: Double) {
        if (mMax != arg) {
            mMax = arg
            mValue = 0.0
            invalidate()
        }
    }

    fun setWarning(arg: Double) {
        if (mWarning != arg) {
            mWarning = arg
            invalidate()
        }
    }

    fun reset() {
        mMax = 1.0
        mWarning = 1.0
        mValue = 0.0
        invalidate()
    }
}
