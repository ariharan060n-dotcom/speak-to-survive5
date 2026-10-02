package com.speaktosurvive.app

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Offline phone-to-phone SOS relay (works with no mobile signal and no internet).
 *
 * Uses Google Nearby Connections (Bluetooth LE + Bluetooth + Wi-Fi Direct underneath).
 * - While protection is on, every phone advertises itself so others can find it.
 * - When an SOS starts, the sender searches for nearby phones running this app and
 *   hands them the alert. Each receiver shows it, passes it on to other phones nearby
 *   (flooding, with a hop limit), and if it has internet or mobile signal it forwards
 *   the alert to the sender's contacts (acts as a gateway).
 */
class MeshNet(private val ctx: Context) {

    companion object {
        private const val SERVICE_ID = "com.speaktosurvive.app.mesh"
        private const val TTL = 6
        private const val DISCOVER_MS = 120_000L
        private const val GATEWAY_COOLDOWN_MS = 90_000L
        private const val MAX_RECENT = 10
        private const val MAX_SEEN = 300
    }

    private val main = Handler(Looper.getMainLooper())
    private val client: ConnectionsClient = Nearby.getConnectionsClient(ctx)

    private val peers = LinkedHashSet<String>()
    private val connecting = HashSet<String>()
    private val seen = LinkedHashSet<String>()
    private val recent = LinkedHashMap<String, String>()       // origin id -> latest message json
    private val gatewayLast = HashMap<String, Long>()

    private val localName = "STS-" + (1000..9999).random()
    private val originId = UUID.randomUUID().toString()

