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

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.android.synthetic.main.activity_main.*

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.DecimalFormat
import java.util.TreeMap
import kotlin.concurrent.thread

class MainActivity : Activity(), UDPReceiver.OnReceiveUDP, MulticastReceiver.OnReceiveMulticast {

    internal var xplaneAddress: InetAddress? = null
    internal var dref_listener: UDPReceiver? = null
    internal var becn_listener: MulticastReceiver? = null
    private var manualAddress: String = ""
    private var manualInetAddress: InetAddress? = null
    private var connectShutdown = false

    internal var mapDREF = TreeMap<String, Float>()
    internal var mapRREF = TreeMap<String, Float>()
    internal var sequence = 0
    internal var fourDecimal = DecimalFormat("0.0000")
    internal var oneDecimal = DecimalFormat("0.0")
    internal var zeroDecimal = DecimalFormat("#")
    internal var lastFlapsDesired = "0"
    internal var lastFlapsActual = "0"
    internal lateinit var googleMap: GoogleMap
    internal lateinit var googleMapMarker: Marker
    internal lateinit var googleMapLine: Marker

    internal var globalLatitude = 0.0f
    internal var globalLongitude = 0.0f
    internal var globalHeading = 0.0f

    internal var globalAirspeed = 0.0f
    internal var globalNav1Distance = 0.0f
    internal var globalAltitude = 0.0f

    internal var landingLightsText = arrayOfNulls<TextView>(4)
    internal var landingLightsValues = FloatArray(4)
    internal var genericLightsText = arrayOfNulls<TextView>(10)
    internal var genericLightsValues = FloatArray(10)

    internal var efis_mode_state = 0
    internal var efis_range_state = 0


    override fun onConfigurationChanged(config: Configuration) {
        Log.d(Const.TAG, "onConfigurationChanged")
        super.onConfigurationChanged(config)
    }

