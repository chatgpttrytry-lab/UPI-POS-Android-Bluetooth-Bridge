package com.upipos.bridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.OutputStream
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val adapter: BluetoothAdapter? get() = BluetoothAdapter.getDefaultAdapter()
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null
    private val prefs by lazy { getSharedPreferences("printer", MODE_PRIVATE) }
    private val permissionRequest = 441

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(AndroidPrinterBridge(), "AndroidPrinter")
        web.loadUrl("file:///android_asset/index.html")
        ensureBluetoothPermission()
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun ensureBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31 && !hasConnectPermission()) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), permissionRequest)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest && (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, "Bluetooth permission is required to use the thermal printer.", Toast.LENGTH_LONG).show()
        }
    }

    private fun bonded(): List<BluetoothDevice> {
        if (!hasConnectPermission()) {
            runOnUiThread { ensureBluetoothPermission() }
            return emptyList()
        }
        return try { adapter?.bondedDevices?.toList()?.sortedBy { it.name ?: it.address } ?: emptyList() }
        catch (_: SecurityException) { emptyList() }
    }

    private fun connectAddress(address: String): Boolean {
        if (!hasConnectPermission()) return false
        return try {
            disconnectSocket()
            val device = adapter?.getRemoteDevice(address) ?: return false
            val s = device.createRfcommSocketToServiceRecord(sppUuid)
            s.connect()
            socket = s
            output = s.outputStream
            true
        } catch (e: Exception) {
            disconnectSocket()
            false
        }
    }

    private fun disconnectSocket() {
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        output = null
        socket = null
    }

    inner class AndroidPrinterBridge {
        @JavascriptInterface fun getPairedDevices(): String {
            val list = bonded()
            return list.joinToString(prefix = "[", postfix = "]") {
                val name = try { it.name ?: "Bluetooth printer" } catch (_: Exception) { "Bluetooth printer" }
                val address = try { it.address } catch (_: Exception) { "" }
                """{"name":${json(name)},"address":${json(address)}}"""
            }
        }

        @JavascriptInterface fun setDefault(address: String, name: String) {
            prefs.edit().putString("default_address", address).putString("default_name", name).apply()
        }

        @JavascriptInterface fun scan(): Boolean {
            runOnUiThread {
                if (!hasConnectPermission()) { ensureBluetoothPermission(); return@runOnUiThread }
                val devices = bonded()
                if (devices.isEmpty()) {
                    AlertDialog.Builder(this@MainActivity).setTitle("No paired Bluetooth devices")
                        .setMessage("Pair your MT580P printer in Android Settings → Bluetooth, then return here.")
                        .setPositiveButton("OK", null).show()
                    return@runOnUiThread
                }
                val labels = devices.map {
                    val n = try { it.name ?: "Bluetooth printer" } catch (_: Exception) { "Bluetooth printer" }
                    val a = try { it.address } catch (_: Exception) { "" }
                    "$n\\n$a"
                }.toTypedArray()
                AlertDialog.Builder(this@MainActivity).setTitle("Choose Bluetooth printer")
                    .setItems(labels) { _, which ->
                        val d = devices[which]
                        val address = try { d.address } catch (_: Exception) { "" }
                        val name = try { d.name ?: "Bluetooth printer" } catch (_: Exception) { "Bluetooth printer" }
                        Thread {
                            val ok = connectAddress(address)
                            runOnUiThread {
                                if (ok) {
                                    web.evaluateJavascript("window.onAndroidPrinterConnected(${json(name)},${json(address)})", null)
                                    Toast.makeText(this@MainActivity, "Connected to $name", Toast.LENGTH_SHORT).show()
                                } else Toast.makeText(this@MainActivity, "Could not connect. Check printer power and pairing.", Toast.LENGTH_LONG).show()
                            }
                        }.start()
                    }.setNegativeButton("Cancel", null).show()
            }
            return true
        }

        @JavascriptInterface fun connect(address: String): Boolean {
            if (address.isBlank()) return false
            return connectAddress(address)
        }

        @JavascriptInterface fun print(base64: String): Boolean {
            return try {
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                val out = output ?: return false
                synchronized(this@MainActivity) { out.write(bytes); out.flush() }
                true
            } catch (_: Exception) { false }
        }

        @JavascriptInterface fun disconnect() { disconnectSocket() }
    }

    private fun json(value: String): String {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r") + "\""
    }

    override fun onDestroy() {
        disconnectSocket()
        web.removeJavascriptInterface("AndroidPrinter")
        web.destroy()
        super.onDestroy()
    }
}
