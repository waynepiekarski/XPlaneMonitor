package net.waynepiekarski.xplanemonitor;

import android.os.AsyncTask;
import android.util.Log;
import android.widget.TextView;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Arrays;

import static junit.framework.Assert.assertEquals;


/*
 *  Listens on a port and processes UDP packets from X-Plane
 */
public class UDPReceiver extends AsyncTask<Integer, UDPReceiver.UDPData, Long> {
    long total;
    long count;
    String buffer;
    TextView statusView;
    OnReceiveUDP callback;
    DatagramSocket socket;

    public class UDPData {
        private byte[] data;
        public UDPData(byte[] in, int length) {
            data = Arrays.copyOfRange(in, 0, length);
        }
        public byte[] get() { return data; }
    }

    public interface OnReceiveUDP {
        void onReceiveUDP(byte[] array);
    }

    // Updates statusView with details (if not null)
    // Executes callback on UI thread at finish (if not null)
    public UDPReceiver(int port, TextView inStatusView, OnReceiveUDP inCallback) {
        callback = inCallback;
        statusView = inStatusView;
        Log.d(Const.TAG, "Created AsyncTask for port " + port);
        // Needed so we can run multiple AsyncTask without one being starved
        // From http://stackoverflow.com/questions/4068984/running-multiple-asynctasks-at-the-same-time-not-possible
        executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, port);
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

            byte[] buffer = new byte[64*1024]; // UDP maximum is 64kb
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            while(!isCancelled()) {
                // Log.d(Const.TAG, "Waiting for UDP packet on port " + port + " with maximum size " + buffer.length);
                try {
                    socket.receive(packet);
                    packetCount++;
                    // Log.d(Const.TAG, "Received packet with " + packet.getLength() + " bytes of data");
                    // Log.d(Const.TAG, "Hex dump = [" + bytesToHex(packet.getData(), packet.getLength()) + "]");
                    // Log.d(Const.TAG, "Txt dump = [" + bytesToChars(packet.getData(), packet.getLength()) + "]");
                    UDPData data = new UDPData(buffer, packet.getLength());

                    publishProgress(data);
                } catch (SocketTimeoutException e) {
                    // Log.d(Const.TAG, "Timeout, reading again ...");
                } catch (IOException e) {
                    Log.e(Const.TAG, "Failed to read packet " + e);
                }
            }
            Log.d(Const.TAG, "AsyncTask cancelled, closing down UDP listener on port " + port);
            socket.close();
        } catch (SocketException e) {
            Log.e(Const.TAG, "Failed to open socket " + e);
        }

        Log.d(Const.TAG, "AsyncTask has ended");
        return Long.valueOf(packetCount);
    }

    @Override
    protected void onProgressUpdate(UDPData... progress) {
        assertEquals(1, progress.length);
        // Log.d(Const.TAG, "onProgressUpdate (UI thread)");
        // This runs on the UI thread, we can use progress[0] or the packet text if we want
        //if (statusView != null)
            //statusView.setText("Downloaded " + progress[0]); // TODO: Update this value
        //if (callbackDREF != null)
            //callbackDREF.onReceive("Hello world"); // TODO: Update this string

        if (callback != null)
            callback.onReceiveUDP(progress[0].get());
    }

    protected void onPostExecute(Long result) {
        // This runs on the UI thread after the AsyncTask finishes, nothing to do here
        Log.d(Const.TAG, "onPostExecute (UI thread)");
    }


    // Extract all printable ASCII from a byte stream, do not print other chars
    public static String bytesToChars(byte[] bytes, int length) {
        assertEquals(true, bytes.length >= length);
        char[] textChars = new char[length * 2];
        for (int j = 0; j < length; j++ ) {
            int v = bytes[j];
            if ((v < 0x20) || (v > 0x7e))
                v = '~';
            else
                v = bytes[j];
            textChars[j * 2] = (char)(v & 0x7F);
            textChars[j * 2 + 1] = ' ';
        }
        return new String(textChars);
    }

    // From http://stackoverflow.com/questions/9655181/how-to-convert-a-byte-array-to-a-hex-string-in-java
    final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes, int length) {
        assertEquals(true, bytes.length >= length);
        char[] hexChars = new char[length * 2];
        for ( int j = 0; j < length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }
}