    fun resetEverything() {
        Log.d(Const.TAG, "Resetting internal map and all UI elements")
        mapDREF.clear()
        mapRREF.clear()
        updateDebugUI()
        resetIndicators()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        versionText.text = "v" + BuildConfig.VERSION_NAME + " " + BuildConfig.VERSION_CODE + " " + BuildConfig.BUILD_TYPE
        resetButton.setOnClickListener {
            resetEverything()
        }
        simulateButton.setOnClickListener {
            Log.d(Const.TAG, "Simulating some data")
            onReceiveUDP(dref_listener!!, sample_groundspeed)
            onReceiveUDP(dref_listener!!, sample_data)

            Log.d(Const.TAG, "Processing res/raw/xplane_dump.raw file")
            val stream = resources.openRawResource(R.raw.xplane_dump)
            val packet = ByteArray(510)
            var count = 0
            try {
                while(true) {
                    val len = stream.read(packet)
                    if (len != packet.size)
                        break
                    onReceiveUDP(dref_listener!!, packet)
                    count++
                }
                Log.d(Const.TAG, "Finished processing rew/raw/xplane_dump.raw with $count packets")
                stream.close()
            } catch (e: IOException) {
                Log.e(Const.TAG, "Could not read from xplane_dump.raw " + e)
            }

            globalLatitude += 1f
            globalLongitude += 1f
            globalHeading += 1f
            Log.d(Const.TAG, "Moving latitude and longitude to $globalLatitude $globalLongitude")
            setItemMap(globalLatitude, globalLongitude, globalHeading)
        }

        switchMainButton.setOnClickListener {
            Log.d(Const.TAG, "Enabling main tab")
            layoutMain.visibility = View.VISIBLE
            layoutDebug.visibility = View.GONE
            layoutMap.visibility = View.GONE
            updateDebugUI()
        }

        switchDebugButton.setOnClickListener {
            Log.d(Const.TAG, "Enabling debug tab")
            layoutMain.visibility = View.GONE
            layoutDebug.visibility = View.VISIBLE
            layoutMap.visibility = View.GONE
            updateDebugUI()
        }

        switchMapButton.setOnClickListener {
            Log.d(Const.TAG, "Enabling map tab")
            layoutMain.visibility = View.GONE
            layoutDebug.visibility = View.GONE
            layoutMap.visibility = View.VISIBLE
            updateDebugUI()
        }

        switchDetectButton.setOnClickListener {
            Log.d(Const.TAG, "Popup for manual hostname")
            popupManualHostname()
        }

        fun button_to_cmnd(button: Button, cmnd: String) {
            button.setOnClickListener {
                check_thread(xplaneAddress, "Button for command $cmnd") {
                    dref_listener!!.sendCMND(xplaneAddress!!, cmnd)
                }
            }
        }

        fun button_to_actions(button: XButton, cmnd: String, dref: String) {
            button.setOnClickListener {
                val value = button.getInverseState()
                check_thread(xplaneAddress, "Button for CMND $cmnd, and DREF $dref to $value") {
                    dref_listener!!.sendCMND(xplaneAddress!!, cmnd)
                    dref_listener!!.sendDREF(xplaneAddress!!, dref, value)
                }
            }
        }

        fun button_to_dref(button: XButton, dref: String, value: Float) {
            button.setOnClickListener {
                check_thread(xplaneAddress, "Button for DREF $dref to $value") {
                    dref_listener!!.sendDREF(xplaneAddress!!, dref, value)
                }
            }
        }

        efis_mode_change.setOnClickListener {
            val efis_mode_prev = efis_mode_state
            val efis_mode_next = if(efis_mode_state >= 3) 0 else efis_mode_state+1
            efis_mode_state = efis_mode_next
            check_thread(xplaneAddress, "Change EFIS mode to $efis_mode_next from $efis_mode_prev") {
                dref_listener!!.sendDREF(xplaneAddress!!, "1-sim/ndpanel/1/hsiModeRotary", efis_mode_state.toFloat()) // XP737
                var rewrite = efis_mode_next
                if (rewrite == 3)
                    rewrite = 4
                dref_listener!!.sendDREF(xplaneAddress!!, "sim/cockpit/switches/EFIS_map_submode[0]", rewrite.toFloat()) // FF767
                dref_listener!!.sendDREF(xplaneAddress!!, "laminar/B738/EFIS_control/capt/map_mode_pos", efis_mode_state.toFloat()) // ZB737
            }
        }

        fun efis_range_change(dir: Int) {
            val efis_range_prev = efis_range_state
            var efis_range_next = efis_range_state+dir
            if (efis_range_next >= 5)
                efis_range_next = 5
            else if (efis_range_next < 0)
                efis_range_next = 0
            efis_range_state = efis_range_next
            check_thread(xplaneAddress, "Change EFIS range with direction $dir to $efis_range_next from $efis_range_prev") {
                dref_listener!!.sendDREF(xplaneAddress!!, "1-sim/ndpanel/1/hsiRangeRotary", efis_range_state.toFloat())                  // FF767
                dref_listener!!.sendDREF(xplaneAddress!!, "sim/cockpit/switches/EFIS_map_range_selector[0]", efis_range_state.toFloat()) // XP737
                if (dir > 0)
                    dref_listener!!.sendCMND(xplaneAddress!!, "laminar/B738/EFIS_control/capt/map_range_up") // ZB737
                else
                    dref_listener!!.sendCMND(xplaneAddress!!, "laminar/B738/EFIS_control/capt/map_range_dn") // ZB737
            }
        }

        map_zoom_out.setOnClickListener {
            efis_range_change(1)
        }

        map_zoom_in.setOnClickListener {
            efis_range_change(-1)
        }

        button_to_actions(efis_button_tfc, "laminar/B738/EFIS_control/capt/push_button/tfc_press", "1-sim/ndpanel/1/hsiRangeButton")
        button_to_actions(efis_button_wxr, "laminar/B738/EFIS_control/capt/push_button/wxr_press", "1-sim/ndpanel/1/hsiWxr")
        button_to_actions(efis_button_sta, "laminar/B738/EFIS_control/capt/push_button/sta_press", "1-sim/ndpanel/1/map2")
        button_to_actions(efis_button_wpt, "laminar/B738/EFIS_control/capt/push_button/wpt_press", "1-sim/ndpanel/1/map5")
        button_to_actions(efis_button_arpt, "laminar/B738/EFIS_control/capt/push_button/arpt_press", "1-sim/ndpanel/1/map3")
        button_to_actions(efis_button_data, "laminar/B738/EFIS_control/capt/push_button/data_press", "1-sim/ndpanel/1/map4")
        button_to_cmnd(efis_button_pos, "laminar/B738/EFIS_control/capt/push_button/pos_press")
        button_to_actions(efis_button_terr, "laminar/B738/EFIS_control/capt/push_button/terr_press", "1-sim/ndpanel/1/hsiTerr")
        // TODO: There does not appear to be a CTR button or dataref in the XP737, only Zibo seems to support the press and not receive an update
        button_to_actions(efis_button_ctr, "laminar/B738/EFIS_control/capt/push_button/ctr_press", "1-sim/ndpanel/1/hsiModeButton")

        all_lights_on.setOnClickListener {
            check_thread(xplaneAddress, "Set all lights on") {
                for (i in 0 until landingLightsText.size)
                    dref_listener!!.sendDREF(xplaneAddress!!, "sim/cockpit2/switches/landing_lights_switch[$i]", 1.0f)
                for (i in 0 until genericLightsText.size)
                    dref_listener!!.sendDREF(xplaneAddress!!, "sim/cockpit2/switches/generic_lights_switch[$i]", 1.0f)
            }
        }

        all_lights_off.setOnClickListener {
            check_thread(xplaneAddress, "Set all lights off") {
                for (i in 0 until landingLightsText.size)
                    dref_listener!!.sendDREF(xplaneAddress!!, "sim/cockpit2/switches/landing_lights_switch[$i]", 0.0f)
                for (i in 0 until genericLightsText.size)
                    dref_listener!!.sendDREF(xplaneAddress!!, "sim/cockpit2/switches/generic_lights_switch[$i]", 0.0f)
            }
        }

        for (i in 0 until landingLightsText.size) {
            val t = TextView(this)
            t.setText("L$i")
            t.setPadding(20, 20, 20, 20)
            layout_lights.addView(t)
            t.setOnClickListener {
                val inverted = 1.0f - landingLightsValues[i]
                check_thread(xplaneAddress, "Clicked landing_lights_switch[$i] from " + landingLightsValues[i] + " to new " + inverted) {
                    dref_listener!!.sendDREF(xplaneAddress!!, "sim/cockpit2/switches/landing_lights_switch[$i]", inverted)
                }
            }
            landingLightsText[i] = t
        }
        for (i in 0 until genericLightsText.size) {
            val t = TextView(this)
            t.setText("G$i")
            t.setPadding(20, 20, 20, 20)
            layout_lights.addView(t)
            t.setOnClickListener {
                val inverted = 1.0f - genericLightsValues[i]
                check_thread(xplaneAddress, "Clicked generic_lights_switch[$i] from " + genericLightsValues[i] + " to new " + inverted) {
                    dref_listener!!.sendDREF(xplaneAddress!!, "sim/cockpit2/switches/generic_lights_switch[$i]", inverted)
                }
            }
            genericLightsText[i] = t
        }

        // Reset display elements to a known state
        resetIndicators()

        // Based on https://stackoverflow.com/questions/16536414/how-to-use-mapview-in-android-using-google-map-v2
        mapView.onCreate(savedInstanceState)
        // Do an async call which replaces deprecated getMap(): https://stackoverflow.com/questions/31371865/replace-getmap-with-getmapasync
        mapView.getMapAsync { map ->
            googleMap = map
            val pos = LatLng(0.0, 0.0) // Move the map to a default origin
            googleMap.moveCamera(CameraUpdateFactory.newLatLng(pos))
        }
    }

    fun mirror_xhsi_value(name: String, src: String, dest: String, value: Float) {
        if (name != src) {
            // Do not mirror the value if the name and value do not match the src
            // Don't send back 737 values because XHSI already understands those
            return
        }
        thread(start = true) {
            Log.d(Const.TAG, "XHSI: Started thread/send for mirroring from [$src] to [$dest] value $value")
            dref_listener!!.sendDREF(xplaneAddress!!, dest, value)
        }

    }

