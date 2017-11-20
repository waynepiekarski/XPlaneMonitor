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


package net.waynepiekarski.xplanemonitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class BarView extends View {

    private Paint mPaintNormal;
    private Paint mPaintWarning;
    private double mValue;                 // Current value
    private double mMax;                   // Maximum +/- value allowed
    private double mWarning;               // Warning +/- with different color
    private final static int mHeight = 20; // Height of the view in pixels

    public BarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mPaintNormal = new Paint();
        mPaintNormal.setColor(Color.BLUE);
        mPaintWarning = new Paint();
        mPaintWarning.setColor(Color.RED);
        reset();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec){
        int parentWidth = MeasureSpec.getSize(widthMeasureSpec);
        this.setMeasuredDimension(parentWidth, mHeight);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        Paint p;
        if ((mValue < -mWarning) || (mValue > mWarning))
            p = mPaintWarning;
        else
            p = mPaintNormal;

        // Draw a bar from the center out to the left or right depending on the mValue
        if (mValue >= 0)
            canvas.drawRect(canvas.getWidth()/2, 0, (int)(canvas.getWidth()/2.0 + mValue/mMax*canvas.getWidth()/2.0), canvas.getHeight()-1, p);
        else // Cannot render right to left so need to flip around X arguments
            canvas.drawRect((int)(canvas.getWidth()/2.0 + mValue/mMax*canvas.getWidth()/2.0), 0, canvas.getWidth()/2, canvas.getHeight()-1, p);
    }

    public void setValue(double in) {
        if (mValue != in) {
            mValue = in;
            invalidate();
        }
    }

    public void setMaximum(double in) {
        if (mMax != in) {
            mMax = in;
            mValue = 0.0;
            invalidate();
        }
    }

    public void setWarning(double in) {
        if (mWarning != in) {
            mWarning = in;
            invalidate();
        }
    }

    public void reset() {
        mMax = 1.0;
        mWarning = 1.0;
        mValue = 0.0;
        invalidate();
    }
}
