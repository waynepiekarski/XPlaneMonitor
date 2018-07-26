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
import android.os.*
import android.text.InputType
import android.util.Base64
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

import java.net.InetAddress
import java.net.UnknownHostException
import java.text.DecimalFormat
import kotlin.concurrent.thread

class MainActivity : Activity(), TCPClient.OnTCPEvent, MulticastReceiver.OnReceiveMulticast {

    private var becn_listener: MulticastReceiver? = null
    private var tcp_extplane: TCPClient? = null
    private var connectAddress: String? = null
    private var manualAddress: String = ""
    private var manualInetAddress: InetAddress? = null
    private var connectSupported = false
    private var connectActTailnum: String = ""
    private var connectWorking = false
    private var connectShutdown = false
    private var connectFailures = 0

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

    internal var max_allowable_altitude_ft = 0


    override fun onConfigurationChanged(config: Configuration) {
        Log.d(Const.TAG, "onConfigurationChanged")
        super.onConfigurationChanged(config)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(Const.TAG, "onCreate()")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Add the compiled-in BuildConfig values to the about text
        versionText.text = "XPlaneMonitor v" + Const.getBuildVersion() + " " + BuildConfig.BUILD_TYPE + " " + Const.getBuildId()

        // Add the compiled-in BuildConfig values to the about text
        aboutText.text = aboutText.getText().toString().replace("__VERSION__", "Version: " + Const.getBuildVersion() + " " + BuildConfig.BUILD_TYPE + " build " + Const.getBuildId() + " " + "\nBuild date: " + Const.getBuildDateTime())

        switchMainButton.setOnClickListener {
            Log.d(Const.TAG, "Enabling main tab")
            layoutMain.visibility = View.VISIBLE
            layoutAbout.visibility = View.GONE
            layoutMap.visibility = View.GONE
        }

        switchAboutButton.setOnClickListener {
            Log.d(Const.TAG, "Enabling about tab")
            layoutMain.visibility = View.GONE
            layoutAbout.visibility = View.VISIBLE
            layoutMap.visibility = View.GONE
        }

        switchMapButton.setOnClickListener {
            Log.d(Const.TAG, "Enabling map tab")
            layoutMain.visibility = View.GONE
            layoutAbout.visibility = View.GONE
            layoutMap.visibility = View.VISIBLE
        }

        switchDetectButton.setOnClickListener {
            Log.d(Const.TAG, "Popup for manual hostname")
            popupManualHostname()
        }
        aboutText.setOnClickListener {
            Log.d(Const.TAG, "Popup for manual hostname")
            popupManualHostname()
        }
        versionText.setOnClickListener {
            Log.d(Const.TAG, "Popup for manual hostname")
            popupManualHostname()
        }

        fun button_to_cmnd(button: Button, cmnd: String) {
            button.setOnClickListener {
                Log.d(Const.TAG, "Sending button command $cmnd")
                sendCommand(tcp_extplane, cmnd)
            }
        }

        fun button_to_actions(button: XButton, cmnd: String, dref: String) {
            button.setOnClickListener {
                val value = button.getInverseState()
                Log.d(Const.TAG, "Button for CMND $cmnd, and DREF $dref to $value")
                sendCommand(tcp_extplane, cmnd)
                sendChange(tcp_extplane, dref, value)
            }
        }

        efis_mode_change.setOnClickListener {
            val efis_mode_prev = efis_mode_state
            val efis_mode_next = if(efis_mode_state >= 3) 0 else efis_mode_state+1
            efis_mode_state = efis_mode_next
            Log.d(Const.TAG, "Change EFIS mode to $efis_mode_next from $efis_mode_prev")
            sendChange(tcp_extplane, "1-sim/ndpanel/1/hsiModeRotary", efis_mode_state.toFloat()) // XP737
            var rewrite = efis_mode_next
            if (rewrite == 3)
                rewrite = 4
            sendChange(tcp_extplane, "sim/cockpit/switches/EFIS_map_submode", rewrite.toFloat()) // FF767
            sendChange(tcp_extplane, "laminar/B738/EFIS_control/capt/map_mode_pos", efis_mode_state.toFloat()) // ZB737
        }

        fun efis_range_change(dir: Int) {
            val efis_range_prev = efis_range_state
            var efis_range_next = efis_range_state+dir
            if (efis_range_next >= 5)
                efis_range_next = 5
            else if (efis_range_next < 0)
                efis_range_next = 0
            efis_range_state = efis_range_next
            Log.d(Const.TAG, "Change EFIS range with direction $dir to $efis_range_next from $efis_range_prev")
            sendChange(tcp_extplane, "1-sim/ndpanel/1/hsiRangeRotary", efis_range_state.toFloat())                  // FF767
            sendChange(tcp_extplane, "sim/cockpit/switches/EFIS_map_range_selector", efis_range_state.toFloat()) // XP737
            if (dir > 0)
                sendCommand(tcp_extplane, "laminar/B738/EFIS_control/capt/map_range_up") // ZB737
            else
                sendCommand(tcp_extplane, "laminar/B738/EFIS_control/capt/map_range_dn") // ZB737
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
            Log.d(Const.TAG, "Set all lights on")
            val onLandingLightsValues = FloatArray(landingLightsValues.size) { i -> 1.0f }
            sendChangeArray(tcp_extplane, "sim/cockpit2/switches/landing_lights_switch", onLandingLightsValues)
            val onGenericLightsValues = FloatArray(genericLightsValues.size) { i -> 1.0f }
            sendChangeArray(tcp_extplane, "sim/cockpit2/switches/generic_lights_switch", onGenericLightsValues)
        }

        all_lights_off.setOnClickListener {
            Log.d(Const.TAG, "Set all lights off")
            val onLandingLightsValues = FloatArray(landingLightsValues.size) { i -> 0.0f }
            sendChangeArray(tcp_extplane, "sim/cockpit2/switches/landing_lights_switch", onLandingLightsValues)
            val onGenericLightsValues = FloatArray(genericLightsValues.size) { i -> 0.0f }
            sendChangeArray(tcp_extplane, "sim/cockpit2/switches/generic_lights_switch", onGenericLightsValues)
        }

        for (i in 0 until landingLightsText.size) {
            val t = TextView(this)
            t.setText("L$i")
            t.setPadding(20, 20, 20, 20)
            layout_lights.addView(t)
            t.setOnClickListener {
                val inverted = 1.0f - landingLightsValues[i]
                Log.d(Const.TAG, "Clicked landing_lights_switch[$i] from " + landingLightsValues[i] + " to new " + inverted)
                landingLightsValues[i] = inverted
                sendChangeArray(tcp_extplane, "sim/cockpit2/switches/landing_lights_switch", landingLightsValues)
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
                Log.d(Const.TAG, "Clicked generic_lights_switch[$i] from " + genericLightsValues[i] + " to new " + inverted)
                genericLightsValues[i] = inverted
                sendChangeArray(tcp_extplane, "sim/cockpit2/switches/generic_lights_switch", genericLightsValues)
            }
            genericLightsText[i] = t
        }

        button_to_cmnd(window_heat_l_side, "laminar/B738/toggle_switch/window_heat_l_side")
        button_to_cmnd(window_heat_r_side, "laminar/B738/toggle_switch/window_heat_r_side")
        button_to_cmnd(window_heat_l_fwd, "laminar/B738/toggle_switch/window_heat_l_fwd")
        button_to_cmnd(window_heat_r_fwd, "laminar/B738/toggle_switch/window_heat_r_fwd")
        button_to_cmnd(capt_probes_pos, "laminar/B738/toggle_switch/capt_probes_pos")
        button_to_cmnd(fo_probes_pos,   "laminar/B738/toggle_switch/fo_probes_pos")
        flt_alt_dn.setOnClickListener {
            max_allowable_altitude_ft -= 5000
            if (max_allowable_altitude_ft < 0) max_allowable_altitude_ft = 0
            Log.d(Const.TAG, "Change maximum altitude to $max_allowable_altitude_ft")
            sendChange(tcp_extplane, "sim/cockpit2/pressurization/actuators/max_allowable_altitude_ft", max_allowable_altitude_ft.toFloat())
        }
        flt_alt_up.setOnClickListener {
            max_allowable_altitude_ft += 5000
            if (max_allowable_altitude_ft > 45000) max_allowable_altitude_ft = 45000
            Log.d(Const.TAG, "Change maximum altitude to $max_allowable_altitude_ft")
            sendChange(tcp_extplane, "sim/cockpit2/pressurization/actuators/max_allowable_altitude_ft", max_allowable_altitude_ft.toFloat())
        }
        button_to_cmnd(seatbelt_sign_up, "laminar/B738/toggle_switch/seatbelt_sign_up")
        button_to_cmnd(seatbelt_sign_dn, "laminar/B738/toggle_switch/seatbelt_sign_dn")
        button_to_cmnd(attend, "laminar/B738/push_button/attend")

        // Reset display elements to a known state
        resetIndicators()

        // Based on https://stackoverflow.com/questions/16536414/how-to-use-mapview-in-android-using-google-map-v2
        mapView.onCreate(savedInstanceState)
        // Do an async call which replaces deprecated getMap(): https://stackoverflow.com/questions/31371865/replace-getmap-with-getmapasync
        mapView.getMapAsync { map ->
            googleMap = map
            val pos = LatLng(0.0, 0.0) // Move the map to a default origin
            googleMap.moveCamera(CameraUpdateFactory.newLatLng(pos))
            googleMap.moveCamera(CameraUpdateFactory.zoomTo(10.0f))
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
            sendChange(tcp_extplane, dest, value)
        }
    }

