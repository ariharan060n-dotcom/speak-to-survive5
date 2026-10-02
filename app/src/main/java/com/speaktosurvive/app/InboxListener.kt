package com.speaktosurvive.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Listens for SOS messages sent to this phone's own number through the app.
 * Runs while protection is switched on.
 */
class InboxListener(private val ctx: Context) {

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var stopped = false
    @Volatile private var conn: HttpURLConnection? = null
    private var thread: Thread? = null

    fun start() {
        val mine = Prefs.myNumber(ctx)
        if (Net.normalize(mine).length < 6) {
            AppState.inboxStatus = "Add your own number in Settings"
            return
        }
        stopped = false
        AppState.inboxStatus = "Waiting for alerts"
        val topic = Net.topicFor(mine)
        val t = Thread({ loop(topic) }, "sts-inbox")
        t.isDaemon = true
        t.start()
        thread = t
    }

    fun stop() {
        stopped = true
        try {
            conn?.disconnect()
        } catch (e: Exception) {
            // ignore
        }
        thread?.interrupt()
        thread = null
    }

    private fun loop(topic: String) {
        var backoff = 3_000L
        while (!stopped) {
            try {
                val lastId = Prefs.inboxLastId(ctx)
                val since = if (lastId.isNotEmpty()) lastId else (System.currentTimeMillis() / 1000L).toString()
                val c = URL("https://ntfy.sh/$topic/json?since=$since").openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 150_000
                conn = c
                BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                    backoff = 3_000L
                    while (!stopped) {
                        val line = r.readLine() ?: break
                        handle(line)
                    }
                }
            } catch (e: Exception) {
                // offline or timed out: retry below
            } finally {
                try {
                    conn?.disconnect()
                } catch (e: Exception) {
                    // ignore
                }
            }
            if (stopped) break
            try {
                Thread.sleep(backoff)
            } catch (e: InterruptedException) {
                break
            }
            backoff = minOf(backoff * 2, 60_000L)
        }
    }

    private fun handle(line: String) {
        try {
            val o = JSONObject(line)
            if (o.optString("event") != "message") return
            val id = o.optString("id")
            if (id.isNotEmpty()) Prefs.setInboxLastId(ctx, id)
            val title = o.optString("title", "SOS ALERT")
            val msg = o.optString("message", "")
            if (msg.isBlank()) return
            main.post {
                AppState.lastEvent = "Alert received: $msg"
                Notif.incoming(ctx, title, msg)
            }
        } catch (e: Exception) {
            // ignore malformed lines
        }
    }
}
