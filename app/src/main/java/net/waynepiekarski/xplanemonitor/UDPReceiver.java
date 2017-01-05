package net.waynepiekarski.xplanemonitor;

import android.os.AsyncTask;
import android.util.Log;
import android.widget.TextView;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;

import static junit.framework.Assert.assertEquals;

/*
 *  Listens on a port and processes UDP packets from X-Plane
 */
public class UDPReceiver extends AsyncTask<Integer, Integer, Long> {
    long total;
    long count;
    String buffer;
    TextView statusView;
    OnReceivePacket callback;
    DatagramSocket socket;

    public interface OnReceivePacket {
        void onReceive(String data);
    }

    // Updates statusView with details (if not null)
    // Executes callback on UI thread at finish (if not null)
    public UDPReceiver(int port, TextView inStatusView, OnReceivePacket inCallback) {
        callback = inCallback;
        statusView = inStatusView;
        execute(port);
    }

    protected Long doInBackground(Integer... ports) {
        int packetCount = 0;
        assertEquals(ports.length, 1);
        int port = ports[0];
        Log.d(Const.TAG, "Receiving UDP packets on port " + port);
        try {
            socket = new DatagramSocket(port);
            // Only block for 1 second before trying again, allows us to check for isCancelled()
            socket.setSoTimeout(1000);
        } catch (SocketException e) {
            Log.e(Const.TAG, "Failed to open socket " + e);
        }

        byte[] buffer = new byte[64*1024]; // UDP maximum is 64kb
        while(!isCancelled()) {
            Log.d(Const.TAG, "Waiting for UDP packet on port " + port + "with maximum size " + buffer.length);
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                packetCount++;
                publishProgress(packetCount);
            } catch (IOException e) {
                Log.e(Const.TAG, "Failed to read packet " + e);
            }
        }

        Log.d(Const.TAG, "AsyncTask cancelled, closing down UDP listener on port " + port);
        socket.close();
        return Long.valueOf(packetCount);
    }

    protected void onProgressUpdate(Integer... progress) {
        // This runs on the UI thread, we can use progress[0] or the packet text if we want
        if (statusView != null)
            statusView.setText("Downloaded " + progress[0]); // TODO: Update this value
        if (callback != null)
            callback.onReceive("Hello world"); // TODO: Update this string
    }

    protected void onPostExecute(Long result) {
        // This runs on the UI thread after the AsyncTask finishes, nothing to do here
    }
}