    companion object {
        private var backgroundThread: HandlerThread? = null

        fun doUiThread(code: () -> Unit) {
            Handler(Looper.getMainLooper()).post { code() }
        }

        fun doBgThread(code: () -> Unit) {
            Handler(backgroundThread!!.getLooper()).post { code() }
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
        input.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        builder.setView(input)
        builder.setPositiveButton("Manual Override") { dialog, which -> changeManualHostname(input.text.toString()) }
        builder.setNegativeButton("Revert") { dialog, which -> dialog.cancel() }
        builder.setNeutralButton("Automatic Multicast") { dialog, which -> changeManualHostname("") }
        builder.show()
    }

    private fun sendCommand(tcpRef: TCPClient?, cmnd: String) {
        // Send the command on a separate thread
        doBgThread {
            if ((tcpRef != null) && (tcpRef == tcp_extplane) && connectWorking) {
                tcpRef.writeln("cmd once $cmnd")
            } else {
                Log.d(Const.TAG, "Ignoring command $cmnd since TCP connection is not available")
            }
        }
    }

    private fun sendMessage(tcpRef: TCPClient?, mesg: String) {
        // Send the mesg on a separate thread
        doBgThread {
            if ((tcpRef != null) && (tcpRef == tcp_extplane) && connectWorking) {
                tcpRef.writeln("$mesg")
            } else {
                Log.d(Const.TAG, "Ignoring message $mesg since TCP connection is not available")
            }
        }
    }

    private fun sendRequest(tcpRef: TCPClient?, request: String, resolution: Float = -1.0f) {
        // Send the request on a separate thread
        doBgThread {
            if ((tcpRef != null) && (tcpRef == tcp_extplane) && connectWorking) {
                if (resolution >= 0.0) {
                    tcpRef.writeln("sub $request $resolution")
                    Log.d(Const.TAG, "sendRequest: sub $request $resolution")
                } else {
                    tcpRef.writeln("sub $request")
                    Log.d(Const.TAG, "sendRequest: sub $request")
                }
            } else {
                Log.d(Const.TAG, "Ignoring request for $request since TCP connection is not available")
            }
        }
    }

    private fun sendChange(tcpRef: TCPClient?, dref: String, value: Float) {
        // Send the request on a separate thread
        doBgThread {
            if ((tcpRef != null) && (tcpRef == tcp_extplane) && connectWorking) {
                tcpRef.writeln("set $dref $value")
                Log.d(Const.TAG, "sendChange: set $dref $value")
            } else {
                Log.d(Const.TAG, "Ignoring request for $dref since TCP connection is not available")
            }
        }
    }

    private fun sendChangeArray(tcpRef: TCPClient?, dref: String, values: FloatArray) {
        // Send the request on a separate thread
        doBgThread {
            if ((tcpRef != null) && (tcpRef == tcp_extplane) && connectWorking) {
                val commaStr = values.joinToString(prefix = "[", postfix = "]", separator = ",")
                tcpRef.writeln("set $dref $commaStr")
                Log.d(Const.TAG, "sendChange: set $dref $commaStr")
            } else {
                Log.d(Const.TAG, "Ignoring request for $dref since TCP connection is not available")
            }
        }
    }

    private fun setConnectionStatus(line1: String, line2: String, fixup: String, dest: String? = null) {
        Log.d(Const.TAG, "Changing connection status to [$line1][$line2][$fixup] with destination [$dest]")
        var out = line1 + ". "
        if (line2.length > 0)
            out += "${line2}. "
        if (fixup.length > 0)
            out += "${fixup}. "
        if (dest != null)
            out += "${dest}."
        if (connectFailures > 0)
            out += " Error #$connectFailures"

        connectText.text = out
    }

    private fun restartNetworking() {
        Log.d(Const.TAG, "restartNetworking()")
        resetDisplay()
        setConnectionStatus("Closing down network", "", "Wait a few seconds")
        connectAddress = null
        connectWorking = false
        connectSupported = false
        connectActTailnum = ""
        if (tcp_extplane != null) {
            Log.d(Const.TAG, "Cleaning up any TCP connections")
            tcp_extplane!!.stopListener()
            tcp_extplane = null
        }
        if (becn_listener != null) {
            Log.w(Const.TAG, "Cleaning up the BECN listener, somehow it is still around?")
            becn_listener!!.stopListener()
            becn_listener = null
        }
        if (connectShutdown) {
            Log.d(Const.TAG, "Will not restart BECN listener since connectShutdown is set")
        } else {
            if (manualAddress.isEmpty()) {
                setConnectionStatus("Waiting for X-Plane", "BECN broadcast", "Touch to override")
                Log.d(Const.TAG, "Starting X-Plane BECN listener since connectShutdown is not set")
                becn_listener = MulticastReceiver(Const.BECN_ADDRESS, Const.BECN_PORT, this)
            } else {
                Log.d(Const.TAG, "Manual address $manualAddress specified, skipping any auto-detection")
                check(tcp_extplane == null)
                connectAddress = manualAddress
                setConnectionStatus("Manual TCP connect", "Find ExtPlane plugin", "Check Win firewall", "$connectAddress:${Const.TCP_EXTPLANE_PORT}")
                tcp_extplane = TCPClient(manualInetAddress!!, Const.TCP_EXTPLANE_PORT, this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        Log.d(Const.TAG, "onResume()")
        connectShutdown = false

        // Start up our background processing thread
        backgroundThread = HandlerThread("BackgroundThread")
        backgroundThread!!.start()

        // Retrieve the manual address from shared preferences
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        val prefAddress = sharedPref.getString("manual_address", "")
        Log.d(Const.TAG, "Found preferences value for manual_address = [$prefAddress]")

        // Pass on whatever this string is, and will end up calling restartNetworking()
        changeManualHostname(prefAddress)
    }

    override fun onPause() {
        Log.d(Const.TAG, "onPause()")
        connectShutdown = true // Prevent new BECN listeners starting up in restartNetworking
        if (tcp_extplane != null) {
            Log.d(Const.TAG, "onPause(): Cancelling existing TCP connection")
            tcp_extplane!!.stopListener()
            tcp_extplane = null
        }
        if (becn_listener != null) {
            Log.d(Const.TAG, "onPause(): Cancelling existing BECN listener")
            becn_listener!!.stopListener()
            becn_listener = null
        }
        backgroundThread!!.quit()
        mapView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        Log.d(Const.TAG, "onDestroy()")
        super.onDestroy()
    }

    override fun onFailureMulticast(ref: MulticastReceiver) {
        if (ref != becn_listener)
            return
        connectFailures++
        setConnectionStatus("No network available", "Cannot listen for BECN", "Enable WiFi")
    }

    override fun onTimeoutMulticast(ref: MulticastReceiver) {
        if (ref != becn_listener)
            return
        Log.d(Const.TAG, "Received indication the multicast socket is not getting replies, will restart it and wait again")
        connectFailures++
        setConnectionStatus("Timeout waiting for", "BECN multicast", "Touch to override")
    }

    override fun onReceiveMulticast(buffer: ByteArray, source: InetAddress, ref: MulticastReceiver) {
        if (ref != becn_listener)
            return
        setConnectionStatus("Found BECN multicast", "Find ExtPlane plugin", "Check Win firewall", source.getHostAddress())
        connectAddress = source.toString().replace("/","")

        // The BECN listener will only reply once, so close it down and open the TCP connection
        becn_listener!!.stopListener()
        becn_listener = null

        check(tcp_extplane == null)
        Log.d(Const.TAG, "Making connection to $connectAddress:${Const.TCP_EXTPLANE_PORT}")
        tcp_extplane = TCPClient(source, Const.TCP_EXTPLANE_PORT, this)
    }

    override fun onConnectTCP(tcpRef: TCPClient) {
        if (tcpRef != tcp_extplane)
            return
        // We will wait for EXTPLANE 1 in onReceiveTCP, so don't send the requests just yet
        setConnectionStatus("Established TCP", "Waiting for ExtPlane", "Needs ExtPlane plugin", "$connectAddress:${Const.TCP_EXTPLANE_PORT}")
    }

    override fun onDisconnectTCP(tcpRef: TCPClient) {
        if (tcpRef != tcp_extplane)
            return
        Log.d(Const.TAG, "onDisconnectTCP(): Closing down TCP connection and will restart")
        connectFailures++
        restartNetworking()
    }

    override fun onReceiveTCP(line: String, tcpRef: TCPClient) {
        // If the current connection does not match the incoming reference, it is out of date and should be ignored.
        // This is important otherwise we will try to transmit on the wrong socket, fail, and then try to restart.
        if (tcpRef != tcp_extplane)
            return

        if (line == "EXTPLANE 1") {
            Log.d(Const.TAG, "Found ExtPlane welcome message, will now make subscription requests for aircraft info")
            setConnectionStatus("Received EXTPLANE", "Sending acf subscribe", "Start your flight", "$connectAddress:${Const.TCP_EXTPLANE_PORT}")

            // Make requests for aircraft type messages so we can detect when the aircraft is actually available,
            // the datarefs do not exist until the aircraft is loaded and in use
            doBgThread {
                tcpRef.writeln("sub sim/aircraft/view/acf_tailnum")
            }
        } else {
            // Log.d(Const.TAG, "Received TCP line [$line]")
            if (!connectWorking) {
                check(!connectSupported) { "connectSupported should not be set if connectWorking is not set" }
                // Everything is working with actual data coming back.
                // This is the last time we can put debug text on the CDU before it is overwritten
                connectFailures = 0
                setConnectionStatus("X-PlaneMonitor starting", "Check aircraft type", "Must find acf_tailnum", "$connectAddress:${Const.TCP_EXTPLANE_PORT}")
                connectWorking = true
            }

            val tokens = line.split(" ")
            if (tokens[0] == "ub") {
                val decoded = String(Base64.decode(tokens[2], Base64.DEFAULT))
                // Replace ` with degree symbol, and * with a diamond symbol (there is no box in Android fonts)
                val fixed = decoded.replace('`','\u00B0').replace('*','\u25CA')

                Log.d(Const.TAG, "Decoded byte array for name [${tokens[1]}] with string [$decoded]")

                // We have received a change in acf_tailnum. If we have never seen any aircraft before, then start
                // the subscriptions. If we have seen a previous aircraft, then reset the network and UI to start fresh
                if (tokens[1] == "sim/aircraft/view/acf_tailnum") {
                    if (connectActTailnum == "") {
                        // No previous aircraft during this connection
                        connectActTailnum = decoded
                        connectSupported = true
                        setConnectionStatus("X-PlaneMonitor working", "${connectActTailnum}", "", "$connectAddress:${Const.TCP_EXTPLANE_PORT}")

                        doBgThread {
                            Log.d(Const.TAG, "Detected aircraft change from nothing to [$decoded], so sending subscriptions")
                            requestDatarefs()
                        }
                    } else {
                        // Currently handling another aircraft, so reset everything
                        Log.d(Const.TAG, "Detected aircraft change from [$connectActTailnum] to [$decoded], so resetting UI and connection")
                        resetDisplay()
                        restartNetworking()
                    }
                } else {
                    Log.d(Const.TAG, "Found unused result name [${tokens[1]}] with string [$fixed]")
                }
            } else if ((tokens[0] == "ud") || (tokens[0] == "uf") || (tokens[0] == "ui")) {
                val number = tokens[2].toFloat()
                // Log.d(Const.TAG, "Decoded number for name [${tokens[1]}] with value [$number]")
                processFloat(tokens[1], number)
            } else if ((tokens[0] == "uda") || (tokens[0] == "ufa") || (tokens[0] == "uia")) {
                var items = tokens[2]
                if (items.startsWith('[') && items.endsWith(']')) {
                    items = items.substring(1, items.length-1)
                    val numbers = items.split(',')
                    // Log.d(Const.TAG, "Decoded for name [${tokens[1]}] with values {$numbers} and ${numbers.size} items")
                    for (i in numbers.indices) {
                        processFloat(tokens[1], numbers[i].toFloat(), i)
                    }
                } else {
                    Log.e(Const.TAG, "Did not find enclosing [ ] brackets in [${tokens[2]}]")
                }
            } else {
                Log.e(Const.TAG, "Unknown encoding type [${tokens[0]}] for name [${tokens[1]}]")
            }
        }
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
        setItemString(itemEstimateFPM, "NAV1 Est FPM", if (airspeed_knots < 100 || distance_nm < 0.1) "N/A" else oneDecimal.format(fpm.toDouble()) + "fpm", false)
    }

    private fun requestDatarefs() {
        // Request values that we are interested in getting updates for
        Log.d(Const.TAG, "Requesting values via RREF")
        sendRequest(tcp_extplane, "sim/cockpit/switches/EFIS_map_submode")        // Map XP737
        sendRequest(tcp_extplane, "1-sim/ndpanel/1/hsiModeRotary")                   // Map FF767
        sendRequest(tcp_extplane, "laminar/B738/EFIS_control/capt/map_mode_pos")     // Map ZB737

        sendRequest(tcp_extplane, "sim/cockpit/switches/EFIS_map_range_selector") // Range XP737
        sendRequest(tcp_extplane, "laminar/B738/EFIS/capt/map_range")                // Range ZB737
        sendRequest(tcp_extplane, "1-sim/ndpanel/1/hsiRangeRotary")                  // Range FF767

        sendRequest(tcp_extplane, "sim/cockpit/switches/EFIS_shows_tcas")      // TFC XP737
        sendRequest(tcp_extplane, "1-sim/ndpanel/1/hsiRangeButton")               // TFC FF767

        sendRequest(tcp_extplane, "sim/cockpit/switches/EFIS_shows_airports")  // ARPT XP737
        sendRequest(tcp_extplane, "laminar/B738/EFIS/EFIS_airport_on")            // ARPT ZB737
        sendRequest(tcp_extplane, "1-sim/ndpanel/1/map3")                         // ARPT FF767

        sendRequest(tcp_extplane, "sim/cockpit/switches/EFIS_shows_waypoints") // WPT XP737
        sendRequest(tcp_extplane, "laminar/B738/EFIS/EFIS_fix_on")                // WPT ZB737
        sendRequest(tcp_extplane, "1-sim/ndpanel/1/map5")                         // WPT FF767

        sendRequest(tcp_extplane, "sim/cockpit/switches/EFIS_shows_VORs")      // STA XP737
        sendRequest(tcp_extplane, "laminar/B738/EFIS/EFIS_vor_on")                // STA ZB737
        sendRequest(tcp_extplane, "1-sim/ndpanel/1/map2")                         // STA FF767

        sendRequest(tcp_extplane, "sim/cockpit/switches/EFIS_shows_weather")   // WXR XP737
        sendRequest(tcp_extplane, "laminar/B738/EFIS/EFIS_wx_on")                 // WXR ZB737
        // No need for this since EFIS_shows_weather seems to pass this on
        // sendRequest(tcp_extplane, "1-sim/ndpanel/1/hsiWxr") // WXR

        sendRequest(tcp_extplane, "1-sim/ndpanel/1/map4")                         // DATA FF767
        // sendRequest(tcp_extplane, "sim/cockpit/switches/EFIS_shows_data")           // DATA, does not exist in XP737 or ZB737?

        sendRequest(tcp_extplane, "1-sim/ndpanel/1/hsiTerr")                      // TERR, no equivalent in XP737 or ZB737?

        sendRequest(tcp_extplane, "1-sim/ndpanel/1/hsiModeButton")                // CTR FF767, no equivalent state on ZB737 or XP737

        sendRequest(tcp_extplane, "sim/operation/misc/frame_rate_period")
        sendRequest(tcp_extplane, "sim/cockpit2/controls/left_brake_ratio")
        sendRequest(tcp_extplane, "sim/cockpit2/controls/right_brake_ratio")
        sendRequest(tcp_extplane, "sim/cockpit2/controls/parking_brake_ratio")
        sendRequest(tcp_extplane, "sim/cockpit2/controls/speedbrake_ratio")
        sendRequest(tcp_extplane, "sim/cockpit/warnings/annunciators/reverse")
        sendRequest(tcp_extplane, "sim/flightmodel/position/indicated_airspeed")
        sendRequest(tcp_extplane, "sim/flightmodel/position/y_agl", 1.0f)
        sendRequest(tcp_extplane, "sim/flightmodel/position/elevation", 1.0f)
        sendRequest(tcp_extplane, "sim/cockpit2/gauges/indicators/altitude_ft_pilot", 1.0f)
        sendRequest(tcp_extplane, "sim/cockpit2/controls/flap_handle_deploy_ratio")
        sendRequest(tcp_extplane, "sim/flightmodel/controls/flaprqst")
        sendRequest(tcp_extplane, "sim/flightmodel2/gear/tire_vertical_force_n_mtr", 1000.0f)
        sendRequest(tcp_extplane, "sim/flightmodel/forces/g_nrml", 0.01f)
        sendRequest(tcp_extplane, "sim/cockpit/radios/nav1_dme_dist_m")
        sendRequest(tcp_extplane, "sim/cockpit/radios/nav2_dme_dist_m")
        sendRequest(tcp_extplane, "sim/flightmodel/position/vh_ind_fpm", 0.01f)
        sendRequest(tcp_extplane, "sim/flightmodel/position/latitude", 0.0001f)
        sendRequest(tcp_extplane, "sim/flightmodel/position/longitude", 0.0001f)
        sendRequest(tcp_extplane, "sim/graphics/view/view_heading", 1.0f)
        sendRequest(tcp_extplane, "sim/cockpit2/switches/landing_lights_switch")
        sendRequest(tcp_extplane, "sim/cockpit2/switches/generic_lights_switch")
        sendRequest(tcp_extplane, "laminar/B738/ice/window_heat_l_fwd_pos")
        sendRequest(tcp_extplane, "laminar/B738/ice/window_heat_r_fwd_pos")
        sendRequest(tcp_extplane, "laminar/B738/ice/window_heat_l_side_pos")
        sendRequest(tcp_extplane, "laminar/B738/ice/window_heat_r_side_pos")
        sendRequest(tcp_extplane, "laminar/B738/toggle_switch/capt_probes_pos")
        sendRequest(tcp_extplane, "laminar/B738/toggle_switch/fo_probes_pos")
        sendRequest(tcp_extplane, "sim/cockpit2/pressurization/actuators/max_allowable_altitude_ft")
        sendRequest(tcp_extplane, "laminar/B738/toggle_switch/seatbelt_sign_pos")
    }

    private fun processFloat(name: String, value: Float, index: Int = -1) {
        if (name == "sim/operation/misc/frame_rate_period") {
            if (value < 0.0001) {
                itemFPS.text = "FPS\nn/a"
                itemFPS.setBackgroundColor(Color.GRAY)
            } else {
                itemFPS.text = "FPS\n" + oneDecimal.format((1.0f / value).toDouble())
                itemFPS.setBackgroundColor(Color.GREEN)
            }
        } else if (name == "sim/cockpit2/controls/left_brake_ratio") {
            setBrakePercent(itemLeftBrake, "Left Brake", value)
        } else if (name == "sim/cockpit2/controls/right_brake_ratio") {
            setBrakePercent(itemRightBrake, "Right Brake", value)
        } else if (name == "sim/cockpit2/controls/parking_brake_ratio") {
            setBrakePercent(itemParkingBrake, "Parking Brake", value)
        } else if (name == "sim/cockpit2/controls/speedbrake_ratio") {
            setBrakePercent(itemSpeedBrake, "Speed Brake (Air)", value)
        } else if (name == "sim/cockpit/warnings/annunciators/reverse") {
            val bits = value.toInt()
            var engines = ""
            if (bits and 1 == 1) engines += "1"
            if (bits and 2 == 2) engines += "2"
            if (bits and 4 == 4) engines += "3"
            if (bits and 8 == 8) engines += "4"
            setItemString(itemReverseThrust, "Thrust Direction", if (bits != 0) "REVERSE " + engines else "All Forward", bits != 0)
        } else if (name == "sim/flightmodel/position/indicated_airspeed") {
            setItemString(itemIndicatedSpeed, "Indicated Air Speed", oneDecimal.format(value.toDouble()) + "kts", false)
            globalAirspeed = value
            setGlideEstimate(globalNav1Distance, globalAirspeed, globalAltitude)
        } else if (name == "sim/flightmodel/position/y_agl") {
            setItemString(itemAltitudeGround, "Altitude AGL", oneDecimal.format((value * Const.METERS_TO_FEET).toDouble()) + "ft", false)
        } else if (name == "sim/flightmodel/position/elevation") {
            setItemString(itemAltitudeMSL, "Altitude MSL", oneDecimal.format((value * Const.METERS_TO_FEET).toDouble()) + "ft", false)
            globalAltitude = value * Const.METERS_TO_FEET
            setGlideEstimate(globalNav1Distance, globalAirspeed, globalAltitude)
        } else if (name == "sim/cockpit2/gauges/indicators/altitude_ft_pilot") {
            setItemString(itemAltitudeGauge, "Altitude Gauge", oneDecimal.format(value.toDouble()) + "ft", false)
        } else if (name == "sim/cockpit2/controls/flap_handle_deploy_ratio") {
            lastFlapsActual = zeroDecimal.format((40 * value).toDouble())
            setItemString(itemFlapsActual, "Flaps Actual", lastFlapsActual, lastFlapsActual != lastFlapsDesired)
        } else if (name == "sim/flightmodel/controls/flaprqst") {
            lastFlapsDesired = zeroDecimal.format((40 * value).toDouble())
            setItemString(itemFlapsDesired, "Flaps Desired", lastFlapsDesired, value > 0.01)
        } else if (name == "sim/flightmodel2/gear/tire_vertical_force_n_mtr") {
            setItemString(itemForceGear, "Gear Force", oneDecimal.format(value.toDouble()) + "Nm", false)
        } else if (name == "sim/flightmodel/forces/g_nrml") {
            setItemString(itemForceVertical, "Vert Force", oneDecimal.format(value.toDouble()) + "G", value < 0.75 || value > 1.25)
            graphForceVertical.set1Value(value - 1.0) // Center around 1G
            barForceVertical.setValue(value - 1.0)
        } else if (name == "sim/cockpit/radios/nav1_dme_dist_m") {
            setItemString(itemDME1Distance, "NAV1 DME", oneDecimal.format(value.toDouble()) + "Nm", false)
            globalNav1Distance = value
            setGlideEstimate(globalNav1Distance, globalAirspeed, globalAltitude)
        } else if (name == "sim/cockpit/radios/nav2_dme_dist_m") {
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
        } else if (name == "sim/cockpit2/switches/generic_lights_switch") {
            if (index < genericLightsText.size) {
                val t = genericLightsText[index]
                t!!.setText("G$index")
                if (value.toInt() > 0)
                    t.setBackgroundColor(Color.LTGRAY)
                else
                    t.setBackgroundColor(Color.GRAY)
                genericLightsValues[index] = value
            }
        } else if (name == "sim/cockpit2/switches/landing_lights_switch") {
            if (index < landingLightsText.size) {
                val t = landingLightsText[index]
                t!!.setText("L$index")
                if (value.toInt() > 0)
                    t.setBackgroundColor(Color.LTGRAY)
                else
                    t.setBackgroundColor(Color.GRAY)
                landingLightsValues[index] = value
            }
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
        } else if (name == "sim/cockpit/switches/EFIS_map_submode") {
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
            mirror_xhsi_value(name, "1-sim/ndpanel/1/hsiModeRotary", "sim/cockpit/switches/EFIS_map_submode", if (value.toInt() == 3) 4.0f else value)
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
        } else if (name == "sim/cockpit/switches/EFIS_map_range_selector" || name == "1-sim/ndpanel/1/hsiRangeRotary") {
            mirror_xhsi_value(name, "1-sim/ndpanel/1/hsiRangeRotary", "sim/cockpit/switches/EFIS_map_range_selector", value)
            val range = (1 shl value.toInt()) * 10
            map_zoom_range.text = "" + range + "nm"
            efis_range_state = value.toInt()
        } else if (name == "laminar/B738/EFIS/capt/map_range") { // Zibo does the range differently than X-Plane
            val range = (1 shl value.toInt()) * 5
            map_zoom_range.text = "" + range + "nm"
            efis_range_state = value.toInt()
        } else if (name == "sim/cockpit/switches/EFIS_shows_tcas" || name == "1-sim/ndpanel/1/hsiRangeButton") {
            mirror_xhsi_value(name, "1-sim/ndpanel/1/hsiRangeButton", "sim/cockpit/switches/EFIS_shows_tcas", value)
            efis_button_tfc.setState(value)
        } else if (name == "1-sim/ndpanel/1/hsiModeButton") { // No equivalent for ZB737 or XP737
            // hsiModeButton seems to either never change, or always go 0->1->0 very quickly, so perhaps it can never be set in FF767
            mirror_xhsi_value(name, "1-sim/ndpanel/1/hsiModeButton", "sim/cockpit/switches/EFIS_shows_ctr_TODO", value)
            efis_button_ctr.setState(value)
        } else if (name == "sim/cockpit/switches/EFIS_shows_airports" || name == "1-sim/ndpanel/1/map3" || name == "laminar/B738/EFIS/EFIS_airport_on") {
            mirror_xhsi_value(name, "1-sim/ndpanel/1/map3", "sim/cockpit/switches/EFIS_shows_airports", value)
            efis_button_arpt.setState(value)
        } else if (name == "sim/cockpit/switches/EFIS_shows_waypoints" || name == "1-sim/ndpanel/1/map5" || name == "laminar/B738/EFIS/EFIS_fix_on") {
            mirror_xhsi_value(name, "1-sim/ndpanel/1/map5", "sim/cockpit/switches/EFIS_shows_waypoints", value)
            efis_button_wpt.setState(value)
        } else if (name == "sim/cockpit/switches/EFIS_shows_VORs" || name == "1-sim/ndpanel/1/map2" || name == "laminar/B738/EFIS/EFIS_vor_on") {
            mirror_xhsi_value(name, "1-sim/ndpanel/1/map2", "sim/cockpit/switches/EFIS_shows_VORs", value)
            efis_button_sta.setState(value)
        } else if (name == "sim/cockpit/switches/EFIS_shows_data" || name == "1-sim/ndpanel/1/map4") {
            // TODO: Note that sim/cockpit/switches/EFIS_shows_data does not seem to exist in XP737, except it should
            // TODO: mirror_xhsi_value()
            efis_button_data.setState(value)
        } else if (name == "sim/cockpit/switches/EFIS_shows_weather" || name == "1-sim/ndpanel/1/hsiWxr" || name == "laminar/B738/EFIS/EFIS_wx_on") {
            mirror_xhsi_value(name, "1-sim/ndpanel/1/hsiWxr", "sim/cockpit/switches/EFIS_shows_weather", value)
            efis_button_wxr.setState(value)
        } else if (name == "sim/cockpit/switches/EFIS_shows_terrain" || name == "1-sim/ndpanel/1/hsiTerr") {
            // TODO: Note that sim/cockpit/switches/EFIS_shows_terrain does not seem to exist in XP737, except it should
            // TODO: mirror_xhsi_value()
            efis_button_terr.setState(value)
        } else if (name == "laminar/B738/ice/window_heat_l_fwd_pos") {
            window_heat_l_fwd.setState(value)
        } else if (name == "laminar/B738/ice/window_heat_r_fwd_pos") {
            window_heat_r_fwd.setState(value)
        } else if (name == "laminar/B738/ice/window_heat_l_side_pos") {
            window_heat_l_side.setState(value)
        } else if (name == "laminar/B738/ice/window_heat_r_side_pos") {
            window_heat_r_side.setState(value)
        } else if (name == "laminar/B738/toggle_switch/capt_probes_pos") {
            capt_probes_pos.setState(value)
        } else if (name == "laminar/B738/toggle_switch/fo_probes_pos") {
            fo_probes_pos.setState(value)
        } else if (name == "sim/cockpit2/pressurization/actuators/max_allowable_altitude_ft") {
            flt_alt_actual.text = "FL" + value.toInt() / 1000
        } else if (name == "laminar/B738/toggle_switch/seatbelt_sign_pos") {
            if (value.toInt() == 0)
                seatbelt_sign.text = "SB-Off"
            else if (value.toInt() == 1)
                seatbelt_sign.text = "SB-Auto"
            else if (value.toInt() == 2)
                seatbelt_sign.text = "SB-On"
            else
                seatbelt_sign.text = "n/a"
        } else {
            Log.e(Const.TAG, "Unhandled dataref name=$name, value=$value")
        }
    }

    fun resetDisplay() {
        resetIndicators()
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
}
