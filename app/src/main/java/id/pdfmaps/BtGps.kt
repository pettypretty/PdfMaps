package id.pdfmaps

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

/** GPS eksternal lewat Bluetooth klasik (SPP) yang mengirim kalimat NMEA. */
class BtGps(
    private val onFix: (NmeaFix) -> Unit,
    private val onState: (String?) -> Unit, // nama perangkat bila tersambung, null bila putus
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var gen = 0
    private var socket: BluetoothSocket? = null

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, label: String) {
        disconnect()
        running = true
        val my = ++gen
        Thread {
            try {
                val s = device.createRfcommSocketToServiceRecord(SPP)
                socket = s
                s.connect()
                main.post { if (gen == my) onState(label) }
                val r = BufferedReader(InputStreamReader(s.inputStream))
                while (running && gen == my) {
                    val line = r.readLine() ?: break
                    val fix = Nmea.parse(line)
                    if (fix != null) main.post { if (gen == my) onFix(fix) }
                }
            } catch (_: Exception) {
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                main.post { if (gen == my) onState(null) }
            }
        }.also { it.isDaemon = true }.start()
    }

    fun disconnect() {
        running = false
        gen++
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