    // The user can click on the connectText and specify a X-Plane hostname manually
    private fun changeManualHostname(hostname: String) {
        if (hostname.isEmpty()) {
            Log.d(Const.TAG, "Clearing override X-Plane hostname for automatic mode, saving to prefs, restarting networking")
            manualAddress = hostname
            val sharedPref = getPreferences(Context.MODE_PRIVATE)
            with(sharedPref.edit()){
                putString("manual_address", manualAddress)
                commit()
            }
            switchDetectButton.text = "Auto BECN"
            restartNetworking()
        } else {
            Log.d(Const.TAG, "Setting override X-Plane hostname to $manualAddress")
            // Lookup the IP address on a background thread
            thread(start = true) {
                try {
                    manualInetAddress = InetAddress.getByName(hostname)
                } catch (e: UnknownHostException) {
                    // IP address was not valid, so ask for another one and exit this thread
                    Handler(Looper.getMainLooper()).post { popupManualHostname(error=true) }
                    return@thread
                }

                // We got a valid IP address, so we can now restart networking on the UI thread
                Handler(Looper.getMainLooper()).post {
                    manualAddress = hostname
                    Log.d(Const.TAG, "Converted manual X-Plane hostname [$manualAddress] to ${manualInetAddress}, saving to prefs, restarting networking")
                    val sharedPref = getPreferences(Context.MODE_PRIVATE)
                    with(sharedPref.edit()) {
                        putString("manual_address", manualAddress)
                        commit()
                    }
                    switchDetectButton.text = "Manual: " + manualAddress
                    restartNetworking()
                }
            }
        }
    }

    private fun popupManualHostname(error: Boolean = false) {
        val builder = AlertDialog.Builder(this)
        if (error)
            builder.setTitle("Invalid entry! Specify X-Plane hostname or IP")
        else
            builder.setTitle("Specify X-Plane hostname or IP")

        val input = EditText(this)
        input.setText(manualAddress)
        builder.setView(input)
        builder.setPositiveButton("Manual Override") { dialog, which -> changeManualHostname(input.text.toString()) }
        builder.setNegativeButton("Revert") { dialog, which -> dialog.cancel() }
        builder.setNeutralButton("Automatic Multicast") { dialog, which -> changeManualHostname("") }
        builder.show()
    }


