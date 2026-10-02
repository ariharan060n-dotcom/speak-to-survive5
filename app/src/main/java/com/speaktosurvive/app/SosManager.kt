package com.speaktosurvive.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.TelecomManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.Locale

/**
 * The emergency engine. When triggered it:
 *  1. sends the last known location to every contact straight away, choosing the route by connectivity:
 *       internet available -> message through the app (ntfy), falling back to SMS if that fails
 *       no internet        -> SMS
 *       nothing delivered  -> Bluetooth mesh relay through nearby phones that have this app
 *  2. places a call to the first contact (optional, off by default),
 *  3. fetches a fresh GPS fix and keeps sending location updates every minute,
 *  4. retries anything that was not delivered.
 */
class SosManager(
    private val ctx: Context,
    private val mesh: MeshNet?,
    private val onChange: () -> Unit
) {

    companion object {
        private const val ACTION_SENT = "com.speaktosurvive.app.SMS_SENT"
        private const val UPDATE_EVERY_MS = 60_000L
        private const val RETRY_EVERY_MS = 20_000L
        private const val FIX_TIMEOUT_MS = 15_000L
        private const val MAX_CYCLES = 30
        private const val MAX_RETRIES = 30
        private const val TEST_DURATION_MS = 20_000L
        private const val MESH_CHECK_MS = 10_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val fused = LocationServices.getFusedLocationProviderClient(ctx)
    private val pending = LinkedHashSet<String>()

    private var active = false
    private var meshOn = false
    private var testMode = false
    private var epoch = 0
    private var cycles = 0
    private var retries = 0
    private var lastText = ""
    private var lastLoc: Location? = null
    private var smsReceiver: BroadcastReceiver? = null
    private var retryRunnable: Runnable? = null

    fun isActive(): Boolean = active

    // ------------------------------------------------------------------ trigger

    fun trigger(reason: String, test: Boolean = false) {
        if (active) return
        val contacts = Prefs.contacts(ctx)
        if (contacts.isEmpty()) {
            AppState.lastEvent = "No emergency contact saved. Add one first."
            onChange()
            return
        }
        active = true
        testMode = test
        cycles = 0
        retries = 0
        meshOn = false
        epoch++
        val my = epoch

        AppState.sosActive = true
        AppState.lastEvent = if (test) "Test alert started" else "SOS triggered: $reason"
        buzz()
        registerSmsReceiver()
        onChange()

        lastKnown { loc ->
            if (my == epoch && active) {
                lastLoc = loc
                sendToAll(buildText(loc, false), my)
                if (!test && Prefs.autoCall(ctx)) {
                    placeCall(contacts.first().number)
                }
                if (test) {
                    main.postDelayed({ finishTest(my) }, TEST_DURATION_MS)
                } else {
                    freshFix(my)
                }
            }
        }
    }

    fun cancel() {
        if (!active) return
        val wasTest = testMode
        active = false
        epoch++
        main.removeCallbacksAndMessages(null)
        retryRunnable = null
        pending.clear()
        AppState.sosActive = false
        AppState.lastEvent = "You marked yourself safe. Alerts stopped."
        if (!wasTest) {
            val name = displayName()
            val safeText = "$name is safe now. Please ignore the earlier SOS alerts."
            for (c in Prefs.contacts(ctx)) deliverSafe(c.number, safeText)
            if (meshOn) mesh?.broadcastOwn("safe", name, null, Prefs.contacts(ctx))
        }
        meshOn = false
        onChange()
    }

    fun destroy() {
        active = false
        epoch++
        main.removeCallbacksAndMessages(null)
        retryRunnable = null
        pending.clear()
        try {
            smsReceiver?.let { ctx.unregisterReceiver(it) }
        } catch (e: Exception) {
            // already unregistered
        }
        smsReceiver = null
        AppState.sosActive = false
    }

    private fun finishTest(my: Int) {
        if (my == epoch && active) {
            active = false
            retryRunnable?.let { main.removeCallbacks(it) }
            retryRunnable = null
            AppState.sosActive = false
            AppState.lastEvent = "Test finished. If your contact received the alert, setup works."
            onChange()
        }
    }

    // ------------------------------------------------------------------ location loop

    private fun freshFix(my: Int) {
        if (my != epoch || !active) return
        current { loc ->
            if (my == epoch && active) {
                cycles++
                if (loc != null) {
                    lastLoc = loc
                    sendToAll(buildText(loc, true), my)
                }
                if (cycles < MAX_CYCLES) {
                    main.postDelayed({ freshFix(my) }, UPDATE_EVERY_MS)
                } else {
                    AppState.lastEvent = "Location updates paused. Tap I'M SAFE when you are safe."
                    onChange()
                }
            }
        }
    }

    // ------------------------------------------------------------------ SMS

    private fun sendToAll(text: String, my: Int) {
        lastText = text
        pending.clear()
        for (c in Prefs.contacts(ctx)) {
            pending.add(c.number)
            deliver(c.number, text, my)
        }
        if (meshOn) meshSend()
        scheduleRetry(my)
        // If nothing was confirmed shortly after, there is probably no signal: use the mesh.
        main.postDelayed({
            if (my == epoch && active && pending.isNotEmpty()) startMesh()
        }, MESH_CHECK_MS)
    }

    /** Route 1: internet (message through the app). Route 2: SMS. */
    private fun deliver(number: String, text: String, my: Int) {
        if (Net.isOnline(ctx)) {
            val both = Prefs.alsoSms(ctx)
            if (both) sendSms(number, text)
            Net.publish(number, if (testMode) "TEST ALERT" else "SOS ALERT", text) { ok ->
                main.post {
                    if (my == epoch && active) {
                        if (ok) {
                            delivered(number, "app")
                        } else if (!both) {
                            sendSms(number, text)
                        }
                    }
                }
            }
        } else {
            sendSms(number, text)
        }
    }

    private fun deliverSafe(number: String, text: String) {
        if (Net.isOnline(ctx)) {
            Net.publish(number, "SAFE", text) { ok ->
                if (!ok) main.post { sendSms(number, text) }
            }
            if (Prefs.alsoSms(ctx)) sendSms(number, text)
        } else {
            sendSms(number, text)
        }
    }

    /** Route 3: Bluetooth mesh through nearby phones that run this app. */
    private fun startMesh() {
        if (mesh == null || !Prefs.meshEnabled(ctx)) return
        if (!Perms.meshGranted(ctx)) {
            AppState.lastEvent = "No signal. Allow 'Nearby devices' in Settings to use the Bluetooth mesh."
            onChange()
            return
        }
        meshOn = true
        meshSend()
        onChange()
    }

    private fun meshSend() {
        val kind = if (testMode) "test" else "sos"
        mesh?.broadcastOwn(kind, displayName(), lastLoc, Prefs.contacts(ctx))
    }

    private fun delivered(number: String, via: String) {
        pending.remove(number)
        val total = Prefs.contacts(ctx).size
        val done = total - pending.size
        AppState.lastEvent = "Alert sent via $via to $done of $total contact(s)"
        onChange()
    }

    private fun scheduleRetry(my: Int) {
        retryRunnable?.let { main.removeCallbacks(it) }
        val r = Runnable {
            if (my == epoch && active && pending.isNotEmpty() && retries < MAX_RETRIES) {
                retries++
                AppState.lastEvent = "Alert not confirmed yet (weak signal?). Retrying..."
                onChange()
                for (n in pending.toList()) deliver(n, lastText, my)
                if (pending.isNotEmpty()) startMesh()
                scheduleRetry(my)
            }
        }
        retryRunnable = r
        main.postDelayed(r, RETRY_EVERY_MS)
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager =
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }

    private fun sendSms(number: String, text: String) {
        try {
            val intent = Intent(ACTION_SENT).setPackage(ctx.packageName).putExtra("number", number)
            val pi = PendingIntent.getBroadcast(
                ctx, number.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val sm = smsManager()
            val parts = sm.divideMessage(text)
            val sentIntents = ArrayList<PendingIntent>()
            for (i in 0 until parts.size) sentIntents.add(pi)
            sm.sendMultipartTextMessage(number, null, parts, sentIntents, null)
        } catch (e: Exception) {
            AppState.lastEvent = "Could not send SMS to $number: ${e.message}"
            onChange()
        }
    }

    private fun registerSmsReceiver() {
        if (smsReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val number = intent?.getStringExtra("number") ?: return
                onSmsResult(number, resultCode == Activity.RESULT_OK)
            }
        }
        ContextCompat.registerReceiver(
            ctx, r, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        smsReceiver = r
    }

    private fun onSmsResult(number: String, ok: Boolean) {
        if (ok) {
            delivered(number, "SMS")
        } else {
            AppState.lastEvent = "SMS to $number failed. Trying the Bluetooth mesh..."
            if (active) startMesh()
            onChange()
        }
    }

    private fun displayName(): String = Prefs.userName(ctx).trim().ifBlank { "A user" }.take(20)

    private fun fmt(v: Double): String = String.format(Locale.US, "%.6f", v)

    private fun buildText(loc: Location?, update: Boolean): String {
        val prefix = when {
            testMode -> "TEST ALERT (not real): "
            update -> "SOS UPDATE: "
            else -> "SOS! "
        }
        val tail = if (loc == null) {
            "Location unavailable, updates will follow."
        } else {
            val ageMs = System.currentTimeMillis() - loc.time
            val old = if (ageMs > 120_000L) " (old fix)" else ""
            "Location: https://maps.google.com/?q=${fmt(loc.latitude)},${fmt(loc.longitude)}$old"
        }
        return "$prefix${displayName()} needs help. $tail"
    }

    // ------------------------------------------------------------------ call

    @SuppressLint("MissingPermission")
    private fun placeCall(number: String) {
        if (!Perms.granted(ctx, Manifest.permission.CALL_PHONE)) {
            AppState.lastEvent = "Call permission missing, call skipped"
            onChange()
            return
        }
        val uri = Uri.fromParts("tel", number, null)
        try {
            val tm = ctx.getSystemService(TelecomManager::class.java)
            tm.placeCall(uri, Bundle())
        } catch (e: Exception) {
            try {
                val i = Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            } catch (e2: Exception) {
                AppState.lastEvent = "Could not place the call: ${e2.message}"
                onChange()
            }
        }
    }

    // ------------------------------------------------------------------ location helpers

    private fun hasLocationPermission(): Boolean =
        Perms.granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ||
            Perms.granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

    @SuppressLint("MissingPermission")
    private fun lastKnown(cb: (Location?) -> Unit) {
        if (!hasLocationPermission()) {
            cb(null)
            return
        }
        try {
            fused.lastLocation
                .addOnSuccessListener { l -> cb(l ?: platformLastKnown()) }
                .addOnFailureListener { cb(platformLastKnown()) }
        } catch (e: Exception) {
            cb(platformLastKnown())
        }
    }

    @SuppressLint("MissingPermission")
    private fun platformLastKnown(): Location? {
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            providers.mapNotNull {
                try {
                    lm.getLastKnownLocation(it)
                } catch (e: Exception) {
                    null
                }
            }.maxByOrNull { it.time }
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun current(cb: (Location?) -> Unit) {
        if (!hasLocationPermission()) {
            cb(null)
            return
        }
        var done = false
        val finish: (Location?) -> Unit = { l ->
            if (!done) {
                done = true
                cb(l)
            }
        }
        val cts = CancellationTokenSource()
        main.postDelayed({
            if (!done) {
                cts.cancel()
                finish(null)
            }
        }, FIX_TIMEOUT_MS)
        try {
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { l -> finish(l) }
                .addOnFailureListener { finish(null) }
        } catch (e: Exception) {
            finish(null)
        }
    }

    // ------------------------------------------------------------------ feedback

    @Suppress("DEPRECATION")
    private fun buzz() {
        try {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250), -1))
        } catch (e: Exception) {
            // vibration is a nice-to-have
        }
    }
}
