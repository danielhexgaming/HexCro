package com.example.hexcro

import android.util.Log
import com.example.hexcro.IHexCroCallback
import com.example.hexcro.IHexCroService
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class HexCroService : IHexCroService.Stub() {

    // ⚙️ Set from app via setConfig()
    private var webhookUrl: String = ""
    private var privateServer: String = ""

    private val RARE_BIOMES = setOf("CYBERSPACE", "DREAMSPACE", "GLITCHED")

    private val BIOME_STYLES = mapOf(
        "CYBERSPACE"  to Pair(0x0a1a3d, "https://i.imgur.com/IfRiMP1.png"),
        "DREAMSPACE"  to Pair(0xea6cbc, "https://i.imgur.com/ws7roO5.png"),
        "GLITCHED"    to Pair(0x65ff65, "https://i.imgur.com/02oATiP.png"),
        "NORMAL"      to Pair(0xffffff, "https://i.imgur.com/3GuR0ha.png"),
        "WINDY"       to Pair(0xadd8e6, "https://i.imgur.com/Xd0f7Y6.png"),
        "RAINY"       to Pair(0x4385ff, "https://i.imgur.com/9D1ki7x.png"),
        "SNOWY"       to Pair(0xffffff, "https://i.imgur.com/cpuhaxo.png"),
        "SAND STORM"  to Pair(0xc2b180, "https://i.imgur.com/SUR6UZV.png"),
        "STARFALL"    to Pair(0x1b23b6, "https://i.imgur.com/ptML5YJ.png"),
        "CORRUPTION"  to Pair(0x4a2b50, "https://i.imgur.com/9fF5LZt.png"),
        "NULL"        to Pair(0x363636, "https://i.imgur.com/9PTaqMY.png"),
        "HELL"        to Pair(0xff0000, "https://i.imgur.com/vlGcjHR.png"),
        "HEAVEN"      to Pair(0xe8c49e, "https://i.imgur.com/k3yobp6.png"),
        "SINGULARITY" to Pair(0xff6a00, "https://i.imgur.com/rBsb4UL.png")
    )
    private val DEFAULT_STYLE = Pair(0x00FFAA, null as String?)

    private val running = AtomicBoolean(false)
    private var logcatProcess: Process? = null
    private var logcatThread: Thread? = null
    private var callback: IHexCroCallback? = null

    private var lastBiome: String? = null
    private var biomeStart: Long = 0L
    private var startTime: Long = 0L
    private var sessionStart: Long = 0L

    private val biomeRegex = Regex(""""largeImage":\{"hoverText":"([^"]+)"""")

    init {
        Log.i("HexCro", ">>> service constructed <<<")
    }

    override fun setConfig(webhookUrl: String?, privateServer: String?) {
        this.webhookUrl = webhookUrl?.trim() ?: ""
        this.privateServer = privateServer?.trim() ?: ""
        Log.i("HexCro", ">>> config set (webhook=${this.webhookUrl.take(30)}..., server=${this.privateServer.take(30)}...)")
    }

    override fun startLogcat(cb: IHexCroCallback?) {
        Log.i("HexCro", ">>> startLogcat called <<<")
        if (running.get()) return
        callback = cb
        lastBiome = null
        biomeStart = 0L
        startTime = System.currentTimeMillis()
        sessionStart = System.currentTimeMillis()
        startLogcatInternal()
        sendLifecycle("start")
    }

    override fun stopLogcat() {
        Log.i("HexCro", ">>> stopLogcat called <<<")
        sendLifecycle("stop")
        stopLogcatInternal()
    }

    override fun destroy() {
        Log.i("HexCro", ">>> destroy called <<<")
        stopLogcatInternal()
        System.exit(0)
    }

    private fun startLogcatInternal() {
        running.set(true)

        // Phase 1: grab current biome from existing buffer
        Thread {
            try {
                val dump = ProcessBuilder(
                    "/system/bin/sh", "-c",
                    "logcat -d -v brief 'Roblox:I' '*:S' | grep BloxstrapRPC | tail -1"
                ).start()
                val line = dump.inputStream.bufferedReader().readLine()
                if (line != null) {
                    val match = biomeRegex.find(line)
                    if (match != null) {
                        val biome = match.groupValues[1].uppercase()
                        lastBiome = biome
                        biomeStart = System.currentTimeMillis()
                        Log.i("HexCro", ">>> initial biome: $biome")
                        try { callback?.onLogLine("BIOME:$biome") } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.e("HexCro", "dump failed", e)
            }
        }.start()

        // Phase 2: stream new lines
        logcatThread = Thread {
            try {
                val pb = ProcessBuilder(
                    "/system/bin/logcat", "-v", "brief", "-T", "1", "Roblox:I", "*:S"
                )
                pb.redirectErrorStream(true)
                val proc = pb.start()
                logcatProcess = proc
                Log.i("HexCro", ">>> logcat spawned <<<")

                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line: String? = reader.readLine()
                while (running.get() && line != null) {
                    handleLine(line)
                    line = reader.readLine()
                }
            } catch (e: Exception) {
                Log.e("HexCro", "logcat error", e)
                try { callback?.onLogError(e.message ?: "unknown") } catch (_: Exception) {}
            } finally {
                running.set(false)
            }
        }.apply { isDaemon = true }
        logcatThread?.start()
    }

    private fun formatDuration(totalSecs: Int): String {
        val d = totalSecs / 86400
        val h = (totalSecs % 86400) / 3600
        val m = (totalSecs % 3600) / 60
        val s = totalSecs % 60
        return when {
            d > 0 -> "${d}d ${h}h ${m}m ${s}s"
            h > 0 -> "${h}h ${m}m ${s}s"
            else -> "${m}m ${s}s"
        }
    }

    private fun handleLine(line: String) {
        if (!line.contains("BloxstrapRPC")) return

        val match = biomeRegex.find(line) ?: return
        val biome = match.groupValues[1].uppercase()

        if (System.currentTimeMillis() - startTime < 1000) return
        if (biome == lastBiome) return

        val now = System.currentTimeMillis()

        val prevBiome = lastBiome
        val prevStart = biomeStart

        lastBiome = biome
        biomeStart = now

        Log.i("HexCro", ">>> biome changed: $biome")

        // UI callbacks fire in order on this thread
        if (prevBiome != null && prevStart > 0) {
            try { callback?.onLogLine("BIOME_END:$prevBiome") } catch (_: Exception) {}
        }
        try { callback?.onLogLine("BIOME:$biome") } catch (_: Exception) {}

        // Send biome change embeds
        sendBiomeChange(prevBiome, prevStart, biome, now)
    }

    private fun sendBiomeChange(
        prevBiome: String?,
        prevStartMs: Long,
        newBiome: String,
        newStartMs: Long
    ) {
        if (webhookUrl.isEmpty()) {
            Log.w("HexCro", "webhook URL not set, skipping embed")
            return
        }

        Thread {
            try {
                // 1. Send "Ended" for the previous biome (if there was one)
                if (prevBiome != null && prevStartMs > 0) {
                    val endPayload = JSONObject().apply {
                        put("embeds", JSONArray().put(buildEmbed(prevBiome, prevStartMs, newStartMs)))
                    }
                    postJson(endPayload)
                    Log.i("HexCro", ">>> ended embed sent: $prevBiome")
                    Thread.sleep(400) // small gap so Discord orders them
                }

                // 2. Send "Started" for the new biome
                val startPayload = JSONObject().apply {
                    if (newBiome in RARE_BIOMES) put("content", "@everyone")
                    put("embeds", JSONArray().put(buildEmbed(newBiome, newStartMs, null)))
                }
                postJson(startPayload)
                Log.i("HexCro", ">>> started embed sent: $newBiome")
            } catch (e: Exception) {
                Log.e("HexCro", "webhook failed", e)
            }
        }.start()
    }

    private fun buildEmbed(biome: String, startMs: Long, endMs: Long?): JSONObject {
        val style = BIOME_STYLES[biome] ?: DEFAULT_STYLE
        val color = style.first
        val icon = style.second
        val display = biome.lowercase().split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        val startUnix = startMs / 1000L

        val (title, desc) = if (endMs != null) {
            val duration = ((endMs - startMs) / 1000L).toInt()
            val endUnix = endMs / 1000L
            "Biome Ended - $display" to
                    "<t:$startUnix:T> → <t:$endUnix:T>\n**Duration:** ${formatDuration(duration)}"
        } else {
            "Biome Started - $display" to
                    "<t:$startUnix:T> (<t:$startUnix:R>)"
        }

        // Private server link only on "Started" embeds
        val descFull = if (privateServer.isNotEmpty() && endMs == null) {
            "$desc\n**Private Server:** $privateServer"
        } else desc

        return JSONObject().apply {
            put("title", title)
            put("description", descFull)
            put("color", color)
            put("footer", JSONObject().put("text", "HexCro | v1.1.0"))
            // Thumbnail only on "started" embeds
            if (icon != null && endMs == null) {
                put("thumbnail", JSONObject().put("url", icon))
            }
        }
    }

    private fun sendLifecycle(event: String) {
        if (webhookUrl.isEmpty()) return

        val work = {
            try {
                val now = System.currentTimeMillis() / 1000L
                val embed = if (event == "start") {
                    JSONObject().apply {
                        put("title", "🟢 HexCro Started")
                        put("description", "**Started:** <t:$now:T> (<t:$now:R>)")
                        put("color", 0x00FF00)
                        put("footer", JSONObject().put("text", "HexCro | v1.1.0"))
                    }
                } else {
                    val duration = ((System.currentTimeMillis() - sessionStart) / 1000L).toInt()
                    JSONObject().apply {
                        put("title", "🔴 HexCro Stopped")
                        put("description",
                            "**Stopped:** <t:$now:T> (<t:$now:R>)\n**Uptime:** ${formatDuration(duration)}")
                        put("color", 0xFF0000)
                        put("footer", JSONObject().put("text", "HexCro | v1.1.0"))
                    }
                }

                val payload = JSONObject().apply { put("embeds", JSONArray().put(embed)) }
                postJson(payload)
            } catch (e: Exception) {
                Log.e("HexCro", "lifecycle failed", e)
            }
        }

        val webhookThread = Thread {
            work()
        }

        webhookThread.start()

// Wait for the stop webhook before allowing Shizuku to shut down.
        if (event == "stop") {
            try {
                webhookThread.join(12000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.e("HexCro", "Interrupted while waiting for stop webhook", e)
            }
        }
    }

    private fun postJson(payload: JSONObject) {
        val conn = URL(webhookUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", "HexCro")
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        val code = conn.responseCode
        Log.i("HexCro", ">>> http $code")
        conn.disconnect()
    }

    private fun stopLogcatInternal() {
        if (!running.getAndSet(false)) return
        logcatProcess?.destroy()
        logcatProcess = null
        logcatThread?.interrupt()
        logcatThread = null
    }
}