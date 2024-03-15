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
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.android.synthetic.main.activity_main.*
import net.waynepiekarski.xplanemonitor.xplane.DataRef

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
    private var connectExtplaneVersion = -1
    private var connectExtplaneWarning = ""

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

        switchDetectButton.setOnClickListener { _ -> popupManualHostnameOnClickListener() }
        aboutText.setOnClickListener { _ -> popupManualHostnameOnClickListener() }
        versionText.setOnClickListener {_ -> popupManualHostnameOnClickListener() }

        layoutMap_maphybrid.setOnClickListener {
            googleMap.setMapType(GoogleMap.MAP_TYPE_HYBRID)
        }

        layoutMap_mapsatellite.setOnClickListener {
            googleMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE)
        }

        layoutMap_mapnormal.setOnClickListener {
            googleMap.setMapType(GoogleMap.MAP_TYPE_NORMAL)
        }

        layoutMap_mapterrain.setOnClickListener {
            googleMap.setMapType(GoogleMap.MAP_TYPE_TERRAIN)
        }

        layoutMap_zoomin.setOnClickListener {
            googleMap.moveCamera(CameraUpdateFactory.zoomBy(1.0f))
        }

        layoutMap_zoomout.setOnClickListener {
            googleMap.moveCamera(CameraUpdateFactory.zoomBy(-1.0f))
        }

        fun buttonToCommand(view: View, command: String) {
            view.setOnClickListener {
                Log.d(Const.TAG, "Sending view command $command")
                sendCommand(tcp_extplane, command)
            }
        }

        fun buttonToActions(button: XButton, command: String, dref: String) {
            button.setOnClickListener {
                val value = button.getInverseState()
                Log.d(Const.TAG, "Button for CMND $command, and DREF $dref to $value")
                sendCommand(tcp_extplane, command)
                sendChange(tcp_extplane, dref, value)
            }
        }

        efis_mode_change.setOnClickListener {
            val efis_mode_prev = efis_mode_state
            val efis_mode_next = if (efis_mode_state >= 3) 0 else efis_mode_state + 1

            efis_mode_state = efis_mode_next

            Log.d(Const.TAG, "Change EFIS mode to $efis_mode_next from $efis_mode_prev")

            sendChange(tcp_extplane, DataRef.NDPANEL_HSIMODEROTARY, efis_mode_state.toFloat()) // XP737 (this is really for FF767?)

            var rewrite = efis_mode_next

            if (rewrite == 3) rewrite = 4

            sendChange(tcp_extplane, DataRef.Cockpit.Switches.EFIS_MAPSUBMODE, rewrite.toFloat()) // FF767
            sendChange(tcp_extplane, DataRef.B738.EFIS.CONTROL_CAPTAIN_MAPMODEPOS, efis_mode_state.toFloat()) // ZB737
        }

        fun efis_range_change(dir: Int) {
            val efis_range_prev = efis_range_state
            var efis_range_next = efis_range_state + dir

            if (efis_range_next >= 5)
                efis_range_next = 5
            else if (efis_range_next < 0)
                efis_range_next = 0

            efis_range_state = efis_range_next

            Log.d(Const.TAG, "Change EFIS range with direction $dir to $efis_range_next from $efis_range_prev")

            sendChange(tcp_extplane, DataRef.NDPANEL_HSIRANGEROTARY, efis_range_state.toFloat()) // FF767
            sendChange(tcp_extplane, DataRef.Cockpit.Switches.EFIS_MAP_RANGE_SELECTOR, efis_range_state.toFloat()) // XP737

            if (dir > 0) {
                sendCommand(tcp_extplane, DataRef.B738.EFIS.Control.MAP_RANGE_UP) // ZB737
            } else {
                sendCommand(tcp_extplane, DataRef.B738.EFIS.Control.MAP_RANGE_DOWN) // ZB737
            }
        }

        map_zoom_out.setOnClickListener { _ -> efis_range_change(1) }
        map_zoom_in.setOnClickListener { _ -> efis_range_change(-1) }

        buttonToActions(efis_button_tfc, DataRef.B738.EFIS.Control.TFC_PRESS, DataRef.NDPANEL_HSIRANGEBUTTON)
        buttonToActions(efis_button_wxr, DataRef.B738.EFIS.Control.WXR_PRESS, DataRef.NDPANEL_HSIWXR)
        buttonToActions(efis_button_sta, DataRef.B738.EFIS.Control.STA_PRESS, DataRef.NDPANEL_MAP2)
        buttonToActions(efis_button_wpt, DataRef.B738.EFIS.Control.WPT_PRESS, DataRef.NDPANEL_MAP5)
        buttonToActions(efis_button_arpt, DataRef.B738.EFIS.Control.ARPT_PRESS, DataRef.NDPANEL_MAP3)
        buttonToActions(efis_button_data, DataRef.B738.EFIS.Control.DATA_PRESS, DataRef.NDPANEL_MAP4)
        buttonToCommand(efis_button_pos, DataRef.B738.EFIS.Control.POS_PRESS)
        buttonToActions(efis_button_terr, DataRef.B738.EFIS.Control.TERR_PRESS, DataRef.NDPANEL_HSITERR)
        // TODO: There does not appear to be a CTR button or dataref in the XP737, only Zibo seems to support the press and not receive an update
        buttonToActions(efis_button_ctr, DataRef.B738.EFIS.Control.CTR_PRESS, DataRef.NDPANEL_HSIMODEBUTTON)

        all_lights_on.setOnClickListener { _ -> updateLights(1.0f, "on") }
        all_lights_off.setOnClickListener { _ -> updateLights(0.0f, "off") }

        for (i in 0 until landingLightsText.size) {
            val t = TextView(this)
            t.setText("L$i")
            t.setPadding(20, 20, 20, 20)

            layout_lights.addView(t)

            t.setOnClickListener {
                val inverted = 1.0f - landingLightsValues[i]

                Log.d(Const.TAG, "Clicked landing_lights_switch[$i] from " + landingLightsValues[i] + " to new " + inverted)

                landingLightsValues[i] = inverted
                sendChangeArray(tcp_extplane, DataRef.Cockpit.Switches.LANDING_LIGHTS_SWITCH, landingLightsValues)
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
                sendChangeArray(tcp_extplane, DataRef.Cockpit.Switches.GENERIC_LIGHTS_SWITCH, genericLightsValues)
            }

            genericLightsText[i] = t
        }

        buttonToCommand(window_heat_l_side, DataRef.B738.ToggleSwitch.WINDOW_HEAT_LEFT_SIDE)
        buttonToCommand(window_heat_r_side, DataRef.B738.ToggleSwitch.WINDOW_HEAT_RIGHT_SIDE)
        buttonToCommand(window_heat_l_fwd, DataRef.B738.ToggleSwitch.WINDOW_HEAT_LEFT_FORWARD)
        buttonToCommand(window_heat_r_fwd, DataRef.B738.ToggleSwitch.WINDOW_HEAT_RIGHT_FORWARD)
        buttonToCommand(capt_probes_pos, DataRef.B738.ToggleSwitch.CAPT_PROBES_POS)
        buttonToCommand(fo_probes_pos, DataRef.B738.ToggleSwitch.FO_PROBES_POS)

        flt_alt_dn.setOnClickListener {
            max_allowable_altitude_ft -= 5000
            if (max_allowable_altitude_ft < 0) max_allowable_altitude_ft = 0

            changeMaximumAltitude()
        }

        flt_alt_up.setOnClickListener {
            max_allowable_altitude_ft += 5000
            if (max_allowable_altitude_ft > 45000) max_allowable_altitude_ft = 45000

            changeMaximumAltitude()
        }

        buttonToCommand(seatbelt_sign_up, DataRef.B738.ToggleSwitch.SEATBELT_SIGN_UP)
        buttonToCommand(seatbelt_sign_dn, DataRef.B738.ToggleSwitch.SEATBELT_SIGN_DN)
        buttonToCommand(attend, DataRef.B738.PushButton.ATTEND)
        buttonToCommand(itemPaused, DataRef.Operation.PUSHBUTTON_PAUSE_TOGGLE)

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
            googleMap.setMapType(GoogleMap.MAP_TYPE_TERRAIN)
        }
    }

    private fun popupManualHostnameOnClickListener() {
        Log.d(Const.TAG, "Popup for manual hostname")
        popupManualHostname()
    }

    private fun changeMaximumAltitude() {
        Log.d(Const.TAG, "Change maximum altitude to $max_allowable_altitude_ft")

        sendChange(tcp_extplane, DataRef.Cockpit.Pressurization.MAX_ALLOWABLE_ALTITUDE_FT, max_allowable_altitude_ft.toFloat())
    }

    private fun updateLights(value: Float, status: String) {
        Log.d(Const.TAG, "Set all lights $status")

        val landingLightsValues = FloatArray(landingLightsValues.size) { _ -> value }
        sendChangeArray(tcp_extplane, DataRef.Cockpit.Switches.LANDING_LIGHTS_SWITCH, landingLightsValues)

        val genericLightsValues = FloatArray(genericLightsValues.size) { _ -> value }
        sendChangeArray(tcp_extplane, DataRef.Cockpit.Switches.GENERIC_LIGHTS_SWITCH, genericLightsValues)
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

            with(sharedPref.edit()) {
                putString(Const.MANUAL_ADDRESS_KEY, manualAddress)
                commit()
            }

            switchDetectButton.text = getString(R.string.auto_becn)
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
                        putString(Const.MANUAL_ADDRESS_KEY, manualAddress)
                        commit()
                    }

                    switchDetectButton.text = "Manual: $manualAddress"

                    restartNetworking()
                }
            }
        }
    }

    private fun popupManualHostname(error: Boolean = false) {
        val builder = AlertDialog.Builder(this)

        if (error) {
            builder.setTitle(getString(R.string.hostname_invalid))
        } else {
            builder.setTitle(getString(R.string.hostname_specify))
        }

        val input = EditText(this)
        input.setText(manualAddress)
        input.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)

        builder.setView(input)
        builder.setPositiveButton(getString(R.string.manual_override)) { dialog, which -> changeManualHostname(input.text.toString()) }
        builder.setNegativeButton(getString(R.string.revert)) { dialog, which -> dialog.cancel() }
        builder.setNeutralButton(getString(R.string.automatic_multicast)) { dialog, which -> changeManualHostname("") }

        builder.show()
    }

    private fun sendCommand(tcpRef: TCPClient?, command: String) {
        // Send the command on a separate thread
        doBgThread {
            if ((tcpRef != null) && (tcpRef == tcp_extplane) && connectWorking) {
                tcpRef.writeln("cmd once $command")
            } else {
                Log.d(Const.TAG, "Ignoring command $command since TCP connection is not available")
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

        var out = "$line1. "
        if (line2.isNotEmpty()) out += "${line2}. "
        if (fixup.isNotEmpty()) out += "${fixup}. "
        if (dest != null) out += "${dest}."
        if (connectFailures > 0) out += " Error #$connectFailures"

        connectText.text = out
    }

    private fun restartNetworking() {
        Log.d(Const.TAG, "restartNetworking()")

        resetDisplay()

        setConnectionStatus(
            getString(R.string.closing_down_network),
            "",
            getString(R.string.wait_a_few_seconds)
        )

        connectAddress = null
        connectWorking = false
        connectSupported = false
        connectActTailnum = ""
        connectExtplaneVersion = -1
        connectExtplaneWarning = ""

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
                setConnectionStatus(
                    getString(R.string.waiting_for_xplane),
                    getString(R.string.becn_broadcast),
                    getString(R.string.touch_to_override)
                )

                Log.d(Const.TAG, "Starting X-Plane BECN listener since connectShutdown is not set")
                becn_listener = MulticastReceiver(Const.BECN_ADDRESS, Const.BECN_PORT, this)
            } else {
                Log.d(Const.TAG, "Manual address $manualAddress specified, skipping any auto-detection")

                check(tcp_extplane == null)

                connectAddress = manualAddress

                setConnectionStatus(
                    getString(R.string.manual_tcp_connect),
                    getString(R.string.find_extplane_plugin),
                    getString(R.string.check_win_firewall),
                    "$connectAddress:${Const.TCP_EXTPLANE_PORT}"
                )

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
        val prefAddress = sharedPref.getString(Const.MANUAL_ADDRESS_KEY, "").orEmpty()

        Log.d(Const.TAG, "Found preferences value for ${Const.MANUAL_ADDRESS_KEY} = [$prefAddress]")

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
        if (ref != becn_listener) return

        connectFailures++

        setConnectionStatus(
            getString(R.string.no_network_available),
            getString(R.string.cannot_listen_for_becn),
            getString(R.string.enable_wifi)
        )
    }

    override fun onTimeoutMulticast(ref: MulticastReceiver) {
        if (ref != becn_listener) return

        Log.d(Const.TAG, "Received indication the multicast socket is not getting replies, will restart it and wait again")

        connectFailures++

        setConnectionStatus(
            getString(R.string.timeout_waiting_for),
            getString(R.string.becn_multicast),
            getString(R.string.touch_to_override)
        )
    }

    override fun onReceiveMulticast(buffer: ByteArray, source: InetAddress, ref: MulticastReceiver) {
        if (ref != becn_listener) return

        setConnectionStatus(
            getString(R.string.found_becn_multicast),
            getString(R.string.find_extplane_plugin),
            getString(R.string.check_win_firewall),
            source.getHostAddress()
        )

        connectAddress = source.toString().replace("/","")

        // The BECN listener will only reply once, so close it down and open the TCP connection
        becn_listener!!.stopListener()
        becn_listener = null

        check(tcp_extplane == null)
        Log.d(Const.TAG, "Making connection to $connectAddress:${Const.TCP_EXTPLANE_PORT}")
        tcp_extplane = TCPClient(source, Const.TCP_EXTPLANE_PORT, this)
    }

    override fun onConnectTCP(tcpRef: TCPClient) {
        if (tcpRef != tcp_extplane) return

        // We will wait for EXTPLANE 1 in onReceiveTCP, so don't send the requests just yet
        setConnectionStatus(
            getString(R.string.established_tcp),
            getString(R.string.waiting_for_extplane),
            getString(R.string.needs_extplane_plugin),
            "$connectAddress:${Const.TCP_EXTPLANE_PORT}"
        )
    }

    override fun onDisconnectTCP(tcpRef: TCPClient) {
        if (tcpRef != tcp_extplane) return

        Log.d(Const.TAG, "onDisconnectTCP(): Closing down TCP connection and will restart")

        connectFailures++
        restartNetworking()
    }

    override fun onReceiveTCP(line: String, tcpRef: TCPClient) {
        // If the current connection does not match the incoming reference, it is out of date and should be ignored.
        // This is important otherwise we will try to transmit on the wrong socket, fail, and then try to restart.
        if (tcpRef != tcp_extplane) return

        if (line.startsWith(Const.EXTPLANE1)) {
            Log.d(Const.TAG, "Found ExtPlane welcome message, will now make subscription requests for aircraft info")

            setConnectionStatus(
                getString(R.string.received_extplane),
                getString(R.string.sending_acf_subscribe),
                getString(R.string.start_your_flight),
                "$connectAddress:${Const.TCP_EXTPLANE_PORT}"
            )

            // Make requests for aircraft type messages so we can detect when the aircraft is actually available,
            // the datarefs do not exist until the aircraft is loaded and in use
            doBgThread {
                tcpRef.writeln("sub ${DataRef.Aircraft.ACF_TAILNUM}")
            }
        } else if (line.startsWith(Const.EXTPLANE_VERSION)) {
            // This is a new header introduced in newer ExtPlane plugins. It will not happen with
            // older versions, so we cannot require this. We store this version number, and later on
            // when the datarefs arrive, we should pop up a warning if we detect the plugin is old.
            // The EXTPLANE-VERSION message is in the header and guaranteed to arrive before connectWorking is true.
            val tokens = line.split(" ")
            connectExtplaneVersion = tokens[1].toInt()
            Log.d(Const.TAG, "Found ExtPlane feature version $connectExtplaneVersion")

            if (connectExtplaneVersion < Const.MIN_EXTPLANE_VERSION) {
                Log.w(Const.TAG, "EXTPLANE-VERSION detected $connectExtplaneVersion is older than expected ${Const.MIN_EXTPLANE_VERSION}")
                Toast.makeText(this, "ExtPlane plugin is out of date, requires version ${Const.MIN_EXTPLANE_VERSION} but found $connectExtplaneVersion.\nDownload the latest from http://waynepiekarski.net/ExtPlane", Toast.LENGTH_LONG).show()
                connectExtplaneWarning = ". Old ExtPlane plugin $connectExtplaneVersion."
            }

            setConnectionStatus(
                getString(R.string.received_extplane_ver),
                getString(R.string.sending_acf_subscribe),
                getString(R.string.start_your_flight),
                "$connectAddress:${Const.TCP_EXTPLANE_PORT}$connectExtplaneWarning"
            )
        } else {
            // Log.d(Const.TAG, "Received TCP line [$line]")
            if (!connectWorking) {
                check(!connectSupported) { "connectSupported should not be set if connectWorking is not set" }
                // Everything is working with actual data coming back.
                connectFailures = 0
                connectWorking = true

                // If the ExtPlane plugin does not emit EXTPLANE-VERSION, then this is the first opportunity to detect it
                if (connectExtplaneVersion <= 0) {
                    Log.w(Const.TAG, "No EXTPLANE-VERSION header detected, so plugin is an unknown old version")
                    Toast.makeText(this, "ExtPlane plugin is out of date, requires version ${Const.MIN_EXTPLANE_VERSION}.\nDownload the latest from http://waynepiekarski.net/ExtPlane", Toast.LENGTH_LONG).show()
                    connectExtplaneWarning = ". Old ExtPlane plugin."
                }

                // This is the last time we can put debug text on the CDU before it is overwritten
                setConnectionStatus(
                    getString(R.string.xplane_monitor_starting),
                    getString(R.string.waiting_acf_description),
                    getString(R.string.must_find_acf_tailnum),
                    "$connectAddress:${Const.TCP_EXTPLANE_PORT}$connectExtplaneWarning"
                )
            }

            val tokens = line.split(" ")
            if (tokens[0] == "ub") {
                val decoded = String(Base64.decode(tokens[2], Base64.DEFAULT))
                // Replace ` with degree symbol, and * with a diamond symbol (there is no box in Android fonts)
                val fixed = decoded.replace('`','\u00B0').replace('*','\u25CA')

                Log.d(Const.TAG, "Decoded byte array for name [${tokens[1]}] with string [$decoded]")

                // We have received a change in acf_tailnum. If we have never seen any aircraft before, then start
                // the subscriptions. If we have seen a previous aircraft, then reset the network and UI to start fresh
                if (tokens[1] == DataRef.Aircraft.ACF_TAILNUM) {
                    if (connectActTailnum == "") {
                        // No previous aircraft during this connection
                        connectActTailnum = decoded
                        connectSupported = true

                        setConnectionStatus(
                            getString(R.string.xplane_monitor_working),
                            connectActTailnum,
                            "",
                            "$connectAddress:${Const.TCP_EXTPLANE_PORT}$connectExtplaneWarning"
                        )

                        doBgThread {
                            Log.d(Const.TAG, "Detected aircraft change from nothing to [$decoded], so sending subscriptions")
                            requestDatarefs()
                        }
                    } else if (connectActTailnum == decoded) {
                        // acf_tailnum was sent to us with the same value. This can happen if a second device connects
                        // via ExtPlane, and it updates all listeners with the latest value. We can safely ignore this.
                        Log.d(Const.TAG, "Detected aircraft update which is the same [$connectActTailnum], but ignoring since nothing has changed")
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

    private fun setBrakePercent(textView: TextView, label: String, f: Float) {
        val percent = (f * 100).toInt()
        setItemString(textView, label, "" + percent + "%", percent >= 1)
    }

    private fun setItemString(textView: TextView, label: String, value: String, displayAsRed: Boolean) {
        textView.text = getString(R.string.textview_text_display, label, value)

        if (displayAsRed)
            textView.setBackgroundColor(Color.RED)
        else
            textView.setBackgroundColor(Color.GREEN)
    }

    private fun setItemMap(latitude: Float, longitude: Float, heading: Float) {
        if (layoutMap.visibility != View.VISIBLE) return

        if (!::googleMap.isInitialized) return

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
                    .title(getString(R.string.airplane)))
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
    private fun setGlideEstimate(distance_nm: Float, airspeed_knots: Float, altitude_feet: Float) {
        val hours = distance_nm / airspeed_knots
        val minutes = hours * 60.0f

        val fpm = -altitude_feet / minutes

        val nav1EstimatedMinutesDisplayValue = if (airspeed_knots < 100 || distance_nm < 0.1) DataRef.NA else oneDecimal.format(minutes.toDouble()) + "mins"
        setItemString(itemEstimateMins, getString(R.string.nav1_estimated_minutes), nav1EstimatedMinutesDisplayValue, false)

        val nav1EstimatedFPMDisplayValue = if (airspeed_knots < 100 || distance_nm < 0.1) DataRef.NA else oneDecimal.format(fpm.toDouble()) + "fpm"
        setItemString(itemEstimateFPM, getString(R.string.nav1_estimated_fpm), nav1EstimatedFPMDisplayValue, false)
    }

    // Request values that we are interested in getting updates for
    private fun requestDatarefs() {
        Log.d(Const.TAG, "Requesting values via RREF")

        sendRequest(tcp_extplane, DataRef.Cockpit.Switches.EFIS_MAPSUBMODE) // Map XP737
        sendRequest(tcp_extplane, DataRef.NDPANEL_HSIMODEROTARY) // Map FF767
        sendRequest(tcp_extplane, DataRef.B738.EFIS.CONTROL_CAPTAIN_MAPMODEPOS) // Map ZB737

        sendRequest(tcp_extplane, DataRef.Cockpit.Switches.EFIS_MAP_RANGE_SELECTOR) // Range XP737
        sendRequest(tcp_extplane, DataRef.B738.EFIS.CAPTAIN_MAP_RANGE) // Range ZB737
        sendRequest(tcp_extplane, DataRef.NDPANEL_HSIRANGEROTARY) // Range FF767

        sendRequest(tcp_extplane, DataRef.Cockpit.Switches.EFIS_SHOWS_TCAS) // TFC XP737
        sendRequest(tcp_extplane, DataRef.NDPANEL_HSIRANGEBUTTON) // TFC FF767

        sendRequest(tcp_extplane, DataRef.Cockpit.Switches.EFIS_SHOWS_AIRPORTS) // ARPT XP737
        sendRequest(tcp_extplane, DataRef.B738.EFIS.AIRPORT_ON) // ARPT ZB737
        sendRequest(tcp_extplane, DataRef.NDPANEL_MAP3) // ARPT FF767

        sendRequest(tcp_extplane, DataRef.Cockpit.Switches.EFIS_SHOWS_WAYPOINTS) // WPT XP737
        sendRequest(tcp_extplane, DataRef.B738.EFIS.FIX_ON) // WPT ZB737
        sendRequest(tcp_extplane, DataRef.NDPANEL_MAP5) // WPT FF767

        sendRequest(tcp_extplane, DataRef.Cockpit.Switches.EFIS_SHOWS_VORS) // STA XP737
        sendRequest(tcp_extplane, DataRef.B738.EFIS.VOR_ON) // STA ZB737
        sendRequest(tcp_extplane, DataRef.NDPANEL_MAP2) // STA FF767

        sendRequest(tcp_extplane, DataRef.Cockpit.Switches.EFIS_SHOWS_WEATHER) // WXR XP737
        sendRequest(tcp_extplane, DataRef.B738.EFIS.WX_ON) // WXR ZB737
        // No need for this since EFIS_shows_weather seems to pass this on
        // sendRequest("1-sim/ndpanel/1/hsiWxr") // WXR

        sendRequest(tcp_extplane, DataRef.NDPANEL_MAP4) // DATA FF767
        // sendRequest("sim/cockpit/switches/EFIS_shows_data") // DATA, does not exist in XP737 or ZB737?

        sendRequest(tcp_extplane, DataRef.NDPANEL_HSITERR) // TERR, no equivalent in XP737 or ZB737?

        sendRequest(tcp_extplane, DataRef.NDPANEL_HSIMODEBUTTON) // CTR FF767, no equivalent state on ZB737 or XP737

        sendRequest(tcp_extplane, DataRef.Time.TIME_PAUSED)
        sendRequest(tcp_extplane, DataRef.Cockpit.Controls.LEFT_BRAKE_RATIO)
        sendRequest(tcp_extplane, DataRef.Cockpit.Controls.RIGHT_BRAKE_RATIO)
        sendRequest(tcp_extplane, DataRef.Cockpit.Controls.PARKING_BRAKE_RATIO)
        sendRequest(tcp_extplane, DataRef.Cockpit.Controls.SPEEDBRAKE_RATIO)
        sendRequest(tcp_extplane, DataRef.Cockpit.Warnings.Annunciators.REVERSE)
        sendRequest(tcp_extplane, DataRef.FlightModel.Position.INDICATED_AIRSPEED)
        sendRequest(tcp_extplane, DataRef.FlightModel.Position.Y_AGL)
        sendRequest(tcp_extplane, DataRef.FlightModel.Position.ELEVATION)
        sendRequest(tcp_extplane, DataRef.Cockpit.Gauges.ALTITUDE_FT_PILOT)
        sendRequest(tcp_extplane, DataRef.Cockpit.Controls.FLAP_HANDLE_DEPLOY_RATIO)
        sendRequest(tcp_extplane, DataRef.FlightModel.Controls.FLAPREQUEST)
        sendRequest(tcp_extplane, DataRef.FlightModel.Gear.TIRE_VERTICAL_FORCE_N_MTR)
        sendRequest(tcp_extplane, DataRef.FlightModel.Forces.G_NRML)
        sendRequest(tcp_extplane, DataRef.Cockpit.Radios.NAV1_DME_DIST_M)
        sendRequest(tcp_extplane, DataRef.Cockpit.Radios.NAV2_DME_DIST_M)
        sendRequest(tcp_extplane, DataRef.FlightModel.Position.VH_IND_FPM)
        sendRequest(tcp_extplane, DataRef.FlightModel.Position.LATITUDE)
        sendRequest(tcp_extplane, DataRef.FlightModel.Position.LONGITUDE)
        sendRequest(tcp_extplane, DataRef.Cockpit.Gauges.COMPASS_HEADING_DEG_MAG)
        sendRequest(tcp_extplane, DataRef.Cockpit.Switches.LANDING_LIGHTS_SWITCH)
        sendRequest(tcp_extplane, DataRef.Cockpit.Switches.GENERIC_LIGHTS_SWITCH)
        sendRequest(tcp_extplane, DataRef.B738.Ice.WINDOW_HEAT_L_FWD_POS)
        sendRequest(tcp_extplane, DataRef.B738.Ice.WINDOW_HEAT_R_FWD_POS)
        sendRequest(tcp_extplane, DataRef.B738.Ice.WINDOW_HEAD_L_SIDE_POS)
        sendRequest(tcp_extplane, DataRef.B738.Ice.WINDOW_HEAD_R_SIDE_POS)
        sendRequest(tcp_extplane, DataRef.B738.ToggleSwitch.CAPT_PROBES_POS)
        sendRequest(tcp_extplane, DataRef.B738.ToggleSwitch.FO_PROBES_POS)
        sendRequest(tcp_extplane, DataRef.Cockpit.Pressurization.MAX_ALLOWABLE_ALTITUDE_FT)
        sendRequest(tcp_extplane, DataRef.B738.ToggleSwitch.SEATBELT_SIGN_POS)
    }

    private fun processFloat(name: String, value: Float, index: Int = -1) {
        if (name == DataRef.Time.TIME_PAUSED) {
            if (value < 1.0) {
                itemPaused.text = getString(R.string.xplane_running)
                itemPaused.setBackgroundColor(Color.GREEN)
            } else {
                itemPaused.text = getString(R.string.xplane_paused)
                itemPaused.setBackgroundColor(Color.RED)
            }
        } else if (name == DataRef.Cockpit.Controls.LEFT_BRAKE_RATIO) {
            setBrakePercent(itemLeftBrake, getString(R.string.left_brake), value)
        } else if (name == DataRef.Cockpit.Controls.RIGHT_BRAKE_RATIO) {
            setBrakePercent(itemRightBrake, getString(R.string.right_brake), value)
        } else if (name == DataRef.Cockpit.Controls.PARKING_BRAKE_RATIO) {
            setBrakePercent(itemParkingBrake, getString(R.string.parking_brake), value)
        } else if (name == DataRef.Cockpit.Controls.SPEEDBRAKE_RATIO) {
            setBrakePercent(itemSpeedBrake, getString(R.string.speed_brake), value)
        } else if (name == DataRef.Cockpit.Warnings.Annunciators.REVERSE) {
            val bits = value.toInt()

            var engines = ""
            if (bits and 1 == 1) engines += "1"
            if (bits and 2 == 2) engines += "2"
            if (bits and 4 == 4) engines += "3"
            if (bits and 8 == 8) engines += "4"

            setItemString(itemReverseThrust, getString(R.string.thrust_direction), if (bits != 0) "REVERSE $engines" else "All Forward", bits != 0)
        } else if (name == DataRef.FlightModel.Position.INDICATED_AIRSPEED) {
            setItemString(itemIndicatedSpeed, getString(R.string.indicated_air_speed), oneDecimal.format(value.toDouble()) + "kts", false)
            globalAirspeed = value
            setGlideEstimate(globalNav1Distance, globalAirspeed, globalAltitude)
        } else if (name == DataRef.FlightModel.Position.Y_AGL) {
            setItemString(itemAltitudeGround, getString(R.string.altitude_agl), oneDecimal.format((value * Const.METERS_TO_FEET).toDouble()) + "ft", false)
        } else if (name == DataRef.FlightModel.Position.ELEVATION) {
            setItemString(itemAltitudeMSL, getString(R.string.altitude_msl), oneDecimal.format((value * Const.METERS_TO_FEET).toDouble()) + "ft", false)
            globalAltitude = value * Const.METERS_TO_FEET
            setGlideEstimate(globalNav1Distance, globalAirspeed, globalAltitude)
        } else if (name == DataRef.Cockpit.Gauges.ALTITUDE_FT_PILOT) {
            setItemString(itemAltitudeGauge, getString(R.string.altitude_gauge), oneDecimal.format(value.toDouble()) + "ft", false)
        } else if (name == DataRef.Cockpit.Controls.FLAP_HANDLE_DEPLOY_RATIO) {
            lastFlapsActual = zeroDecimal.format((40 * value).toDouble())
            setItemString(itemFlapsActual, getString(R.string.flaps_actual), lastFlapsActual, lastFlapsActual != lastFlapsDesired)
        } else if (name == DataRef.FlightModel.Controls.FLAPREQUEST) {
            lastFlapsDesired = zeroDecimal.format((40 * value).toDouble())
            setItemString(itemFlapsDesired, getString(R.string.flaps_desired), lastFlapsDesired, value > 0.01)
        } else if (name == DataRef.FlightModel.Gear.TIRE_VERTICAL_FORCE_N_MTR) {
            val verticalForceDisplayValue = oneDecimal.format(value.toDouble()) + "Nm"
            setItemString(itemForceGear, getString(R.string.gear_force), verticalForceDisplayValue, false)
        } else if (name == DataRef.FlightModel.Forces.G_NRML) {
            setItemString(itemForceVertical, getString(R.string.vert_force), oneDecimal.format(value.toDouble()) + "G", value < 0.75 || value > 1.25)
            graphForceVertical.set1Value(value - 1.0) // Center around 1G
            barForceVertical.setValue(value - 1.0)
        } else if (name == DataRef.Cockpit.Radios.NAV1_DME_DIST_M) {
            val distanceDisplayValue = oneDecimal.format(value.toDouble()) + "Nm"

            setItemString(itemDME1Distance, getString(R.string.nav1_dme), distanceDisplayValue, false)
            globalNav1Distance = value
            setGlideEstimate(globalNav1Distance, globalAirspeed, globalAltitude)
        } else if (name == DataRef.Cockpit.Radios.NAV2_DME_DIST_M) {
            setItemString(itemDME2Distance, getString(R.string.nav2_dme), oneDecimal.format(value.toDouble()) + "Nm", false)
        } else if (name == DataRef.FlightModel.Position.VH_IND_FPM) {
            val fpmDisplayValue = oneDecimal.format(value.toDouble()) + "fpm"
            val displayAsRed = value < -3000 || value > 3000

            setItemString(itemActualFPM, getString(R.string.actual_fpm), fpmDisplayValue, displayAsRed)
        } else if (name == DataRef.FlightModel.Position.LATITUDE) {
            globalLatitude = value
            setItemMap(globalLatitude, globalLongitude, globalHeading)
        } else if (name == DataRef.FlightModel.Position.LONGITUDE) {
            globalLongitude = value
            setItemMap(globalLatitude, globalLongitude, globalHeading)
        } else if (name == DataRef.Cockpit.Gauges.COMPASS_HEADING_DEG_MAG) {
            val headingDegreesMagDisplayValue = zeroDecimal.format(value.toDouble()) + "deg"

            setItemString(itemHeading, getString(R.string.mag_heading), headingDegreesMagDisplayValue, false)
            globalHeading = value
            setItemMap(globalLatitude, globalLongitude, globalHeading)
        } else if (name == DataRef.Cockpit.Switches.GENERIC_LIGHTS_SWITCH) {
            if (index < genericLightsText.size) {
                val lightsText = genericLightsText[index]
                lightsText!!.setText("G$index")

                if (value.toInt() > 0)
                    lightsText.setBackgroundColor(Color.LTGRAY)
                else
                    lightsText.setBackgroundColor(Color.GRAY)

                genericLightsValues[index] = value
            }
        } else if (name == DataRef.Cockpit.Switches.LANDING_LIGHTS_SWITCH) {
            if (index < landingLightsText.size) {
                val lightsText = landingLightsText[index]
                lightsText!!.setText("L$index")

                if (value.toInt() > 0)
                    lightsText.setBackgroundColor(Color.LTGRAY)
                else
                    lightsText.setBackgroundColor(Color.GRAY)

                landingLightsValues[index] = value
            }
        } else if (name == DataRef.B738.EFIS.CONTROL_CAPTAIN_MAPMODEPOS) {
            val mode: String = when (value.toInt()) {
                0 -> DataRef.APP
                1 -> DataRef.VOR
                2 -> DataRef.MAP
                3 -> DataRef.PLN
                else -> DataRef.NA
            }

            efis_mode_change.text = getString(R.string.efis_mode_format, mode)
            efis_mode_state = value.toInt()
        } else if (name == DataRef.Cockpit.Switches.EFIS_MAPSUBMODE) {
            val mode: String = when (value.toInt()) {
                0 -> DataRef.APP
                1 -> DataRef.VOR
                2 -> DataRef.MAP
                4 -> DataRef.PLN
                else -> DataRef.NA
            }

            efis_mode_change.text = getString(R.string.efis_mode_format, mode)
            efis_mode_state = value.toInt()

            if (efis_mode_state == 4) efis_mode_state = 3
        } else if (name == DataRef.NDPANEL_HSIMODEROTARY) {
            mirror_xhsi_value(
                name,
                DataRef.NDPANEL_HSIMODEROTARY,
                DataRef.Cockpit.Switches.EFIS_MAPSUBMODE,
                if (value.toInt() == 3) 4.0f else value
            )

            val mode: String = when (value.toInt()) {
                0 -> DataRef.VOR
                1 -> DataRef.APP
                2 -> DataRef.MAP
                3 -> DataRef.PLN
                else -> DataRef.NA
            }

            efis_mode_state = value.toInt()
            efis_mode_change.text = getString(R.string.efis_mode_format, mode)
        } else if (name == DataRef.Cockpit.Switches.EFIS_MAP_RANGE_SELECTOR || name == DataRef.NDPANEL_HSIRANGEROTARY) {
            mirror_xhsi_value(name, DataRef.NDPANEL_HSIRANGEROTARY, DataRef.Cockpit.Switches.EFIS_MAP_RANGE_SELECTOR, value)

            val range = (1 shl value.toInt()) * 10

            map_zoom_range.text = "" + range + "nm"

            efis_range_state = value.toInt()
        } else if (name == DataRef.B738.EFIS.CAPTAIN_MAP_RANGE) { // Zibo does the range differently than X-Plane
            val range = (1 shl value.toInt()) * 5
            map_zoom_range.text = "" + range + "nm"
            efis_range_state = value.toInt()
        } else if (name == DataRef.Cockpit.Switches.EFIS_SHOWS_TCAS || name == DataRef.NDPANEL_HSIRANGEBUTTON) {
            mirror_xhsi_value(name, DataRef.NDPANEL_HSIRANGEBUTTON, DataRef.Cockpit.Switches.EFIS_SHOWS_TCAS, value)
            efis_button_tfc.setState(value)
        } else if (name == DataRef.NDPANEL_HSIMODEBUTTON) { // No equivalent for ZB737 or XP737
            // hsiModeButton seems to either never change, or always go 0->1->0 very quickly, so perhaps it can never be set in FF767
            mirror_xhsi_value(name, DataRef.NDPANEL_HSIMODEBUTTON, DataRef.Cockpit.Switches.EFIS_SHOWS_CTR_TODO, value)
            efis_button_ctr.setState(value)
        } else if (name == DataRef.Cockpit.Switches.EFIS_SHOWS_AIRPORTS || name == DataRef.NDPANEL_MAP3 || name == DataRef.B738.EFIS.AIRPORT_ON) {
            mirror_xhsi_value(name, DataRef.NDPANEL_MAP3, DataRef.Cockpit.Switches.EFIS_SHOWS_AIRPORTS, value)
            efis_button_arpt.setState(value)
        } else if (name == DataRef.Cockpit.Switches.EFIS_SHOWS_WAYPOINTS || name == DataRef.NDPANEL_MAP5 || name == DataRef.B738.EFIS.FIX_ON) {
            mirror_xhsi_value(name, DataRef.NDPANEL_MAP5, DataRef.Cockpit.Switches.EFIS_SHOWS_WAYPOINTS, value)
            efis_button_wpt.setState(value)
        } else if (name == DataRef.Cockpit.Switches.EFIS_SHOWS_VORS || name == DataRef.NDPANEL_MAP2 || name == DataRef.B738.EFIS.VOR_ON) {
            mirror_xhsi_value(name, DataRef.NDPANEL_MAP2, DataRef.Cockpit.Switches.EFIS_SHOWS_VORS, value)
            efis_button_sta.setState(value)
        } else if (name == DataRef.Cockpit.Switches.EFIS_SHOWS_DATA || name == DataRef.NDPANEL_MAP4) {
            // TODO: Note that sim/cockpit/switches/EFIS_shows_data does not seem to exist in XP737, except it should
            // TODO: mirror_xhsi_value()
            efis_button_data.setState(value)
        } else if (name == DataRef.Cockpit.Switches.EFIS_SHOWS_WEATHER || name == DataRef.NDPANEL_HSIWXR || name == DataRef.B738.EFIS.WX_ON) {
            mirror_xhsi_value(name, DataRef.NDPANEL_HSIWXR, DataRef.Cockpit.Switches.EFIS_SHOWS_WEATHER, value)
            efis_button_wxr.setState(value)
        } else if (name == DataRef.Cockpit.Switches.EFIS_SHOWS_TERRAIN || name == DataRef.NDPANEL_HSITERR) {
            // TODO: Note that sim/cockpit/switches/EFIS_shows_terrain does not seem to exist in XP737, except it should
            // TODO: mirror_xhsi_value()
            efis_button_terr.setState(value)
        } else if (name == DataRef.B738.Ice.WINDOW_HEAT_L_FWD_POS) {
            window_heat_l_fwd.setState(value)
        } else if (name == DataRef.B738.Ice.WINDOW_HEAT_R_FWD_POS) {
            window_heat_r_fwd.setState(value)
        } else if (name == DataRef.B738.Ice.WINDOW_HEAD_L_SIDE_POS) {
            window_heat_l_side.setState(value)
        } else if (name == DataRef.B738.Ice.WINDOW_HEAD_R_SIDE_POS) {
            window_heat_r_side.setState(value)
        } else if (name == DataRef.B738.ToggleSwitch.CAPT_PROBES_POS) {
            capt_probes_pos.setState(value)
        } else if (name == DataRef.B738.ToggleSwitch.FO_PROBES_POS) {
            fo_probes_pos.setState(value)
        } else if (name == DataRef.Cockpit.Pressurization.MAX_ALLOWABLE_ALTITUDE_FT) {
            flt_alt_actual.text = "FL" + value.toInt() / 1000
        } else if (name == DataRef.B738.ToggleSwitch.SEATBELT_SIGN_POS) {
            if (value.toInt() == 0)
                seatbelt_sign.text = DataRef.SB_OFF
            else if (value.toInt() == 1)
                seatbelt_sign.text = DataRef.SB_AUTO
            else if (value.toInt() == 2)
                seatbelt_sign.text = DataRef.SB_ON
            else
                seatbelt_sign.text = DataRef.SB_NA
        } else {
            Log.e(Const.TAG, "Unhandled dataref name=$name, value=$value")
        }
    }

    private fun resetDisplay() {
        resetIndicators()
    }

    private fun resetIndicators() {
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
