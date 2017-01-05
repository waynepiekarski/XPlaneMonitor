package net.waynepiekarski.xplanemonitor;

import android.app.Activity;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.format.Formatter;
import android.util.Log;
import android.widget.TextView;

public class MainActivity extends Activity {

    UDPReceiver listener;
    TextView ipAddress;
    TextView debugText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ipAddress = (TextView)findViewById(R.id.ipAddress);
        debugText = (TextView)findViewById(R.id.debugText);
    }

    @Override
    protected void onResume() {
        super.onResume();

        WifiManager wm = (WifiManager) getSystemService(WIFI_SERVICE);
        String ip = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
        Log.d(Const.TAG, "onResume(), starting listener with IP address " + ip);
        ipAddress.setText(ip);

        listener = new UDPReceiver(Const.UDP_DREF_PORT, debugText, null);
    }

    @Override
    protected void onPause() {
        Log.d(Const.TAG, "onPause(), cancelling UDP listeners");
        listener.cancel(true);
        super.onPause();
    }
}
