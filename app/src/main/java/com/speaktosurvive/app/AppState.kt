package com.speaktosurvive.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/** Live state shared between the background service and the screen. */
object AppState {
    var guardRunning by mutableStateOf(false)
    var voiceStatus by mutableStateOf("Off")
    var sosActive by mutableStateOf(false)
    var statusLine by mutableStateOf("Protection is off")
    var lastEvent by mutableStateOf("")
    var resumeTick by mutableIntStateOf(0)
    var tab by mutableIntStateOf(0)
    var meshStatus by mutableStateOf("Off")
    var meshPeers by mutableIntStateOf(0)
    var inboxStatus by mutableStateOf("Off")
}

object Perms {
    fun granted(c: Context, p: String): Boolean =
        ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    fun required(): Array<String> {
        val list = ArrayList<String>()
        list.add(Manifest.permission.RECORD_AUDIO)
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        list.add(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list.toTypedArray()
    }

    /** Needed for the offline Bluetooth mesh. Optional: protection still starts without them. */
    fun mesh(): Array<String> {
        val list = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
            list.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        return list.toTypedArray()
    }

    fun meshGranted(c: Context): Boolean = mesh().all { granted(c, it) }

    fun allGranted(c: Context): Boolean = required().all { granted(c, it) }
}
