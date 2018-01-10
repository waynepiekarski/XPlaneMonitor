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
import android.util.AttributeSet
import android.widget.Button

class XButton(context: Context, attrs: AttributeSet) : Button(context, attrs)  {
    public var mState = false

    fun setState(_state: Boolean) {
        mState = _state
        setPressed(mState)
    }

    fun setState(_value: Float) {
        mState = (_value > 0.0)
        setPressed(mState)
    }

    fun setInverseState(_value: Float) {
        mState = (_value < 1.0)
        setPressed(mState)
    }

    fun getInverseState(): Float {
        return if(mState) 0.0f else 1.0f
    }

    fun getState(): Boolean {
        return mState
    }
}
