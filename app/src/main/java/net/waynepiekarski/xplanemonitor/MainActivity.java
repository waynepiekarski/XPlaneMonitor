package net.waynepiekarski.xplanemonitor;

import android.app.Activity;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.TreeMap;

public class MainActivity extends Activity implements UDPReceiver.OnReceiveUDP {

    UDPReceiver listener;
    TextView ipAddress;
    TextView debugText;
    Button resetButton;
    Button simButton;
    TreeMap<String, Float> mapDREF;
    int sequence;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ipAddress = (TextView)findViewById(R.id.ipAddress);
        debugText = (TextView)findViewById(R.id.debugText);
        resetButton = (Button)findViewById(R.id.reset);
        resetButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.d(Const.TAG, "Resetting internal map");
                mapDREF.clear();
                updateUI();
            }
        });
        simButton = (Button)findViewById(R.id.simulate);
        simButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.d(Const.TAG, "Simulating some data");
                onReceiveUDP(sample_groundspeed);
            }
        });


        mapDREF = new TreeMap<>();
        sequence = 0;
    }

    @Override
    protected void onResume() {
        super.onResume();

        WifiManager wm = (WifiManager) getSystemService(WIFI_SERVICE);
        String ip = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
        Log.d(Const.TAG, "onResume(), starting listener with IP address " + ip);
        ipAddress.setText(ip);

        listener = new UDPReceiver(Const.UDP_DREF_PORT, debugText, this);
    }

    @Override
    protected void onPause() {
        Log.d(Const.TAG, "onPause(), cancelling UDP listeners");
        listener.cancel(true);
        super.onPause();
    }

    public static final byte[] sample_groundspeed = new byte[] { (byte)0x44, (byte)0x52, (byte)0x45, (byte)0x46, (byte)0x2B, (byte)0xC0, (byte)0xBB, (byte)0xB0, (byte)0x35, (byte)0x66, (byte)0x61, (byte)0x6b, (byte)0x65, (byte)0x2f, (byte)0x66, (byte)0x6C, (byte)0x69, (byte)0x67, (byte)0x68, (byte)0x74, (byte)0x6D, (byte)0x6F, (byte)0x64, (byte)0x65, (byte)0x6C, (byte)0x2F, (byte)0x70, (byte)0x6F, (byte)0x73, (byte)0x69, (byte)0x74, (byte)0x69, (byte)0x6F, (byte)0x6E, (byte)0x2F, (byte)0x67, (byte)0x72, (byte)0x6F, (byte)0x75, (byte)0x6E, (byte)0x64, (byte)0x73, (byte)0x70, (byte)0x65, (byte)0x65, (byte)0x64, (byte)0x5B, (byte)0x30, (byte)0x5D };

    public void onReceiveUDP(byte[] buffer) {
        Log.d(Const.TAG, "onReceiveUDP bytes=" + buffer.length);
        sequence++;

        if ((buffer.length >= 5) && (buffer[0] == 'D') && (buffer[1] == 'R') && (buffer[2] == 'E') && (buffer[3] == 'F')) {
            // Handle DREF+ packet type here
            // ["DREF+"=5bytes] [float=4bytes] ["label/name/var[0]\0"=remaining_bytes]
            if (buffer[4] != '+') {
                Log.e(Const.TAG, "Cannot parse [" + buffer[4] + "] when expected '+' symbol");
            }
            float f = ByteBuffer.wrap(buffer,+5,4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
            int zero;
            for (zero = 9; zero < buffer.length && buffer[zero] != '\0'; zero++)
                ;
            String name = new String(buffer, +9, zero-9);
            Log.d(Const.TAG, "Parsed DREF+ with float=" + f + " for variable=" + name);

            mapDREF.put(name, f);
            updateUI();
        }
    }

    public void updateUI() {
        // Dump out current list of everything
        String out = "sequence=" + sequence + "\n";
        for (TreeMap.Entry<String, Float> entry : mapDREF.entrySet()) {
            // Log.d(Const.TAG, "Key=" + entry.getKey() + " Value=" + entry.getValue());
            out = out + "\n" + entry.getKey() + " = " + entry.getValue();
        }
        debugText.setText(out);
    }
}
