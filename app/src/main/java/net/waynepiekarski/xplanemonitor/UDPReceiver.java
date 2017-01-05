package net.waynepiekarski.xplanemonitor;

import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.text.format.Formatter;
import android.util.Log;
import android.widget.TextView;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static android.content.Context.WIFI_SERVICE;
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

    public static final byte[] sample_groundspeed = new byte[] { 0x44, 0x52, 0x45, 0x46, 0x2B, 0xC0, 0xBB, 0xB0, 0x35, 0x73, 0x69, 0x6D, 0x2F, 0x66, 0x6C, 0x69, 0x67, 0x68, 0x74, 0x6D, 0x6F, 0x64, 0x65, 0x6C, 0x2F, 0x70, 0x6F, 0x73, 0x69, 0x74, 0x69, 0x6F, 0x6E, 0x2F, 0x67, 0x72, 0x6F, 0x75, 0x6E, 0x64, 0x73, 0x70, 0x65, 0x65, 0x64, 0x5B, 0x30, 0x5D };

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

            byte[] buffer = new byte[64*1024]; // UDP maximum is 64kb
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            while(!isCancelled()) {
                // Log.d(Const.TAG, "Waiting for UDP packet on port " + port + " with maximum size " + buffer.length);
                try {
                    socket.receive(packet);
                    packetCount++;
                    publishProgress(packetCount);
                    Log.d(Const.TAG, "Received packet with " + packet.getLength() + " bytes of data");
                    Log.d(Const.TAG, "Hex dump = [" + bytesToHex(packet.getData(), packet.getLength()) + "]");
                    Log.d(Const.TAG, "Txt dump = [" + bytesToChars(packet.getData(), packet.getLength()) + "]");

                    if ((packet.getLength() >= 5) && (buffer[0] == 'D') && (buffer[1] == 'R') && (buffer[2] == 'E') && (buffer[3] == 'F') && (buffer[4] == '+')) {
                        // Handle DREF+ packet type here
                        // ["DREF+"=5bytes] [float=4bytes] ["label/name/var[0]\0"=remaining_bytes]
                        float f = ByteBuffer.wrap(buffer,+5,4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
                        int zero;
                        for (zero = 9; zero < packet.getLength() && buffer[zero] != '\0'; zero++)
                            ;
                        String name = new String(buffer, +9, zero-9);
                        Log.d(Const.TAG, "Parsed DREF+ with float=" + f + " for variable=" + name);
                    }
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


    // Extract all printable ASCII from a byte stream, do not print other chars
    public static String bytesToChars(byte[] bytes, int length) {
        assertEquals(true, bytes.length > length);
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
        assertEquals(true, bytes.length > length);
        char[] hexChars = new char[length * 2];
        for ( int j = 0; j < length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }
}