    private var advertising = false
    private var discovering = false
    private var stopped = false
    private var stopDiscoveryRunnable: Runnable? = null

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        if (stopped) return
        if (!Prefs.meshEnabled(ctx)) {
            AppState.meshStatus = "Off"
            return
        }
        if (!Perms.meshGranted(ctx)) {
            AppState.meshStatus = "Allow Nearby devices permission"
            return
        }
        if (advertising) return
        try {
            val opts = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
            client.startAdvertising(localName, SERVICE_ID, connCb, opts)
                .addOnSuccessListener {
                    advertising = true
                    updateStatus()
                }
                .addOnFailureListener { e ->
                    if ((e as? ApiException)?.statusCode == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) {
                        advertising = true
                        updateStatus()
                    } else {
                        AppState.meshStatus = "Unavailable (${e.message})"
                    }
                }
        } catch (e: Exception) {
            AppState.meshStatus = "Unavailable (${e.message})"
        }
    }

    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        try {
            client.stopAdvertising()
            client.stopDiscovery()
            client.stopAllEndpoints()
        } catch (e: Exception) {
            // ignore
        }
        advertising = false
        discovering = false
        peers.clear()
        connecting.clear()
        AppState.meshPeers = 0
        AppState.meshStatus = "Off"
    }

    // ------------------------------------------------------------------ sending

    /** Called by the SOS engine when internet and SMS cannot reach the contacts. */
    fun broadcastOwn(kind: String, name: String, loc: Location?, contacts: List<Contact>) {
        if (stopped || !Prefs.meshEnabled(ctx) || !Perms.meshGranted(ctx)) return
        val o = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("src", originId)
            .put("k", kind)
            .put("n", name)
            .put("t", System.currentTimeMillis())
            .put("ttl", TTL)
        if (loc != null) {
            o.put("la", loc.latitude)
            o.put("lo", loc.longitude)
        }
        val arr = JSONArray()
        for (c in contacts) arr.put(c.number)
        o.put("c", arr)
        val s = o.toString()
        seen.add(o.getString("id"))
        remember(originId, s)
        start()
        ensureDiscovery()
        sendToPeers(s, null)
        AppState.lastEvent = if (peers.isEmpty()) {
            "No signal: searching for nearby phones with this app..."
        } else {
            "No signal: alert passed to ${peers.size} nearby phone(s)"
        }
    }

    private fun remember(origin: String, json: String) {
        recent.remove(origin)
        recent[origin] = json
        while (recent.size > MAX_RECENT) {
            val first = recent.keys.first()
            recent.remove(first)
        }
    }

    private fun sendToPeers(json: String, except: String?) {
        val targets = peers.filter { it != except }
        if (targets.isEmpty()) return
        try {
            client.sendPayload(targets, Payload.fromBytes(json.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            // peer may have just left
        }
    }

    // ------------------------------------------------------------------ discovery

    private fun ensureDiscovery() {
        if (stopped) return
        stopDiscoveryRunnable?.let { main.removeCallbacks(it) }
        val r = Runnable {
            try {
                client.stopDiscovery()
            } catch (e: Exception) {
                // ignore
            }
            discovering = false
        }
        stopDiscoveryRunnable = r
        main.postDelayed(r, DISCOVER_MS)
        if (discovering) return
        try {
            val opts = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
            client.startDiscovery(SERVICE_ID, discCb, opts)
                .addOnSuccessListener { discovering = true }
                .addOnFailureListener { e ->
                    if ((e as? ApiException)?.statusCode == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) {
                        discovering = true
                    }
                }
        } catch (e: Exception) {
            // permission or hardware problem
        }
    }

    private val discCb = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (peers.contains(endpointId) || connecting.contains(endpointId)) return
            connecting.add(endpointId)
            try {
                client.requestConnection(localName, endpointId, connCb)
                    .addOnFailureListener { connecting.remove(endpointId) }
            } catch (e: Exception) {
                connecting.remove(endpointId)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            connecting.remove(endpointId)
        }
    }

    private val connCb = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            try {
                client.acceptConnection(endpointId, payloadCb)
            } catch (e: Exception) {
                connecting.remove(endpointId)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            connecting.remove(endpointId)
            if (result.status.isSuccess) {
                peers.add(endpointId)
                updateStatus()
                // give the new neighbour everything we are currently relaying
                for (m in recent.values) sendToPeers(m, null)
            }
        }

        override fun onDisconnected(endpointId: String) {
            peers.remove(endpointId)
            connecting.remove(endpointId)
            updateStatus()
        }
    }

    private fun updateStatus() {
        AppState.meshPeers = peers.size
        AppState.meshStatus = when {
            !advertising -> "Starting"
            peers.isEmpty() -> "Ready, no phones nearby yet"
            else -> "${peers.size} phone(s) connected"
        }
    }

    // ------------------------------------------------------------------ receiving

    private val payloadCb = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            handle(String(bytes, Charsets.UTF_8), endpointId)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // small messages arrive in one piece
        }
    }

    private fun handle(json: String, from: String) {
        try {
            val o = JSONObject(json)
            val id = o.getString("id")
            if (!seen.add(id)) return
            while (seen.size > MAX_SEEN) seen.remove(seen.first())

            val kind = o.optString("k", "sos")
            val name = o.optString("n", "")
            val lat = if (o.has("la")) o.getDouble("la") else null
            val lon = if (o.has("lo")) o.getDouble("lo") else null
            val src = o.optString("src", id)
            val ttl = o.optInt("ttl", 0)
            val text = textFor(kind, name, lat, lon)

            // 1. tell the person holding this phone
            val title = when (kind) {
                "safe" -> "Nearby person is safe"
                "test" -> "TEST ALERT from a nearby phone"
                else -> "SOS from a nearby phone"
            }
            Notif.incoming(ctx, title, text)
            AppState.lastEvent = "Mesh alert received: $text"

            // 2. act as a gateway if this phone has internet or signal
            gateway(o, src, kind, text)

            // 3. pass it on to other phones
            if (ttl > 0) {
                o.put("ttl", ttl - 1)
                val s = o.toString()
                remember(src, s)
                ensureDiscovery()
                sendToPeers(s, from)
            }
        } catch (e: Exception) {
            // ignore malformed payloads
        }
    }

    private fun gateway(o: JSONObject, src: String, kind: String, text: String) {
        val arr = o.optJSONArray("c") ?: return
        val key = "$src:$kind"
        val now = System.currentTimeMillis()
        val last = gatewayLast[key] ?: 0L
        if (now - last < GATEWAY_COOLDOWN_MS) return
        gatewayLast[key] = now

        val mine = Net.normalize(Prefs.myNumber(ctx))
        val online = Net.isOnline(ctx)
        val suffix = " (relayed by a nearby phone)"
        for (i in 0 until arr.length()) {
            val number = arr.optString(i, "")
            if (number.isBlank()) continue
            if (mine.isNotEmpty() && Net.normalize(number) == mine) continue
            if (online) {
                Net.publish(number, if (kind == "safe") "SAFE" else "SOS ALERT", text + suffix) { ok ->
                    if (!ok) main.post { Net.sendSmsSimple(ctx, number, text + suffix) }
                }
            } else {
                Net.sendSmsSimple(ctx, number, text + suffix)
            }
        }
    }

    private fun textFor(kind: String, name: String, lat: Double?, lon: Double?): String {
        val who = name.ifBlank { "Someone" }
        if (kind == "safe") return "$who is safe now. Please ignore the earlier SOS alerts."
        val prefix = if (kind == "test") "TEST ALERT (not real): " else "SOS! "
        val tail = if (lat == null || lon == null) {
            "Location unavailable."
        } else {
            "Location: https://maps.google.com/?q=" +
                String.format(Locale.US, "%.6f,%.6f", lat, lon)
        }
        return "$prefix$who needs help. $tail"
    }
}
