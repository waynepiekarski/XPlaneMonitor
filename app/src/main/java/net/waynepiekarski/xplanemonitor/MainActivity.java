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

import android.app.Activity;
import android.content.res.Configuration;
import android.databinding.DataBindingUtil;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.gms.common.GooglePlayServicesNotAvailableException;
import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.MapsInitializer;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import net.waynepiekarski.xplanemonitor.databinding.ActivityMainBinding;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.DecimalFormat;
import java.util.TreeMap;

public class MainActivity extends Activity implements UDPReceiver.OnReceiveUDP {

    UDPReceiver data_listener, dref_listener;
    TreeMap<String, Float> mapDREF;
    TreeMap<String, String> mapDATA;
    int sequence;
    DecimalFormat oneDecimal = new DecimalFormat("#.#");
    DecimalFormat zeroDecimal = new DecimalFormat("#");
    String lastFlapsDesired = "";
    String lastFlapsActual = "";
    ActivityMainBinding binding;
    GoogleMap googleMap;
    Marker googleMapMarker;

    float globalLatitude = 0.0f;
    float globalLongitude = 0.0f;

    @Override
    public void onConfigurationChanged(Configuration config) {
        Log.d(Const.TAG, "onConfigurationChanged");
        super.onConfigurationChanged(config);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main);

