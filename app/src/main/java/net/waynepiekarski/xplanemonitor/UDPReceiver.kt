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

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView

import java.io.IOException
import java.util.Arrays

import junit.framework.Assert.assertEquals
import java.io.ByteArrayOutputStream
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.experimental.and


/*
 *  Listens on a port and processes UDP packets from X-Plane
 *  Updates statusView with details (if not null)
 *  Executes callback on UI thread at finish (if not null)
 */
class UDPReceiver (port: Int, internal var statusView: TextView?, internal var callback: OnReceiveUDP?) {
    private var thread: Thread
    private var socket: DatagramSocket? = null
    @Volatile private var cancelled = false

    inner class UDPData(arg: ByteArray, length: Int) {
        private val data = Arrays.copyOfRange(arg, 0, length)

        fun get(): ByteArray {
            return data
        }
    }

    interface OnReceiveUDP {
        fun onReceiveUDP(buffer: ByteArray)
    }

    init {
        Log.d(Const.TAG, "Created AsyncTask for port " + port)
        thread = Thread(Runnable { backgroundListener(port) })
        thread.start()
    }

    fun stopListener() {
        cancelled = true
    }

    public fun sendPacket(dp: DatagramPacket) {
        Log.d(Const.TAG, "Sending outbound packet to " + socket.toString())
        Log.d(Const.TAG, bytesToChars(dp.data, dp.data.size))
//         Log.d(Const.TAG, bytesToHex(dp.data, dp.data.size))
        socket!!.send(dp)
    }

    public fun sendCMND(name: String) {
        val thread = Thread(Runnable {
            val os = ByteArrayOutputStream()
            os.write("CMND".toByteArray())
            os.write(0x00)
            os.write((name).toByteArray())
            os.write(0x00)
            val ba = os.toByteArray()

            // TODO: Remove broadcast because we receive our own packets, transmit only back to the X-Plane instance
            val dp = DatagramPacket(ba, ba.size, InetAddress.getByName("255.255.255.255"), Const.UDP_DATA_PORT)

            Log.d(Const.TAG, "Sending outbound CMND packet to " + socket.toString())
            Log.d(Const.TAG, bytesToChars(dp.data, dp.data.size))
            Log.d(Const.TAG, bytesToHex(dp.data, dp.data.size))
            socket!!.send(dp)
        })
        thread.start()
    }

    public fun sendDREF(name: String, value: Float) {
        val thread = Thread(Runnable {
            val os = ByteArrayOutputStream()
            os.write("DREF".toByteArray())
            os.write(0x00)
            os.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())
            os.write((name).toByteArray())
            os.write(0x00)
            for (i in 0 until 500 - 1 - name.length) {
                os.write(0x20)
            }

            val ba = os.toByteArray()
            val dp = DatagramPacket(ba, ba.size, InetAddress.getByName("255.255.255.255"), Const.UDP_DATA_PORT)
            assertEquals(true, ba.size == 509)

            Log.d(Const.TAG, "Sending outbound DREF packet to " + socket.toString())
            Log.d(Const.TAG, bytesToChars(dp.data, dp.data.size))
            Log.d(Const.TAG, bytesToHex(dp.data, dp.data.size))
            socket!!.send(dp)
        })
        thread.start()
    }


    // Handle DREF+ packet type here
    // ["DREF+"=5bytes] [float=4bytes] ["label/name/var[0]\0"=remaining_bytes]
    /*
    public fun createPacket(name: String, value: Float) : ByteArray {
        val os = ByteArrayOutputStream()
        os.write("DREF".toByteArray())
        os.write(0x00)
        val one = 1.0f
        os.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(one).array())
        os.write((name).toByteArray())
        os.write(0x00)
        val ba = os.toByteArray()
        return ba
    } */

    protected fun backgroundListener(port: Int) {
        var packetCount = 0
        Log.d(Const.TAG, "Receiving UDP packets on port " + port)
        try {
            socket = DatagramSocket(port)
            // Only block for 1 second before trying again, allows us to check for if cancelled
            socket!!.soTimeout = 1000
            socket!!.broadcast = true

            val buffer = ByteArray(64 * 1024) // UDP maximum is 64kb
            val packet = DatagramPacket(buffer, buffer.size)
            while (!cancelled) {
                // Log.d(Const.TAG, "Waiting for UDP packet on port " + port + " with maximum size " + buffer.length);
                try {
                    socket!!.receive(packet)
                    packetCount++
                    // Log.d(Const.TAG, "Received packet with " + packet.getLength() + " bytes of data");
                    // Log.d(Const.TAG, "Hex dump = [" + bytesToHex(packet.getData(), packet.getLength()) + "]");
                    // Log.d(Const.TAG, "Txt dump = [" + bytesToChars(packet.getData(), packet.getLength()) + "]");
                    val data = UDPData(buffer, packet.length)
                    Handler(Looper.getMainLooper()).post {
                        // This runs on the UI thread
                        //if (statusView != null)
                        //statusView.setText("Downloaded " + progress[0]); // TODO: Update this value
                        //if (callbackDREF != null)
                        //callbackDREF.onReceive("Hello world"); // TODO: Update this string
                        if (callback != null)
                            callback!!.onReceiveUDP(data.get())
                    }

                } catch (e: SocketTimeoutException) {
                    // Log.d(Const.TAG, "Timeout, reading again ...");
                } catch (e: IOException) {
                    Log.e(Const.TAG, "Failed to read packet " + e)
                }

            }
            Log.d(Const.TAG, "Thread is cancelled, closing down UDP listener on port " + port)
            socket!!.close()
        } catch (e: SocketException) {
            Log.e(Const.TAG, "Failed to open socket " + e)
        }

        Log.d(Const.TAG, "UDP listener thread for port $port has ended")
    }

    companion object {

        // Extract all printable ASCII from a byte stream, do not print other chars
        fun bytesToChars(bytes: ByteArray, length: Int): String {
            assertEquals(true, bytes.size >= length)
            val textChars = StringBuffer()
            for (j in 0 until length) {
                var v = bytes[j].toInt() and 0xFF
                if (v < 0x20 || v > 0x7e) {
                    textChars.append("(")
                    textChars.append(hexArray[v / 16])
                    textChars.append(hexArray[v and 0x0F])
                    textChars.append(")")
                } else {
                    textChars.append((v and 0x7F).toChar())
                }
            }
            return textChars.toString()
        }

        // From http://stackoverflow.com/questions/9655181/how-to-convert-a-byte-array-to-a-hex-string-in-java
        protected val hexArray = "0123456789ABCDEF".toCharArray()

        fun bytesToHex(bytes: ByteArray, length: Int): String {
            assertEquals(true, bytes.size >= length)
            val hexChars = CharArray(length * 2)
            for (j in 0 until length) {
                val v = bytes[j].toInt() and 0xFF
                hexChars[j * 2] = hexArray[v / 16]
                hexChars[j * 2 + 1] = hexArray[v and 0x0F]
            }
            return String(hexChars)
        }
    }
}
