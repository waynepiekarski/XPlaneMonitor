package net.waynepiekarski.xplanemonitor;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

public class MainActivity extends Activity {

    UDPReceiver listener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    @Override
    protected void onResume() {
        Log.d(Const.TAG, "onResume(), starting listener");
        listener = new UDPReceiver(Const.UDP_DREF_PORT, null, null);
    }

    @Override
    protected void onPause() {
        listener.cancel(true);
    }
}
