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

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
import android.view.View
import android.widget.Button
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.DecimalFormat
import java.util.TreeMap
import kotlin.concurrent.thread

class MainActivity : Activity(), UDPReceiver.OnReceiveUDP, MulticastReceiver.OnReceiveMulticast {

    internal var xplane_address: InetAddress? = null
    internal var data_listener: UDPReceiver? = null
    internal var dref_listener: UDPReceiver? = null
    internal var becn_listener: MulticastReceiver? = null
    internal var mapDREF = TreeMap<String, Float>()
    internal var mapDATA = TreeMap<String, String>()
    internal var sequence = 0
    internal var fourDecimal = DecimalFormat("0.0000")
    internal var oneDecimal = DecimalFormat("0.0")
    internal var zeroDecimal = DecimalFormat("#")
    internal var lastFlapsDesired = ""
    internal var lastFlapsActual = ""
    internal var googleMap: GoogleMap? = null
    internal var googleMapMarker: Marker? = null
    internal var googleMapLine: Marker? = null

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

    override fun onConfigurationChanged(config: Configuration) {
        Log.d(Const.TAG, "onConfigurationChanged")
        super.onConfigurationChanged(config)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        versionText.text = "v" + BuildConfig.VERSION_NAME + " " + BuildConfig.VERSION_CODE + " " + BuildConfig.BUILD_TYPE
        exitButton.setOnClickListener {
            Log.d(Const.TAG, "Exiting")
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        resetButton.setOnClickListener {
            Log.d(Const.TAG, "Resetting internal map")
            mapDREF.clear()
            mapDATA.clear()
            updateDebugUI()
            resetIndicators()
        }
        simulateButton.setOnClickListener {
            Log.d(Const.TAG, "Simulating some data")
            onReceiveUDP(sample_groundspeed)
            onReceiveUDP(sample_data)

            Log.d(Const.TAG, "Processing res/raw/xplane_dump.raw file")
            val stream = resources.openRawResource(R.raw.xplane_dump)
            val packet = ByteArray(510)
            var count = 0
            try {
                while(true) {
                    val len = stream.read(packet)
                    if (len != packet.size)
                        break
                    onReceiveUDP(packet)
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

        fun button_to_cmnd(button: Button, cmnd: String) {
            button.setOnClickListener {
                check_thread(xplane_address, "Button for command $cmnd") {
                    dref_listener!!.sendCMND(xplane_address!!, cmnd)
                }
            }
        }

        button_to_cmnd(efis_mode_dn, "sim/instruments/EFIS_mode_dn")
        button_to_cmnd(map_zoom_out, "sim/instruments/map_zoom_out")
        button_to_cmnd(map_zoom_in, "sim/instruments/map_zoom_in")
        button_to_cmnd(efis_button_tfc, "laminar/B738/EFIS_control/capt/push_button/tfc_press")
        button_to_cmnd(efis_button_wxr, "laminar/B738/EFIS_control/capt/push_button/wxr_press")
        button_to_cmnd(efis_button_sta, "laminar/B738/EFIS_control/capt/push_button/sta_press")
        button_to_cmnd(efis_button_wpt, "laminar/B738/EFIS_control/capt/push_button/wpt_press")
        button_to_cmnd(efis_button_arpt, "laminar/B738/EFIS_control/capt/push_button/arpt_press")
        button_to_cmnd(efis_button_data, "laminar/B738/EFIS_control/capt/push_button/data_press")
        button_to_cmnd(efis_button_pos, "laminar/B738/EFIS_control/capt/push_button/pos_press")
        button_to_cmnd(efis_button_terr, "laminar/B738/EFIS_control/capt/push_button/terr_press")

        all_lights_on.setOnClickListener {
            check_thread(xplane_address, "Set all lights on") {
                for (i in 0 until landingLightsText.size)
                    dref_listener!!.sendDREF(xplane_address!!, "sim/cockpit2/switches/landing_lights_switch[$i]", 1.0f)
                for (i in 0 until genericLightsText.size)
                    dref_listener!!.sendDREF(xplane_address!!, "sim/cockpit2/switches/generic_lights_switch[$i]", 1.0f)
            }
        }

        all_lights_off.setOnClickListener {
            check_thread(xplane_address, "Set all lights off") {
                for (i in 0 until landingLightsText.size)
                    dref_listener!!.sendDREF(xplane_address!!, "sim/cockpit2/switches/landing_lights_switch[$i]", 0.0f)
                for (i in 0 until genericLightsText.size)
                    dref_listener!!.sendDREF(xplane_address!!, "sim/cockpit2/switches/generic_lights_switch[$i]", 0.0f)
            }
        }

        for (i in 0 until landingLightsText.size) {
            val t = TextView(this)
            t.setText("L$i=n/a")
            t.setPadding(20, 20, 20, 20)
            layout_lights.addView(t)
            t.setOnClickListener {
                val inverted = 1.0f - landingLightsValues[i]
                check_thread(xplane_address, "Clicked landing_lights_switch[$i] from " + landingLightsValues[i] + " to new " + inverted) {
                    dref_listener!!.sendDREF(xplane_address!!, "sim/cockpit2/switches/landing_lights_switch[$i]", inverted)
                }
            }
            landingLightsText[i] = t
        }
        for (i in 0 until genericLightsText.size) {
            val t = TextView(this)
            t.setText("G$=n/a")
            t.setPadding(20, 20, 20, 20)
            layout_lights.addView(t)
            t.setOnClickListener {
                val inverted = 1.0f - genericLightsValues[i]
                check_thread(xplane_address, "Clicked generic_lights_switch[$i] from " + genericLightsValues[i] + " to new " + inverted) {
                    dref_listener!!.sendDREF(xplane_address!!, "sim/cockpit2/switches/generic_lights_switch[$i]", inverted)
                }
            }
            genericLightsText[i] = t
        }

        resetIndicators()

        // Based on https://stackoverflow.com/questions/16536414/how-to-use-mapview-in-android-using-google-map-v2
        mapView.onCreate(savedInstanceState)
        // Do an async call which replaces deprecated getMap(): https://stackoverflow.com/questions/31371865/replace-getmap-with-getmapasync
        mapView.getMapAsync { map ->
            googleMap = map
            val pos = LatLng(0.0, 0.0) // Move the map to a default origin
            googleMap!!.moveCamera(CameraUpdateFactory.newLatLng(pos))
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
        Log.d(Const.TAG, "onResume(), starting listeners with IP address " + ip)
        ipAddress.text = ip + ":" + Const.UDP_DREF_PORT + "/" + Const.UDP_DATA_PORT

        dref_listener = UDPReceiver(Const.UDP_DREF_PORT, this)
        data_listener = UDPReceiver(Const.UDP_DATA_PORT, this)
        becn_listener = MulticastReceiver(Const.BECN_ADDRESS, Const.BECN_PORT, this)
    }

    override fun onPause() {
        Log.d(Const.TAG, "onPause(), cancelling UDP listeners")
        dref_listener!!.stopListener()
        data_listener!!.stopListener()
        becn_listener!!.stopListener()
        mapView.onPause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus:Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
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
        mapCoordinates.text = ("LatLong: "
                + (if (latitude < 0) "S" else "N")
                + fourDecimal.format(latitude)
                + " "
                + (if (longitude < 0) "W" else "E")
                + fourDecimal.format(longitude)
                + " - Heading: "
                + zeroDecimal.format(heading))

        val pos = LatLng(latitude.toDouble(), longitude.toDouble())
        googleMap!!.moveCamera(CameraUpdateFactory.newLatLng(pos))
        if (googleMapMarker == null) {
            // Draw an airplane icon centered around the coordinates
            googleMapMarker = googleMap!!.addMarker(MarkerOptions()
                    .position(pos)
                    .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_airplane_alpha))
                    .anchor(0.5f, 0.5f)
                    .title("Airplane"))
        }
        if (googleMapLine == null) {
            // Draw a line in the direction, need to use an image since there is no way to rotate a poly-line
            googleMapLine = googleMap!!.addMarker(MarkerOptions()
                    .position(pos)
                    .icon(BitmapDescriptorFactory.fromResource(R.drawable.line512))
                    .title("Line"))
        }
        googleMapMarker!!.position = pos
        googleMapMarker!!.rotation = heading
        googleMapLine!!.position = pos
        googleMapLine!!.rotation = heading
    }

    // Compute the feet-per-minute rate to get to NAV1 DME with 0 altitude at current airspeed
    fun setGlideEstimate(distance_nm: Float, airspeed_knots: Float, altitude_feet: Float) {
        val hours = distance_nm / airspeed_knots
        val minutes = hours * 60.0f

        val fpm = -altitude_feet / minutes

        setItemString(itemEstimateMins, "NAV1 Est Mins", if (airspeed_knots < 100 || distance_nm < 0.1) "N/A" else oneDecimal.format(minutes.toDouble()) + "mins", false)
        setItemString(itemEstimateFPM,  "NAV1 Est FPM",  if (airspeed_knots < 100 || distance_nm < 0.1) "N/A" else oneDecimal.format(fpm.toDouble()) + "fpm", false)
    }

    override fun onReceiveMulticast(buffer: ByteArray, source: InetAddress) {
        Log.d(Const.TAG, "Received BECN multicast packet from $source")
        Log.d(Const.TAG, "BECN packet printable: " + UDPReceiver.bytesToChars(buffer, buffer.size))
        Log.d(Const.TAG, "BECN packet hex: " + UDPReceiver.bytesToHex(buffer, buffer.size))
        xplaneHost.setText("X-Plane: " + source.getHostAddress())
        xplane_address = source

        // Request values that we are interested in getting updates for
        check_thread(xplane_address, "Requesting values via RREF") {
            dref_listener!!.sendRREF(xplane_address!!, "sim/cockpit/switches/EFIS_map_submode[0]", 2)
            dref_listener!!.sendRREF(xplane_address!!, "sim/cockpit/switches/EFIS_map_range_selector[0]", 2)
            dref_listener!!.sendRREF(xplane_address!!, "sim/cockpit/switches/EFIS_shows_airports[0]", 2)

        }
    }

    private fun processRREF(name: String, value: Float): Boolean {
        val indicator: Boolean
        if (name == "sim/cockpit/switches/EFIS_map_submode[0]") {
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
            efis_mode_dn.text = "EFIS " + mode
            indicator = true
        } else if (name == "sim/cockpit/switches/EFIS_map_range_selector[0]") {
            val range = (1 shl value.toInt()) * 10
            efis_button_tfc.text = "TFC " + range
            indicator = true
        } else if (name == "sim/cockpit/switches/EFIS_shows_airports[0]") {
            efis_button_arpt.text = "ARPT" + value.toInt()
            indicator = true
        } else {
            Log.e(Const.TAG, "Unhandled RREF name=$name, value=$value")
            indicator = false
        }
        return indicator
    }

    override fun onReceiveUDP(buffer: ByteArray) {
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

            val indicator: Boolean
            // Handle any of the indicators
            if (name == "sim/operation/misc/frame_rate_period[0]") {
                if (f < 0.0001) {
                    itemFPS.text = "FPS\nn/a"
                    itemFPS.setBackgroundColor(Color.GRAY)
                } else {
                    itemFPS.text = "FPS\n" + oneDecimal.format((1.0f / f).toDouble())
                    itemFPS.setBackgroundColor(Color.GREEN)
                }
                indicator = true
            } else if (name == "sim/cockpit2/controls/left_brake_ratio[0]") {
                setBrakePercent(itemLeftBrake, "Left Brake", f)
                indicator = true
            } else if (name == "sim/cockpit2/controls/right_brake_ratio[0]") {
                setBrakePercent(itemRightBrake, "Right Brake", f)
                indicator = true
            } else if (name == "sim/cockpit2/controls/parking_brake_ratio[0]") {
                setBrakePercent(itemParkingBrake, "Parking Brake", f)
                indicator = true
            } else if (name == "sim/cockpit2/controls/speedbrake_ratio[0]") {
                setBrakePercent(itemSpeedBrake, "Speed Brake (Air)", f)
                indicator = true
            } else if (name == "sim/cockpit/warnings/annunciators/reverse[0]") {
                val bits = f.toInt()
                var engines = ""
                if (bits and 1 == 1) engines += "1"
                if (bits and 2 == 2) engines += "2"
                if (bits and 4 == 4) engines += "3"
                if (bits and 8 == 8) engines += "4"
                setItemString(itemReverseThrust, "Thrust Direction", if (bits != 0) "REVERSE " + engines else "All Forward", bits != 0)
                indicator = true
            } else if (name == "sim/flightmodel/position/indicated_airspeed[0]") {
                setItemString(itemIndicatedSpeed, "Indicated Air Speed", oneDecimal.format(f.toDouble()) + "kts", false)
                globalAirspeed = f
                setGlideEstimate(globalNav1Distance, globalAirspeed, globalAltitude)
                indicator = true
            } else if (name == "sim/flightmodel/position/y_agl[0]") {
                setItemString(itemAltitudeGround, "Altitude AGL", oneDecimal.format((f * Const.METERS_TO_FEET).toDouble()) + "ft", false)
                indicator = true
            } else if (name == "sim/flightmodel/position/elevation[0]") {
                setItemString(itemAltitudeMSL, "Altitude MSL", oneDecimal.format((f * Const.METERS_TO_FEET).toDouble()) + "ft", false)
                globalAltitude = f * Const.METERS_TO_FEET
                setGlideEstimate(globalNav1Distance, globalAirspeed, globalAltitude)
                indicator = true
            } else if (name == "sim/cockpit2/gauges/indicators/altitude_ft_pilot[0]") {
                setItemString(itemAltitudeGauge, "Altitude Gauge", oneDecimal.format(f.toDouble()) + "ft", false)
                indicator = true
            } else if (name == "sim/cockpit2/controls/flap_handle_deploy_ratio[0]") {
                lastFlapsActual = zeroDecimal.format((40 * f).toDouble())
                setItemString(itemFlapsActual, "Flaps Actual", lastFlapsActual, lastFlapsActual != lastFlapsDesired)
                indicator = true
            } else if (name == "sim/flightmodel/controls/flaprqst[0]") {
                lastFlapsDesired = zeroDecimal.format((40 * f).toDouble())
                setItemString(itemFlapsDesired, "Flaps Desired", lastFlapsDesired, f > 0.01)
                indicator = true
            } else if (name == "sim/flightmodel2/gear/tire_vertical_force_n_mtr[0]") {
                setItemString(itemForceGear, "Gear Force", oneDecimal.format(f.toDouble()) + "Nm", false)
                indicator = true
            } else if (name == "sim/flightmodel/forces/g_nrml[0]") {
                setItemString(itemForceVertical, "Vert Force", oneDecimal.format(f.toDouble()) + "G", f < 0.75 || f > 1.25)
                graphForceVertical.set1Value(f - 1.0) // Center around 1G
                barForceVertical.setValue(f - 1.0)
                indicator = true
            } else if (name == "sim/cockpit/radios/nav1_dme_dist_m[0]") {
                setItemString(itemDME1Distance, "NAV1 DME", oneDecimal.format(f.toDouble()) + "Nm", false)
                globalNav1Distance = f
                setGlideEstimate(globalNav1Distance, globalAirspeed, globalAltitude)
                indicator = true
            } else if (name == "sim/cockpit/radios/nav2_dme_dist_m[0]") {
                setItemString(itemDME2Distance, "NAV2 DME", oneDecimal.format(f.toDouble()) + "Nm", false)
                indicator = true
            } else if (name == "sim/flightmodel/position/vh_ind_fpm") {
                setItemString(itemActualFPM, "Actual FPM", oneDecimal.format(f.toDouble()) + "fpm", f < -3000 || f > 3000)
                indicator = true
            } else if (name == "sim/flightmodel/position/latitude") {
                indicator = true
                globalLatitude = f
                setItemMap(globalLatitude, globalLongitude, globalHeading)
            } else if (name == "sim/flightmodel/position/longitude") {
                indicator = true
                globalLongitude = f
                setItemMap(globalLatitude, globalLongitude, globalHeading)
            } else if (name == "sim/graphics/view/view_heading") {
                setItemString(itemHeading, "True Heading", oneDecimal.format(f.toDouble()) + "deg", false)
                indicator = true
                globalHeading = f
                setItemMap(globalLatitude, globalLongitude, globalHeading)
            } else if (name.startsWith("sim/cockpit2/switches/generic_lights_switch[")) {
                // Extract out the number between [ ]
                var s = name.substring(name.indexOf("[") + 1)
                s = s.substring(0, s.indexOf("]"))
                val n = s.toInt()
                val t = genericLightsText[n]
                t!!.setText("G$n=" + f.toInt())
                if (f.toInt() > 0)
                    t.setBackgroundColor(Color.LTGRAY)
                else
                    t.setBackgroundColor(Color.GRAY)
                genericLightsValues[n] = f
                indicator = true
            } else if (name.startsWith("sim/cockpit2/switches/landing_lights_switch[")) {
                // Extract out the number between [ ]
                var s = name.substring(name.indexOf("[") + 1)
                s = s.substring(0, s.indexOf("]"))
                val n = s.toInt()
                val t = landingLightsText[n]
                t!!.setText("L$n=" + f.toInt())
                if (f.toInt() > 0)
                    t.setBackgroundColor(Color.LTGRAY)
                else
                    t.setBackgroundColor(Color.GRAY)
                landingLightsValues[n] = f
                indicator = true
            } else {
                // We don't need this value, it will only appear in the debug dump
                indicator = false
            }

            if (indicator) {
                // We requested this item
                mapDREF.put("Indicate: " + name, f)
            } else {
                // We didn't need this item, it will only be visible in the debug dump
                mapDREF.put("Unused: " + name, f)
            }

            updateDebugUI()
        } else if (buffer.size >= 5 && buffer[0] == 'R'.toByte() && buffer[1] == 'R'.toByte() && buffer[2] == 'E'.toByte() && buffer[3] == 'F'.toByte()) {
            // Handle RREF, packet type here, based on our earlier request
            // ["RREF,"=5bytes]
            // [id=4bytes] [float=4bytes]
            // ...
            Log.d(Const.TAG, "Found RREF packet bytes=" + buffer.size + ": " + UDPReceiver.bytesToChars(buffer, buffer.size))
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
                    Log.d(Const.TAG, "#$item, idx=$index: Parsed RREF with name=$name, id=$id, value=$value")
                    processRREF(name, value)
                } else {
                    Log.e(Const.TAG, "#$item, idx=$index: Ignoring unexpected RREF with id=$id, value=$value")
                }
                index += 8
                item ++
            }
        } else if (buffer.size >= 5 && buffer[0] == 'D'.toByte() && buffer[1] == 'A'.toByte() && buffer[2] == 'T'.toByte() && buffer[3] == 'A'.toByte() && buffer[4] == '*'.toByte()) {
            // Log.d(Const.TAG, "Found DATA* packet");
            // A data blob is an int (4 bytes) followed by 8 floats (8*4) = total 36
            // There can be many blobs in a single UDP packet (5 byte header + n*36 == packet size)
            val blob_length = 4 + 8 * 4
            var start: Int
            start = 5
            while (start < buffer.size) {
                val id = ByteBuffer.wrap(buffer, start, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val idx = start + 4
                val array = FloatArray(8)
                var debugArray = "["
                for (f in 0..7) {
                    array[f] = ByteBuffer.wrap(buffer, idx, 4).order(ByteOrder.LITTLE_ENDIAN).float
                    // Round floats so ridiculously tiny values get called 0
                    debugArray += XPlaneData.names[id * 8 + f] + "(" + XPlaneData.units[id * 8 + f] + ")="
                    debugArray += getCompactFloat(array[f])
                    if (f < 7)
                        debugArray += ", "
                }
                debugArray += "]"
                // Log.d(Const.TAG, "DATA* id=" + id + " array=" + debugArray);
                mapDATA.put("DATA*" + id, debugArray)
                updateDebugUI()
                start += blob_length
            }
            if (start != buffer.size)
                Log.e(Const.TAG, "Mismatch in buffer size, end was " + start + " but buffer length was " + buffer.size)
        } else {
            Log.e(Const.TAG, "Ignoring unknown packet: " + UDPReceiver.bytesToChars(buffer, buffer.size))
        }
    }

    fun updateDebugUI() {
        // If debug mode is not visible, do nothing
        if (layoutDebug.visibility != View.VISIBLE)
            return

        // Dump out current list of everything
        var out = "sequence=" + sequence + "\n"
        for ((key, value) in mapDREF) {
            // Log.d(Const.TAG, "Key=" + entry.getKey() + " Value=" + entry.getValue());
            out = out + "\n" + key + " = " + getCompactFloat(value)
        }
        out = out + "\n"
        for ((key, value) in mapDATA) {
            // Log.d(Const.TAG, "Key=" + entry.getKey() + " Value=" + entry.getValue());
            out = out + "\n" + key + " = " + value
        }
        debugText.text = out
    }

    fun resetIndicators() {
        try {
            // Set every View starting with "item" to a default gray color
            val c = Class.forName("$packageName.R\$id")
            val fields = c.fields
            for (i in fields.indices) {
                if (fields[i].name.startsWith("item")) {
                    val res = fields[i].getInt(null)
                    val v = findViewById(res)
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
            Log.e(Const.TAG, "Could not locate R.id class")
            e.printStackTrace()
        } catch (e: IllegalAccessException) {
            Log.e(Const.TAG, "Could not access R.id class")
            e.printStackTrace()
        }

        barForceVertical.reset()
        graphForceVertical.reset()

        barForceVertical.setMaximum(1.0) // +/- 1G (0G..2G)
        barForceVertical.setWarning(0.25)
        graphForceVertical.resetMaximum(1.0) // +/- 1G (0G..2G)
        graphForceVertical.setSize(1) // Only 1 value on the graph

        if (googleMapMarker != null)
            googleMapMarker!!.remove()
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
