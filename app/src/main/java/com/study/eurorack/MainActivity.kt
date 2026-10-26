package com.study.eurorack

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient

/** WebView com o rack (assets/index.html) + ponte MIDI nativa: recebe MIDI de apps e dispositivos
 *  (USB, Bluetooth e portas virtuais de outros apps) via android.media.midi e entrega ao JS. */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private val opened = mutableSetOf<Int>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "$userAgentString EurorackApp"
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) = startMidi()
        }
        web.loadUrl("file:///android_asset/index.html")
    }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    @Suppress("DEPRECATION")
    private fun startMidi() {
        val mm = getSystemService(Context.MIDI_SERVICE) as? MidiManager ?: return
        val h = Handler(Looper.getMainLooper())
        mm.registerDeviceCallback(object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(info: MidiDeviceInfo) = attach(mm, info, h)
        }, h)
        mm.devices.forEach { attach(mm, it, h) }
    }

    private fun attach(mm: MidiManager, info: MidiDeviceInfo, h: Handler) {
        if (info.outputPortCount == 0 || !opened.add(info.id)) return
        val name = (info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI").replace("'", "")
        mm.openDevice(info, { dev ->
            dev?.openOutputPort(0)?.connect(Rx())
            js("midiDev('$name')")
        }, h)
    }

    private inner class Rx : MidiReceiver() {
        override fun onSend(d: ByteArray, off: Int, count: Int, ts: Long) {
            var k = off
            val end = off + count
            while (k < end) {
                val s = d[k].toInt() and 255
                when {
                    s >= 0xF8 -> { js("midiIn($s,0,0)"); k++ }
                    s >= 0xF0 -> k++
                    s >= 0x80 -> {
                        val n = if ((s and 0xF0) == 0xC0 || (s and 0xF0) == 0xD0) 1 else 2
                        if (k + n >= end) break
                        val a = d[k + 1].toInt() and 127
                        val b = if (n == 2) d[k + 2].toInt() and 127 else 0
                        js("midiIn($s,$a,$b)")
                        k += 1 + n
                    }
                    else -> k++
                }
            }
        }
    }
}
