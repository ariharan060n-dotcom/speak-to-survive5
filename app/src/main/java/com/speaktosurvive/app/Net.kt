package com.speaktosurvive.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Internet delivery ("through the app").
 * Each phone number maps to a private-looking topic on the free ntfy.sh service.
 * The sender posts to the contact's topic; the contact's app listens on its own topic.
 */
object Net {
    private val exec: ExecutorService = Executors.newSingleThreadExecutor()

    fun isOnline(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val n = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(n) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            false
        }
    }

    /** Last 10 digits, so +91 98765 43210 and 9876543210 match. */
    fun normalize(number: String): String = number.filter { it.isDigit() }.takeLast(10)

    fun topicFor(number: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val h = md.digest(("speaktosurvive-v1:" + normalize(number)).toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in h) sb.append(String.format("%02x", b.toInt() and 0xff))
        return "sts" + sb.toString().take(24)
    }

    /** Posts a message to the contact's topic. The callback runs on a background thread. */
    fun publish(number: String, title: String, message: String, cb: (Boolean) -> Unit) {
        exec.execute {
            var ok = false
            var c: HttpURLConnection? = null
            try {
                c = URL("https://ntfy.sh/").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject()
                    .put("topic", topicFor(number))
                    .put("title", title)
                    .put("message", message)
                    .put("priority", 5)
                    .put("tags", JSONArray().put("rotating_light"))
                    .toString()
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                ok = c.responseCode in 200..299
            } catch (e: Exception) {
                ok = false
            } finally {
                try {
                    c?.disconnect()
                } catch (e: Exception) {
                    // ignore
                }
            }
            cb(ok)
        }
    }

    /** Fire-and-forget SMS used when this phone relays someone else's alert. */
    @Suppress("DEPRECATION")
    fun sendSmsSimple(ctx: Context, number: String, text: String): Boolean {
        if (!Perms.granted(ctx, android.Manifest.permission.SEND_SMS)) return false
        return try {
            val sm = if (android.os.Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
            sm.sendMultipartTextMessage(number, null, sm.divideMessage(text), null, null)
            true
        } catch (e: Exception) {
            false
        }
    }
}