    fun restartNetworking() {
        Log.d(Const.TAG, "restartNetworking()")
        resetEverything()
        xplaneStatus.text = "Re-detecting X-Plane"

        Log.d(Const.TAG, "Cleaning up existing sockets")
        if (dref_listener != null) {
            dref_listener!!.stopListener()
            dref_listener = null
        }
        if (becn_listener != null) {
            becn_listener!!.stopListener()
            becn_listener = null
        }

        if (connectShutdown) {
            Log.d(Const.TAG, "Will not restart BECN listener since connectShutdown is set")
        } else {
            if (manualAddress.isEmpty()) {
                xplaneStatus.setText("Waiting for X-Plane BECN")
                Log.d(Const.TAG, "Starting X-Plane BECN listener since connectShutdown is not set")
                becn_listener = MulticastReceiver(Const.BECN_ADDRESS, Const.BECN_PORT, this)
            } else {
                Log.d(Const.TAG, "Manual address $manualAddress specified, skipping any auto-detection")
                check(becn_listener == null) { "becn_listener should not be initialized" }
                dref_listener = UDPReceiver(Const.UDP_DREF_PORT, this)
                xplaneAddress = manualInetAddress
                xplaneStatus.setText("X-Plane: " + xplaneAddress!!.getHostAddress())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()

        connectShutdown = false

        // Retrieve the manual address from shared preferences
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        val prefAddress = sharedPref.getString("manual_address", "")
        Log.d(Const.TAG, "Found preferences value for manual_address = [$prefAddress]")

        // Pass on whatever this string is, and will end up calling restartNetworking()
        changeManualHostname(prefAddress)
    }

    override fun onPause() {
        Log.d(Const.TAG, "onPause(), cancelling UDP listeners")
        connectShutdown = true // Prevent new BECN listeners starting up in restartNetworking
        if (dref_listener != null) {
            dref_listener!!.stopListener()
            dref_listener = null
        }
        if (becn_listener != null) {
            becn_listener!!.stopListener()
            becn_listener = null
        }
        mapView.onPause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus:Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Only implement full-screen in API >= 19, older Android brings them back on each click
        if (hasFocus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    fun getCompactFloat(arg: Float): String {
        // Round floats so ridiculously tiny values get called 0
        return if (arg < 0.00001)
            "0"
        else
            "" + arg

    }

    fun setBrakePercent(v: TextView, label: String, f: Float) {
        val percent = (f * 100).toInt()
        setItemString(v, label, "" + percent + "%", percent >= 1)
    }

    fun setItemString(v: TextView, label: String, value: String, red: Boolean) {
        v.text = label + "\n" + value
        if (red)
            v.setBackgroundColor(Color.RED)
        else
            v.setBackgroundColor(Color.GREEN)
    }

    fun setItemMap(latitude: Float, longitude: Float, heading: Float) {
        if (layoutMap.visibility != View.VISIBLE)
            return
        if (!::googleMap.isInitialized)
            return
        mapCoordinates.text = ("LatLong: "
                + (if (latitude < 0) "S" else "N")
                + fourDecimal.format(latitude)
                + " "
                + (if (longitude < 0) "W" else "E")
                + fourDecimal.format(longitude)
                + " - Heading: "
                + zeroDecimal.format(heading))

        val pos = LatLng(latitude.toDouble(), longitude.toDouble())
        googleMap.moveCamera(CameraUpdateFactory.newLatLng(pos))
        if (!::googleMapMarker.isInitialized) {
            // Draw an airplane icon centered around the coordinates
            googleMapMarker = googleMap.addMarker(MarkerOptions()
                    .position(pos)
                    .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_airplane_alpha))
                    .anchor(0.5f, 0.5f)
                    .title("Airplane"))
        }
        if (!::googleMapLine.isInitialized) {
            // Draw a line in the direction, need to use an image since there is no way to rotate a poly-line
            googleMapLine = googleMap.addMarker(MarkerOptions()
                    .position(pos)
                    .icon(BitmapDescriptorFactory.fromResource(R.drawable.line512))
                    .title("Line"))
        }
        googleMapMarker.position = pos
        googleMapMarker.rotation = heading
        googleMapLine.position = pos
        googleMapLine.rotation = heading
    }

    // Compute the feet-per-minute rate to get to NAV1 DME with 0 altitude at current airspeed
    fun setGlideEstimate(distance_nm: Float, airspeed_knots: Float, altitude_feet: Float) {
        val hours = distance_nm / airspeed_knots
        val minutes = hours * 60.0f

        val fpm = -altitude_feet / minutes

        setItemString(itemEstimateMins, "NAV1 Est Mins", if (airspeed_knots < 100 || distance_nm < 0.1) "N/A" else oneDecimal.format(minutes.toDouble()) + "mins", false)
        setItemString(itemEstimateFPM,  "NAV1 Est FPM",  if (airspeed_knots < 100 || distance_nm < 0.1) "N/A" else oneDecimal.format(fpm.toDouble()) + "fpm", false)
    }

    override fun onTimeoutMulticast(ref: MulticastReceiver) {
        Log.d(Const.TAG, "Received indication that we never received any BECN packets, need to keep waiting")
        xplaneStatus.setText("Re-detecting X-Plane - BECN timeout")
    }

    override fun onFailureMulticast(ref: MulticastReceiver) {
        Log.d(Const.TAG, "Received indication the network is not ready, cannot open socket")
        xplaneStatus.setText("No network")
    }

    private fun requestRREF() {
        // Request values that we are interested in getting updates for
        check_thread(xplaneAddress, "Requesting values via RREF") {
            dref_listener!!.sendRREF(xplaneAddress!!, "sim/cockpit/switches/EFIS_map_submode[0]", 2)        // Map XP737
            dref_listener!!.sendRREF(xplaneAddress!!, "1-sim/ndpanel/1/hsiModeRotary", 2)                   // Map FF767
            dref_listener!!.sendRREF(xplaneAddress!!, "laminar/B738/EFIS_control/capt/map_mode_pos", 2)     // Map ZB737

            dref_listener!!.sendRREF(xplaneAddress!!, "sim/cockpit/switches/EFIS_map_range_selector[0]", 2) // Range XP737
            dref_listener!!.sendRREF(xplaneAddress!!, "laminar/B738/EFIS/capt/map_range", 2)                // Range ZB737
            dref_listener!!.sendRREF(xplaneAddress!!, "1-sim/ndpanel/1/hsiRangeRotary", 2)                  // Range FF767

            dref_listener!!.sendRREF(xplaneAddress!!, "sim/cockpit/switches/EFIS_shows_tcas[0]", 2)      // TFC XP737
            dref_listener!!.sendRREF(xplaneAddress!!, "1-sim/ndpanel/1/hsiRangeButton", 2)               // TFC FF767

            dref_listener!!.sendRREF(xplaneAddress!!, "sim/cockpit/switches/EFIS_shows_airports[0]", 2)  // ARPT XP737
            dref_listener!!.sendRREF(xplaneAddress!!, "laminar/B738/EFIS/EFIS_airport_on", 2)            // ARPT ZB737
            dref_listener!!.sendRREF(xplaneAddress!!, "1-sim/ndpanel/1/map3", 2)                         // ARPT FF767

            dref_listener!!.sendRREF(xplaneAddress!!, "sim/cockpit/switches/EFIS_shows_waypoints[0]", 2) // WPT XP737
            dref_listener!!.sendRREF(xplaneAddress!!, "laminar/B738/EFIS/EFIS_fix_on", 2)                // WPT ZB737
            dref_listener!!.sendRREF(xplaneAddress!!, "1-sim/ndpanel/1/map5", 2)                         // WPT FF767

            dref_listener!!.sendRREF(xplaneAddress!!, "sim/cockpit/switches/EFIS_shows_VORs[0]", 2)      // STA XP737
            dref_listener!!.sendRREF(xplaneAddress!!, "laminar/B738/EFIS/EFIS_vor_on", 2)                // STA ZB737
            dref_listener!!.sendRREF(xplaneAddress!!, "1-sim/ndpanel/1/map2", 2)                         // STA FF767

            dref_listener!!.sendRREF(xplaneAddress!!, "sim/cockpit/switches/EFIS_shows_weather[0]", 2)   // WXR XP737
            dref_listener!!.sendRREF(xplaneAddress!!, "laminar/B738/EFIS/EFIS_wx_on", 2)                 // WXR ZB737
            // No need for this since EFIS_shows_weather[0] seems to pass this on
            // dref_listener!!.sendRREF(xplaneAddress!!, "1-sim/ndpanel/1/hsiWxr", 2) // WXR

            dref_listener!!.sendRREF(xplaneAddress!!, "1-sim/ndpanel/1/map4", 2)                        // DATA FF767
            // dref_listener!!.sendRREF(xplaneAddress!!, "sim/cockpit/switches/EFIS_shows_data[0]", 2)              // DATA, does not exist in XP737 or ZB737?

            dref_listener!!.sendRREF(xplaneAddress!!, "1-sim/ndpanel/1/hsiTerr", 2)                     // TERR, no equivalent in XP737 or ZB737?

            dref_listener!!.sendRREF(xplaneAddress!!, "1-sim/ndpanel/1/hsiModeButton", 2)               // CTR FF767, no equivalent state on ZB737 or XP737

            dref_listener!!.sendRREF(xplaneAddress!!, "sim/operation/misc/frame_rate_period[0]", 2)
            dref_listener!!.sendRREF(xplaneAddress!!, "sim/cockpit2/controls/left_brake_ratio[0]", 2)
            dref_listener!!.sendRREF(xplaneAddress!!, "sim/cockpit2/controls/right_brake_ratio[0]", 2)
            dref_listener!!.sendRREF(xplaneAddress!!, "sim/cockpit2/controls/parking_brake_ratio[0]", 2)
            dref_listener!!.sendRREF(xplaneAddress!!, "sim/cockpit2/controls/speedbrake_ratio[0]", 2)
            dref_listener!!.sendRREF(xplaneAddress!!, "sim/cockpit/warnings/annunciators/reverse[0]", 2)
            dref_listener!!.sendRREF(xplaneAddress!!, "sim/flightmodel/position/indicated_airspeed[0]", 2)
            dref_listener!!.sendRREF(xplaneAddress!!, "sim/flightmodel/position/y_agl[0]", 2)
            dref_listener!!.sendRREF(xplaneAddress!!, "sim/flightmodel/position/elevation[0]", 2)
            dref_listener!!.sendRREF(xplaneAddress!!, "sim/cockpit2/gauges/indicators/altitude_ft_pilot[0]", 2)
            dref_listener!!.sendRREF(xplaneAddress!!, "sim/cockpit2/controls/flap_handle_deploy_ratio[0]", 2)
            dref_listener!!.sendRREF(xplaneAddress!!, "sim/flightmodel/controls/flaprqst[0]", 2)
            dref_listener!!.sendRREF(xplaneAddress!!, "sim/flightmodel2/gear/tire_vertical_force_n_mtr[0]", 2)
            dref_listener!!.sendRREF(xplaneAddress!!, "sim/flightmodel/forces/g_nrml[0]", 2)
            dref_listener!!.sendRREF(xplaneAddress!!, "sim/cockpit/radios/nav1_dme_dist_m[0]", 2)
            dref_listener!!.sendRREF(xplaneAddress!!, "sim/cockpit/radios/nav2_dme_dist_m[0]", 2)
            dref_listener!!.sendRREF(xplaneAddress!!, "sim/flightmodel/position/vh_ind_fpm", 2)
            dref_listener!!.sendRREF(xplaneAddress!!, "sim/flightmodel/position/latitude", 2)
            dref_listener!!.sendRREF(xplaneAddress!!, "sim/flightmodel/position/longitude", 2)
            dref_listener!!.sendRREF(xplaneAddress!!, "sim/graphics/view/view_heading", 2)

            for (i in 0 until landingLightsText.size)
                dref_listener!!.sendRREF(xplaneAddress!!, "sim/cockpit2/switches/landing_lights_switch[$i]", 2)
            for (i in 0 until genericLightsText.size)
                dref_listener!!.sendRREF(xplaneAddress!!, "sim/cockpit2/switches/generic_lights_switch[$i]", 2)
        }
    }

    override fun onReceiveMulticast(buffer: ByteArray, source: InetAddress, ref: MulticastReceiver) {
        Log.d(Const.TAG, "Received BECN multicast packet from $source")
        Log.d(Const.TAG, "BECN packet printable: " + UDPReceiver.bytesToChars(buffer, buffer.size))
        Log.d(Const.TAG, "BECN packet hex: " + UDPReceiver.bytesToHex(buffer, buffer.size))

        // The BECN listener will only reply once for a new X-Plane IP. If X-Plane restarts then the
        // app needs to be restarted. If X-Plane changes IP address then the app needs restarting too.
        becn_listener!!.stopListener()
        becn_listener = null

        // Open up the UDP listener
        dref_listener = UDPReceiver(Const.UDP_DREF_PORT, this)
        xplaneAddress = source
        xplaneStatus.setText("X-Plane: " + xplaneAddress!!.getHostAddress())
    }

    private fun processRREF(name: String, value: Float) {
        val prev: Float? = mapRREF.get(name)
        if ((prev != null) and (prev == value)) {
            // Value has been seen before and is not a change, so ignore it to save time.
            // This is also handy when different aircraft work with different names and we can ignore unchanging values.
            // TODO: There is a possibility we could have stale values here when switching planes?
            // Log.d(Const.TAG, "Ignoring unchanged value for $name with value $value")
            return
        } else {
            // Log.d(Const.TAG, "Existing $name changing to $value from $prev")
        }
        mapRREF.put(name, value)

        if (name == "sim/operation/misc/frame_rate_period[0]") {
            if (value < 0.0001) {
                itemFPS.text = "FPS\nn/a"
                itemFPS.setBackgroundColor(Color.GRAY)
            } else {
                itemFPS.text = "FPS\n" + oneDecimal.format((1.0f / value).toDouble())
                itemFPS.setBackgroundColor(Color.GREEN)
            }
        } else if (name == "sim/cockpit2/controls/left_brake_ratio[0]") {
            setBrakePercent(itemLeftBrake, "Left Brake", value)
        } else if (name == "sim/cockpit2/controls/right_brake_ratio[0]") {
            setBrakePercent(itemRightBrake, "Right Brake", value)
        } else if (name == "sim/cockpit2/controls/parking_brake_ratio[0]") {
            setBrakePercent(itemParkingBrake, "Parking Brake", value)
        } else if (name == "sim/cockpit2/controls/speedbrake_ratio[0]") {
            setBrakePercent(itemSpeedBrake, "Speed Brake (Air)", value)
        } else if (name == "sim/cockpit/warnings/annunciators/reverse[0]") {
            val bits = value.toInt()
            var engines = ""
            if (bits and 1 == 1) engines += "1"
            if (bits and 2 == 2) engines += "2"
            if (bits and 4 == 4) engines += "3"
            if (bits and 8 == 8) engines += "4"
            setItemString(itemReverseThrust, "Thrust Direction", if (bits != 0) "REVERSE " + engines else "All Forward", bits != 0)
        } else if (name == "sim/flightmodel/position/indicated_airspeed[0]") {
            setItemString(itemIndicatedSpeed, "Indicated Air Speed", oneDecimal.format(value.toDouble()) + "kts", false)
            globalAirspeed = value
            setGlideEstimate(globalNav1Distance, globalAirspeed, globalAltitude)
        } else if (name == "sim/flightmodel/position/y_agl[0]") {
            setItemString(itemAltitudeGround, "Altitude AGL", oneDecimal.format((value * Const.METERS_TO_FEET).toDouble()) + "ft", false)
        } else if (name == "sim/flightmodel/position/elevation[0]") {
            setItemString(itemAltitudeMSL, "Altitude MSL", oneDecimal.format((value * Const.METERS_TO_FEET).toDouble()) + "ft", false)
            globalAltitude = value * Const.METERS_TO_FEET
            setGlideEstimate(globalNav1Distance, globalAirspeed, globalAltitude)
        } else if (name == "sim/cockpit2/gauges/indicators/altitude_ft_pilot[0]") {
            setItemString(itemAltitudeGauge, "Altitude Gauge", oneDecimal.format(value.toDouble()) + "ft", false)
        } else if (name == "sim/cockpit2/controls/flap_handle_deploy_ratio[0]") {
            lastFlapsActual = zeroDecimal.format((40 * value).toDouble())
            setItemString(itemFlapsActual, "Flaps Actual", lastFlapsActual, lastFlapsActual != lastFlapsDesired)
        } else if (name == "sim/flightmodel/controls/flaprqst[0]") {
            lastFlapsDesired = zeroDecimal.format((40 * value).toDouble())
            setItemString(itemFlapsDesired, "Flaps Desired", lastFlapsDesired, value > 0.01)
        } else if (name == "sim/flightmodel2/gear/tire_vertical_force_n_mtr[0]") {
            setItemString(itemForceGear, "Gear Force", oneDecimal.format(value.toDouble()) + "Nm", false)
        } else if (name == "sim/flightmodel/forces/g_nrml[0]") {
            setItemString(itemForceVertical, "Vert Force", oneDecimal.format(value.toDouble()) + "G", value < 0.75 || value > 1.25)
            graphForceVertical.set1Value(value - 1.0) // Center around 1G
            barForceVertical.setValue(value - 1.0)
        } else if (name == "sim/cockpit/radios/nav1_dme_dist_m[0]") {
            setItemString(itemDME1Distance, "NAV1 DME", oneDecimal.format(value.toDouble()) + "Nm", false)
            globalNav1Distance = value
            setGlideEstimate(globalNav1Distance, globalAirspeed, globalAltitude)
        } else if (name == "sim/cockpit/radios/nav2_dme_dist_m[0]") {
            setItemString(itemDME2Distance, "NAV2 DME", oneDecimal.format(value.toDouble()) + "Nm", false)
        } else if (name == "sim/flightmodel/position/vh_ind_fpm") {
            setItemString(itemActualFPM, "Actual FPM", oneDecimal.format(value.toDouble()) + "fpm", value < -3000 || value > 3000)
        } else if (name == "sim/flightmodel/position/latitude") {
            globalLatitude = value
            setItemMap(globalLatitude, globalLongitude, globalHeading)
        } else if (name == "sim/flightmodel/position/longitude") {
            globalLongitude = value
            setItemMap(globalLatitude, globalLongitude, globalHeading)
        } else if (name == "sim/graphics/view/view_heading") {
            setItemString(itemHeading, "True Heading", oneDecimal.format(value.toDouble()) + "deg", false)
            globalHeading = value
            setItemMap(globalLatitude, globalLongitude, globalHeading)
        } else if (name.startsWith("sim/cockpit2/switches/generic_lights_switch[")) {
            // Extract out the number between [ ]
            var s = name.substring(name.indexOf("[") + 1)
            s = s.substring(0, s.indexOf("]"))
            val n = s.toInt()
            val t = genericLightsText[n]
            t!!.setText("G$n")
            if (value.toInt() > 0)
                t.setBackgroundColor(Color.LTGRAY)
            else
                t.setBackgroundColor(Color.GRAY)
            genericLightsValues[n] = value
        } else if (name.startsWith("sim/cockpit2/switches/landing_lights_switch[")) {
            // Extract out the number between [ ]
            var s = name.substring(name.indexOf("[") + 1)
            s = s.substring(0, s.indexOf("]"))
            val n = s.toInt()
            val t = landingLightsText[n]
            t!!.setText("L$n")
            if (value.toInt() > 0)
                t.setBackgroundColor(Color.LTGRAY)
            else
                t.setBackgroundColor(Color.GRAY)
            landingLightsValues[n] = value
        } else if (name == "laminar/B738/EFIS_control/capt/map_mode_pos") {
            val mode: String
            if (value.toInt() == 0)
                mode = "APP"
            else if (value.toInt() == 1)
                mode = "VOR"
            else if (value.toInt() == 2)
                mode = "MAP"
            else if (value.toInt() == 3)
                mode = "PLN"
            else
                mode = "N/A"
            efis_mode_change.text = "EFIS " + mode
            efis_mode_state = value.toInt()
        } else if (name == "sim/cockpit/switches/EFIS_map_submode[0]") {
            val mode: String
            if (value.toInt() == 0)
                mode = "APP"
            else if (value.toInt() == 1)
                mode = "VOR"
            else if (value.toInt() == 2)
                mode = "MAP"
            else if (value.toInt() == 4)
                mode = "PLN"
            else
                mode = "N/A"
            efis_mode_change.text = "EFIS " + mode
            efis_mode_state = value.toInt()
            if (efis_mode_state == 4)
                efis_mode_state = 3
        } else if (name == "1-sim/ndpanel/1/hsiModeRotary") {
            mirror_xhsi_value(name, "1-sim/ndpanel/1/hsiModeRotary", "sim/cockpit/switches/EFIS_map_submode[0]", if (value.toInt() == 3) 4.0f else value)
            val mode: String
            if (value.toInt() == 0)
                mode = "VOR"
            else if (value.toInt() == 1)
                mode = "APP"
            else if (value.toInt() == 2)
                mode = "MAP"
            else if (value.toInt() == 3)
                mode = "PLN"
            else
                mode = "N/A"
            efis_mode_state = value.toInt()
            efis_mode_change.text = "EFIS " + mode
        } else if (name == "sim/cockpit/switches/EFIS_map_range_selector[0]" || name == "1-sim/ndpanel/1/hsiRangeRotary") {
            mirror_xhsi_value(name, "1-sim/ndpanel/1/hsiRangeRotary", "sim/cockpit/switches/EFIS_map_range_selector[0]", value)
            val range = (1 shl value.toInt()) * 10
            map_zoom_range.text = "" + range + "nm"
            efis_range_state = value.toInt()
        } else if (name == "laminar/B738/EFIS/capt/map_range") { // Zibo does the range differently than X-Plane
            val range = (1 shl value.toInt()) * 5
            map_zoom_range.text = "" + range + "nm"
            efis_range_state = value.toInt()
        } else if (name == "sim/cockpit/switches/EFIS_shows_tcas[0]" || name == "1-sim/ndpanel/1/hsiRangeButton") {
            mirror_xhsi_value(name, "1-sim/ndpanel/1/hsiRangeButton", "sim/cockpit/switches/EFIS_shows_tcas[0]", value)
            efis_button_tfc.setState(value)
        } else if (name == "1-sim/ndpanel/1/hsiModeButton") { // No equivalent for ZB737 or XP737
            // hsiModeButton seems to either never change, or always go 0->1->0 very quickly, so perhaps it can never be set in FF767
            mirror_xhsi_value(name, "1-sim/ndpanel/1/hsiModeButton", "sim/cockpit/switches/EFIS_shows_ctr_TODO[0]", value)
            efis_button_ctr.setState(value)
        } else if (name == "sim/cockpit/switches/EFIS_shows_airports[0]" || name == "1-sim/ndpanel/1/map3" || name == "laminar/B738/EFIS/EFIS_airport_on") {
            mirror_xhsi_value(name, "1-sim/ndpanel/1/map3", "sim/cockpit/switches/EFIS_shows_airports[0]", value)
            efis_button_arpt.setState(value)
        } else if (name == "sim/cockpit/switches/EFIS_shows_waypoints[0]" || name == "1-sim/ndpanel/1/map5" || name == "laminar/B738/EFIS/EFIS_fix_on") {
            mirror_xhsi_value(name, "1-sim/ndpanel/1/map5", "sim/cockpit/switches/EFIS_shows_waypoints[0]", value)
            efis_button_wpt.setState(value)
        } else if (name == "sim/cockpit/switches/EFIS_shows_VORs[0]" || name == "1-sim/ndpanel/1/map2" || name == "laminar/B738/EFIS/EFIS_vor_on") {
            mirror_xhsi_value(name, "1-sim/ndpanel/1/map2", "sim/cockpit/switches/EFIS_shows_VORs[0]", value)
            efis_button_sta.setState(value)
        } else if (name == "sim/cockpit/switches/EFIS_shows_data[0]" || name == "1-sim/ndpanel/1/map4") {
            // TODO: Note that sim/cockpit/switches/EFIS_shows_data[0] does not seem to exist in XP737, except it should
            // TODO: mirror_xhsi_value()
            efis_button_data.setState(value)
        } else if (name == "sim/cockpit/switches/EFIS_shows_weather[0]" || name == "1-sim/ndpanel/1/hsiWxr" || name == "laminar/B738/EFIS/EFIS_wx_on") {
            mirror_xhsi_value(name, "1-sim/ndpanel/1/hsiWxr", "sim/cockpit/switches/EFIS_shows_weather[0]", value)
            efis_button_wxr.setState(value)
        } else if (name == "sim/cockpit/switches/EFIS_shows_terrain[0]" || name == "1-sim/ndpanel/1/hsiTerr") {
            // TODO: Note that sim/cockpit/switches/EFIS_shows_terrain[0] does not seem to exist in XP737, except it should
            // TODO: mirror_xhsi_value()
            efis_button_terr.setState(value)
        } else {
            Log.e(Const.TAG, "Unhandled RREF name=$name, value=$value")
        }
    }

    override fun onConnectUDP(ref: UDPReceiver) {
        // Now that we have a connection, send requests for data now
        Log.d(Const.TAG, "UDP socket is ready, send requests for data")
        requestRREF()
    }

    override fun onReceiveUDP(ref: UDPReceiver, buffer: ByteArray) {
        // Log.d(Const.TAG, "onReceiveUDP bytes=" + buffer.length);
        sequence++

        if (buffer.size >= 5 && buffer[0] == 'D'.toByte() && buffer[1] == 'R'.toByte() && buffer[2] == 'E'.toByte() && buffer[3] == 'F'.toByte()) {
            // Handle DREF+ packet type here
            // ["DREF+"=5bytes] [float=4bytes] ["label/name/var[0]\0"=remaining_bytes]
            if (buffer[4] != '+'.toByte()) {
                Log.e(Const.TAG, "Cannot parse [" + buffer[4] + "] when expected '+' symbol")
                return
            }
            val f = ByteBuffer.wrap(buffer, +5, 4).order(ByteOrder.LITTLE_ENDIAN).float
            // Find terminating null for the string
            var zero: Int = 9
            while (zero < buffer.size && buffer[zero] != 0x00.toByte()) {
                zero++
            }
            val name = String(buffer, +9, zero - 9)
            // Log.d(Const.TAG, "Parsed DREF+ with float=" + f + " for variable=" + name);

            // We are not requesting any values via the manual X-Plane UI any more,
            // so nothing should be coming through here any more. Mark them all as unused
            // for the debug dump. Previously we had our big if() statement right here,
            // and keeping it just in case we want to use it later.
            val indicator = false

            if (isDebugUI()) {
                if (indicator) {
                    // We requested this item
                    mapDREF.put("Indicate: " + name, f)
                } else {
                    // We didn't need this item, it will only be visible in the debug dump
                    mapDREF.put("Unused: " + name, f)
                }
            }

            updateDebugUI()
        } else if (buffer.size >= 5 && buffer[0] == 'R'.toByte() && buffer[1] == 'R'.toByte() && buffer[2] == 'E'.toByte() && buffer[3] == 'F'.toByte()) {
            // Handle RREF, packet type here, based on our earlier request
            // ["RREF,"=5bytes]
            // [id=4bytes] [float=4bytes]
            // ...
            // Log.d(Const.TAG, "Found RREF packet bytes=" + buffer.size + ": " + UDPReceiver.bytesToChars(buffer, buffer.size))
            if (buffer[4] != ','.toByte()) {
                Log.e(Const.TAG, "Cannot parse [" + buffer[4] + "] when expected ',' symbol")
                return
            }
            if ((buffer.size-5) % 8 != 0) {
                Log.e(Const.TAG, "Improper RREF message size " + buffer.size + " should be 5+8*n bytes")
                return
            }
            // Read all blocks of 8 bytes in a loop until we run out
            var index = 5
            var item = 0
            while (index < buffer.size) {
                val id = ByteBuffer.wrap(buffer, index, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val value = ByteBuffer.wrap(buffer, index+4, 4).order(ByteOrder.LITTLE_ENDIAN).float
                val name = dref_listener!!.lookupRREF(id)
                if (id < dref_listener!!.rref_base) {
                    Log.e(Const.TAG, "#$item, idx=$index: Ignoring invalid id=$id, value=$value less than base ")
                } else if (name != null) {
                    // Log.d(Const.TAG, "#$item, idx=$index: Parsed RREF with name=$name, id=$id, value=$value")
                    processRREF(name, value)
                } else {
                    Log.e(Const.TAG, "#$item, idx=$index: Ignoring unexpected RREF with id=$id, value=$value")
                }
                index += 8
                item ++
            }
        } else if (buffer.size >= 5 && buffer[0] == 'D'.toByte() && buffer[1] == 'A'.toByte() && buffer[2] == 'T'.toByte() && buffer[3] == 'A'.toByte() && buffer[4] == '*'.toByte()) {
            // Log.d(Const.TAG, "Ignoring legacy DATA* packet with size ${buffer.size}")
        } else {
            Log.e(Const.TAG, "Ignoring unknown packet: " + UDPReceiver.bytesToChars(buffer, buffer.size))
        }
    }

    fun isDebugUI(): Boolean {
        return (layoutDebug.visibility == View.VISIBLE)
    }

    fun updateDebugUI() {
        // If debug mode is not visible, do nothing
        if (!isDebugUI()) {
            return
        }

        // Dump out current list of everything
        var out = "sequence=" + sequence + "\n"
        for ((key, value) in mapDREF) {
            // Log.d(Const.TAG, "Key=" + entry.getKey() + " Value=" + entry.getValue());
            out = out + "\n" + key + " = " + getCompactFloat(value)
        }
        out = out + "\n"
        for ((key, value) in mapRREF) {
            // Log.d(Const.TAG, "Key=" + entry.getKey() + " Value=" + entry.getValue());
            out = out + "\n" + "RREF: " + key + " = " + getCompactFloat(value)
        }
        debugText.text = out
    }

    fun resetIndicators() {
        val name = "$packageName.R\$id"
        try {
            // Set every View starting with "item" to a default gray color
            val c = Class.forName(name)
            val fields = c.fields
            for (i in fields.indices) {
                if (fields[i].name.startsWith("item")) {
                    val res = fields[i].getInt(null)
                    val v = findViewById<TextView>(res)
                    v.setBackgroundColor(Color.GRAY)
                }
            }
            for (i in landingLightsText) {
                i?.setBackgroundColor(Color.GRAY)
            }
            for (i in genericLightsText) {
                i?.setBackgroundColor(Color.GRAY)
            }
        } catch (e: ClassNotFoundException) {
            Log.e(Const.TAG, "resetIndicators exception: Could not locate R.id class with $name")
            e.printStackTrace()
        } catch (e: IllegalAccessException) {
            Log.e(Const.TAG, "resetIndicators exception: Could not access R.id class with $name")
            e.printStackTrace()
        }

        barForceVertical.resetLimits(max=1.0, warn=0.25) // +/- 1G (0G..2G)
        graphForceVertical.resetLimits(max=1.0, size=1) // +/- 1G (0G..2G), only 1 value on the graph
    }

    companion object {

        val sample_groundspeed = byteArrayOf(0x44.toByte(), 0x52.toByte(), 0x45.toByte(), 0x46.toByte(), 0x2B.toByte(), 0xC0.toByte(), 0xBB.toByte(), 0xB0.toByte(), 0x35.toByte(), 0x66.toByte(), 0x61.toByte(), 0x6b.toByte(), 0x65.toByte(), 0x2f.toByte(), 0x66.toByte(), 0x6C.toByte(), 0x69.toByte(), 0x67.toByte(), 0x68.toByte(), 0x74.toByte(), 0x6D.toByte(), 0x6F.toByte(), 0x64.toByte(), 0x65.toByte(), 0x6C.toByte(), 0x2F.toByte(), 0x70.toByte(), 0x6F.toByte(), 0x73.toByte(), 0x69.toByte(), 0x74.toByte(), 0x69.toByte(), 0x6F.toByte(), 0x6E.toByte(), 0x2F.toByte(), 0x67.toByte(), 0x72.toByte(), 0x6F.toByte(), 0x75.toByte(), 0x6E.toByte(), 0x64.toByte(), 0x73.toByte(), 0x70.toByte(), 0x65.toByte(), 0x65.toByte(), 0x64.toByte(), 0x5B.toByte(), 0x30.toByte(), 0x5D.toByte())
        val sample_data = hexStringToByteArray("444154412A000000000E88624133339F4100C079C4A7A6903D4C43903DA78D8F3D0000803F0000803F0200000000005C420000A6428045B14A00C079C400C079C400C079C400C079C400C079C40300000009000000AC660B3561810B3562810B3500C079C40A0000003A8A20353B8A20350D0000000000000000000000000000000000803F0000803F0000803F00000000000000000E0000000000803FA0C7993E000000000000000000C079C400C079C400C079C400C079C4220000006ED897436ED897434FFAA4434FFAA44300000000000000000000000000000000")

        // From http://stackoverflow.com/questions/140131/convert-a-string-representation-of-a-hex-dump-to-a-byte-array-using-java
        fun hexStringToByteArray(s: String): ByteArray {
            val len = s.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }

        fun check_thread(address: InetAddress?, purpose: String, code: () -> Unit) {
            if (address == null) {
                Log.d(Const.TAG, "Skipping thread/send for [$purpose] because remote X-Plane is not discovered yet")
            } else {
                thread(start = true) {
                    Log.d(Const.TAG, "Started thread/send for [$purpose]")
                    code()
                }
            }
        }
    }
}
