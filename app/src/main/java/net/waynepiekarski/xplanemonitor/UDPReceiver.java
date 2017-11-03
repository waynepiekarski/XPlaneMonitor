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

import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
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
public class UDPReceiver {
    long total;
    long count;
    String buffer;
    TextView statusView;
    OnReceiveUDP callback;
    DatagramSocket socket;
    Thread thread;
    volatile boolean cancelled = false;

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
    public UDPReceiver(final int port, TextView inStatusView, OnReceiveUDP inCallback) {
        callback = inCallback;
        statusView = inStatusView;
        Log.d(Const.TAG, "Created AsyncTask for port " + port);
        thread = new Thread(new Runnable() {
            public void run() {
                backgroundListener(port);
            }
        });
        thread.start();
    }

    public void stopListener() {
        cancelled = true;
    }

    protected void backgroundListener(int port) {
        int packetCount = 0;
        Log.d(Const.TAG, "Receiving UDP packets on port " + port);
        try {
            socket = new DatagramSocket(port);
            // Only block for 1 second before trying again, allows us to check for if cancelled
            socket.setSoTimeout(1000);

            byte[] buffer = new byte[64*1024]; // UDP maximum is 64kb
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            while(!cancelled) {
                // Log.d(Const.TAG, "Waiting for UDP packet on port " + port + " with maximum size " + buffer.length);
                try {
                    socket.receive(packet);
                    packetCount++;
                    // Log.d(Const.TAG, "Received packet with " + packet.getLength() + " bytes of data");
                    // Log.d(Const.TAG, "Hex dump = [" + bytesToHex(packet.getData(), packet.getLength()) + "]");
                    // Log.d(Const.TAG, "Txt dump = [" + bytesToChars(packet.getData(), packet.getLength()) + "]");
                    final UDPData data = new UDPData(buffer, packet.getLength());
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        public void run() {
                            // This runs on the UI thread
                            //if (statusView != null)
                            //statusView.setText("Downloaded " + progress[0]); // TODO: Update this value
                            //if (callbackDREF != null)
                            //callbackDREF.onReceive("Hello world"); // TODO: Update this string
                            if (callback != null)
                                callback.onReceiveUDP(data.get());
                        }
                    });

                } catch (SocketTimeoutException e) {
                    // Log.d(Const.TAG, "Timeout, reading again ...");
                } catch (IOException e) {
                    Log.e(Const.TAG, "Failed to read packet " + e);
                }
            }
            Log.d(Const.TAG, "Thread is cancelled, closing down UDP listener on port " + port);
            socket.close();
        } catch (SocketException e) {
            Log.e(Const.TAG, "Failed to open socket " + e);
        }

        Log.d(Const.TAG, "UDP listener thread for port " + port + " has ended");
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