        binding.version.setText("v" + BuildConfig.VERSION_NAME + " " + BuildConfig.VERSION_CODE + " " + BuildConfig.BUILD_TYPE);
        binding.exitButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.d(Const.TAG, "Exiting");
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        });
        binding.resetButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.d(Const.TAG, "Resetting internal map");
                mapDREF.clear();
                mapDATA.clear();
                updateDebugUI();
                resetIndicators();
            }
        });
        binding.simulateButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.d(Const.TAG, "Simulating some data");
                onReceiveUDP(sample_groundspeed);
                onReceiveUDP(sample_data);

                Log.d(Const.TAG, "Processing res/raw/xplane_dump.raw file");
                InputStream stream = getResources().openRawResource(R.raw.xplane_dump);
                byte packet[] = new byte[510];
                int len;
                int count = 0;
                try {
                    while ((len = stream.read(packet)) == packet.length) {
                        onReceiveUDP(packet);
                        count++;
                    }
                    Log.d(Const.TAG, "Finished processing rew/raw/xplane_dump.raw with " + count + " packets");
                    stream.close();
                } catch (IOException e) {
                    Log.e(Const.TAG, "Could not read from xplane_dump.raw " + e);
                }

                globalLatitude += 1;
                globalLongitude += 1;
                Log.d(Const.TAG, "Moving latitude and longitude to " + globalLatitude + " " + globalLongitude);
                setItemMap(globalLatitude, globalLongitude);
            }
        });

        binding.switchMainButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.d(Const.TAG, "Enabling main tab");
                binding.layoutMain.setVisibility(View.VISIBLE);
                binding.layoutDebug.setVisibility(View.GONE);
                binding.layoutMap.setVisibility(View.GONE);
                updateDebugUI();
            }
        });

        binding.switchDebugButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.d(Const.TAG, "Enabling debug tab");
                binding.layoutMain.setVisibility(View.GONE);
                binding.layoutDebug.setVisibility(View.VISIBLE);
                binding.layoutMap.setVisibility(View.GONE);
                updateDebugUI();
            }
        });

        binding.switchMapButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.d(Const.TAG, "Enabling map tab");
                binding.layoutMain.setVisibility(View.GONE);
                binding.layoutDebug.setVisibility(View.GONE);
                binding.layoutMap.setVisibility(View.VISIBLE);
                updateDebugUI();
            }
        });

        resetIndicators();

        mapDREF = new TreeMap<>();
        mapDATA = new TreeMap<>();
        sequence = 0;

        // Based on https://stackoverflow.com/questions/16536414/how-to-use-mapview-in-android-using-google-map-v2
        binding.mapView.onCreate(savedInstanceState);
        // Do an async call which replaces deprecated getMap(): https://stackoverflow.com/questions/31371865/replace-getmap-with-getmapasync
        binding.mapView.getMapAsync(new OnMapReadyCallback() {
            @Override
            public void onMapReady (GoogleMap map){
                googleMap = map;
                LatLng pos = new LatLng(0, 0); // Move the map to a default origin
                googleMap.moveCamera(CameraUpdateFactory.newLatLng(pos));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        binding.mapView.onResume();

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        String ip = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
        Log.d(Const.TAG, "onResume(), starting listeners with IP address " + ip);
        binding.ipAddress.setText(ip + ":" + Const.UDP_DREF_PORT + "/" + Const.UDP_DATA_PORT);

        dref_listener = new UDPReceiver(Const.UDP_DREF_PORT, null, this);
        data_listener = new UDPReceiver(Const.UDP_DATA_PORT, null, this);
    }

    @Override
    protected void onPause() {
        Log.d(Const.TAG, "onPause(), cancelling UDP listeners");
        dref_listener.stopListener();
        data_listener.stopListener();
        binding.mapView.onPause();
        super.onPause();
    }

    public static final byte[] sample_groundspeed = new byte[] { (byte)0x44, (byte)0x52, (byte)0x45, (byte)0x46, (byte)0x2B, (byte)0xC0, (byte)0xBB, (byte)0xB0, (byte)0x35, (byte)0x66, (byte)0x61, (byte)0x6b, (byte)0x65, (byte)0x2f, (byte)0x66, (byte)0x6C, (byte)0x69, (byte)0x67, (byte)0x68, (byte)0x74, (byte)0x6D, (byte)0x6F, (byte)0x64, (byte)0x65, (byte)0x6C, (byte)0x2F, (byte)0x70, (byte)0x6F, (byte)0x73, (byte)0x69, (byte)0x74, (byte)0x69, (byte)0x6F, (byte)0x6E, (byte)0x2F, (byte)0x67, (byte)0x72, (byte)0x6F, (byte)0x75, (byte)0x6E, (byte)0x64, (byte)0x73, (byte)0x70, (byte)0x65, (byte)0x65, (byte)0x64, (byte)0x5B, (byte)0x30, (byte)0x5D };
    public static final byte[] sample_data = hexStringToByteArray("444154412A000000000E88624133339F4100C079C4A7A6903D4C43903DA78D8F3D0000803F0000803F0200000000005C420000A6428045B14A00C079C400C079C400C079C400C079C400C079C40300000009000000AC660B3561810B3562810B3500C079C40A0000003A8A20353B8A20350D0000000000000000000000000000000000803F0000803F0000803F00000000000000000E0000000000803FA0C7993E000000000000000000C079C400C079C400C079C400C079C4220000006ED897436ED897434FFAA4434FFAA44300000000000000000000000000000000");

    // From http://stackoverflow.com/questions/140131/convert-a-string-representation-of-a-hex-dump-to-a-byte-array-using-java
    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    public String getCompactFloat(float in) {
        // Round floats so ridiculously tiny values get called 0
        if (in < 0.00001)
            return "0";
        else
            return "" + in;

    }

    public void setBrakePercent(TextView v, String label, float f) {
        int percent = (int)(f * 100);
        boolean red = (percent >= 1);
        setItemString(v, label, "" + percent + "%", (percent >= 1));
    }

    public void setItemString(TextView v, String label, String value, boolean red) {
        v.setText(label + "\n" + value);
        if (red)
            v.setBackgroundColor(Color.RED);
        else
            v.setBackgroundColor(Color.GREEN);
    }

    public void setItemMap(float latitude, float longitude) {
        binding.mapCoordinates.setText("LatLong: "
                + (latitude<0 ? "S" : "N")
                + latitude
                + " "
                + (longitude<0 ? "W" : "E")
                + longitude);

        LatLng pos = new LatLng(latitude, longitude);
        googleMap.moveCamera(CameraUpdateFactory.newLatLng(pos));
        if (googleMapMarker != null)
            googleMapMarker.remove();
        googleMapMarker = googleMap.addMarker(new MarkerOptions().position(pos).title("Airplane"));
    }

    public void onReceiveUDP(byte[] buffer) {
        // Log.d(Const.TAG, "onReceiveUDP bytes=" + buffer.length);
        sequence++;

        if ((buffer.length >= 5) && (buffer[0] == 'D') && (buffer[1] == 'R') && (buffer[2] == 'E') && (buffer[3] == 'F')) {
            // Handle DREF+ packet type here
            // ["DREF+"=5bytes] [float=4bytes] ["label/name/var[0]\0"=remaining_bytes]
            if (buffer[4] != '+') {
                Log.e(Const.TAG, "Cannot parse [" + buffer[4] + "] when expected '+' symbol");
            }
            float f = ByteBuffer.wrap(buffer, +5, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
            int zero;
            for (zero = 9; zero < buffer.length && buffer[zero] != '\0'; zero++)
                ;
            String name = new String(buffer, +9, zero - 9);
            // Log.d(Const.TAG, "Parsed DREF+ with float=" + f + " for variable=" + name);

            boolean indicator;
            // Handle any of the indicators
            if (name.equals("sim/operation/misc/frame_rate_period[0]")) {
                if (f < 0.0001) {
                    binding.itemFPS.setText("FPS\nn/a");
                    binding.itemFPS.setBackgroundColor(Color.GRAY);
                } else {
                    binding.itemFPS.setText("FPS\n" + oneDecimal.format(1.0f / f));
                    binding.itemFPS.setBackgroundColor(Color.GREEN);
                }
                indicator = true;
            } else if (name.equals("sim/cockpit2/controls/left_brake_ratio[0]")) {
                setBrakePercent(binding.itemLeftBrake, "Left Brake", f);
                indicator = true;
            } else if (name.equals("sim/cockpit2/controls/right_brake_ratio[0]")) {
                setBrakePercent(binding.itemRightBrake, "Right Brake", f);
                indicator = true;
            } else if (name.equals("sim/cockpit2/controls/parking_brake_ratio[0]")) {
                setBrakePercent(binding.itemParkingBrake, "Parking Brake", f);
                indicator = true;
            } else if (name.equals("sim/cockpit2/controls/speedbrake_ratio[0]")) {
                setBrakePercent(binding.itemSpeedBrake, "Speed Brake (Air)", f);
                indicator = true;
            } else if (name.equals("sim/cockpit/warnings/annunciators/reverse[0]")) {
                int bits = (int)f;
                String engines = "";
                if ((bits & 1) == 1) engines += "1";
                if ((bits & 2) == 2) engines += "2";
                if ((bits & 4) == 4) engines += "3";
                if ((bits & 8) == 8) engines += "4";
                setItemString(binding.itemReverseThrust, "Thrust Direction", (bits != 0 ? "REVERSE " + engines : "All Forward"), (bits != 0));
                indicator = true;
            } else if (name.equals("sim/flightmodel/position/indicated_airspeed[0]")) {
                setItemString(binding.itemIndicatedSpeed, "Indicated Air Speed", oneDecimal.format(f) + "kts", false);
                indicator = true;
            } else if (name.equals("sim/flightmodel/position/y_agl[0]")) {
                setItemString(binding.itemAltitudeGround, "Altitude AGL", oneDecimal.format(f * Const.METERS_TO_FEET) + "ft", false);
                indicator = true;
            } else if (name.equals("sim/flightmodel/position/elevation[0]")) {
                setItemString(binding.itemAltitudeMSL, "Altitude MSL", oneDecimal.format(f * Const.METERS_TO_FEET) + "ft", false);
                indicator = true;
            } else if (name.equals("sim/cockpit2/gauges/indicators/altitude_ft_pilot[0]")) {
                setItemString(binding.itemAltitudeGauge, "Altitude Gauge", oneDecimal.format(f) + "ft", false);
                indicator = true;
            } else if (name.equals("sim/cockpit2/controls/flap_handle_deploy_ratio[0]")) {
                lastFlapsActual = zeroDecimal.format(40 * f);
                setItemString(binding.itemFlapsActual, "Flaps Actual", lastFlapsActual, !lastFlapsActual.equals(lastFlapsDesired));
                indicator = true;
            } else if (name.equals("sim/flightmodel/controls/flaprqst[0]")) {
                lastFlapsDesired = zeroDecimal.format(40 * f);
                setItemString(binding.itemFlapsDesired, "Flaps Desired", lastFlapsDesired, f > 0.01);
                indicator = true;
            } else if (name.equals("sim/flightmodel2/gear/tire_vertical_force_n_mtr[0]")) {
                setItemString(binding.itemForceGear, "Gear Force", oneDecimal.format(f) + "Nm", false);
                indicator = true;
            } else if (name.equals("sim/flightmodel/forces/g_nrml[0]")) {
                setItemString(binding.itemForceVertical, "Vert Force", oneDecimal.format(f) + "G", false);
                binding.graphForceVertical.set1Value(f);
                binding.barForceVertical.setValue(f);
                indicator = true;
            } else if (name.equals("sim/cockpit/radios/nav1_dme_dist_m[0]")) {
                setItemString(binding.itemDME1Distance, "NAV1 DME", oneDecimal.format(f) + "Nm", false);
                indicator = true;
            } else if (name.equals("sim/cockpit/radios/nav2_dme_dist_m[0]")) {
                setItemString(binding.itemDME2Distance, "NAV2 DME", oneDecimal.format(f) + "Nm", false);
                indicator = true;
            } else if (name.equals("sim/flightmodel/position/latitude")) {
                indicator = true;
                globalLatitude = f;
                setItemMap(globalLatitude, globalLongitude);
            } else if (name.equals("sim/flightmodel/position/longitude")) {
                indicator = true;
                globalLongitude = f;
                setItemMap(globalLatitude, globalLongitude);
            } else {
                // We don't need this value, it will only appear in the debug dump
                indicator = false;
            }

            if (indicator) {
                // We requested this item
                mapDREF.put("Indicate: " + name, f);
            } else {
                // We didn't need this item, it will only be visible in the debug dump
                mapDREF.put("Unused: " + name, f);
            }

            updateDebugUI();
        } else if ((buffer.length >= 5) && (buffer[0] == 'D') && (buffer[1] == 'A') && (buffer[2] == 'T') && (buffer[3] == 'A') && (buffer[4] == '*')) {
            // Log.d(Const.TAG, "Found DATA* packet");
            // A data blob is an int (4 bytes) followed by 8 floats (8*4) = total 36
            // There can be many blobs in a single UDP packet (5 byte header + n*36 == packet size)
            final int blob_length = 4 + 8*4;
            int start;
            for (start = 5; start < buffer.length; start += blob_length) {
                int id = ByteBuffer.wrap(buffer, start, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                int idx = start + 4;
                float[] array = new float[8];
                String debugArray = "[";
                for (int f = 0; f < 8; f++) {
                    array[f] = ByteBuffer.wrap(buffer, idx, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
                    // Round floats so ridiculously tiny values get called 0
                    debugArray += XPlaneData.names[id*8+f] + "(" + XPlaneData.units[id*8+f] + ")=";
                    debugArray += getCompactFloat(array[f]);
                    if (f < 7)
                        debugArray += ", ";
                }
                debugArray += "]";
                // Log.d(Const.TAG, "DATA* id=" + id + " array=" + debugArray);
                mapDATA.put("DATA*" + id, debugArray);
                updateDebugUI();
            }
            if (start != buffer.length)
                Log.e(Const.TAG, "Mismatch in buffer size, end was " + start + " but buffer length was " + buffer.length);
        } else {
            Log.e(Const.TAG, "Ignoring unknown packet");
        }
    }

    public void updateDebugUI() {
        // If debug mode is not visible, do nothing
        if (binding.layoutDebug.getVisibility() != View.VISIBLE)
            return;

        // Dump out current list of everything
        String out = "sequence=" + sequence + "\n";
        for (TreeMap.Entry<String, Float> entry : mapDREF.entrySet()) {
            // Log.d(Const.TAG, "Key=" + entry.getKey() + " Value=" + entry.getValue());
            out = out + "\n" + entry.getKey() + " = " + getCompactFloat(entry.getValue());
        }
        out = out + "\n";
        for (TreeMap.Entry<String, String> entry : mapDATA.entrySet()) {
            // Log.d(Const.TAG, "Key=" + entry.getKey() + " Value=" + entry.getValue());
            out = out + "\n" + entry.getKey() + " = " + entry.getValue();
        }
        binding.debugText.setText(out);
    }

    public void resetIndicators() {
        try {
            // Set every View starting with "item" to a default gray color
            Class c = Class.forName(getPackageName() + ".R$id");
            Field[] fields = c.getFields();
            for (int i = 0; i < fields.length; i++) {
                if (fields[i].getName().startsWith("item")) {
                    int res = fields[i].getInt(null);
                    View v = findViewById(res);
                    v.setBackgroundColor(Color.GRAY);
                }
            }
        } catch (ClassNotFoundException e) {
            Log.e(Const.TAG, "Could not locate R.id class");
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            Log.e(Const.TAG, "Could not access R.id class");
            e.printStackTrace();
        }

        binding.barForceVertical.reset();
        binding.graphForceVertical.reset();

        binding.barForceVertical.setMaximum(3.5); // +/- 3G maximum
        binding.graphForceVertical.resetMaximum(3.5); // +/- 3G maximum
        binding.graphForceVertical.setSize(1); // Only 1 value on the graph

        if (googleMapMarker != null)
            googleMapMarker.remove();
    }
}
