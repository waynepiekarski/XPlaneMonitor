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
import kotlin.concurrent.thread
import kotlin.experimental.and


class UDPReceiver (port: Int, internal var callback: OnReceiveUDP) {
    private var socket: DatagramSocket? = null
    @Volatile private var cancelled = false

    interface OnReceiveUDP {
        fun onReceiveUDP(buffer: ByteArray)
    }

    fun stopListener() {
        cancelled = true
    }

    fun sendCMND(address: InetAddress, name: String) {
        val os = ByteArrayOutputStream()
        os.write("CMND".toByteArray())
        os.write(0x00)
        os.write(name.toByteArray())
        os.write(0x00)
        val ba = os.toByteArray()
        val dp = DatagramPacket(ba, ba.size, address, Const.UDP_DATA_PORT)

        Log.d(Const.TAG, "Sending outbound CMND packet: " + bytesToChars(dp.data, dp.data.size))
        // Log.d(Const.TAG, bytesToHex(dp.data, dp.data.size))
        socket!!.send(dp)
    }

    fun sendDREF(address: InetAddress, name: String, value: Float) {
        val os = ByteArrayOutputStream()
        os.write("DREF".toByteArray())
        os.write(0x00)
        os.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())
        os.write(name.toByteArray())
        os.write(0x00)
        for (i in 0 until 500 - 1 - name.length) {
            os.write(0x20)
        }

        val ba = os.toByteArray()
        val dp = DatagramPacket(ba, ba.size, address, Const.UDP_DATA_PORT)
        assertEquals(true, ba.size == 509)

        Log.d(Const.TAG, "Sending outbound DREF packet: " + bytesToChars(dp.data, dp.data.size))
        // Log.d(Const.TAG, bytesToHex(dp.data, dp.data.size))
        socket!!.send(dp)
    }

    internal var rref_table = arrayOfNulls<String>(64)
    internal var rref_id = 0

    fun lookupRREF(id: Int): String? {
        return rref_table[id]
    }

    fun sendRREF(address: InetAddress, name: String, freq: Int): String {
        val id = rref_id
        rref_table[id] = name
        rref_id++
        val os = ByteArrayOutputStream()
        os.write("RREF".toByteArray())
        os.write(0x00)
        os.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(freq).array())
        os.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(id).array())
        os.write(name.toByteArray())
        os.write(0x00)
        for (i in 0 until 400 - 1 - name.length) {
            os.write(0x20)
        }

        val ba = os.toByteArray()
        val dp = DatagramPacket(ba, ba.size, address, Const.UDP_DATA_PORT)

        Log.d(Const.TAG, "Sending outbound RREF packet with id=$id: " + bytesToChars(dp.data, dp.data.size))
        // Log.d(Const.TAG, bytesToHex(dp.data, dp.data.size))
        socket!!.send(dp)
        return name
    }


    init {
        Log.d(Const.TAG, "Created thread to listen on port " + port)
        thread(start = true) {
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
                        val data = Arrays.copyOfRange(buffer, 0, packet.length)
                        Handler(Looper.getMainLooper()).post {
                            callback.onReceiveUDP(data)
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
