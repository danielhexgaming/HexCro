package com.example.hexcro

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import com.example.hexcro.TapInjector
import com.example.hexcro.HexCroForegroundService
import com.example.hexcro.JumpSetupActivity
import org.json.JSONObject
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity(), HexCroForegroundService.Listener {

    private val BG_COLOR = 0xFFF5F5F8.toInt()
    private val CARD_COLOR = 0xFFFFFFFF.toInt()
    private val DRAWER_COLOR = 0xFF16162A.toInt()
    private val DRAWER_ACTIVE = 0xFF6C5CE7.toInt()
    private val ACCENT = 0xFF6C5CE7.toInt()
    private val ACCENT_SOFT = 0xFFEDE7FF.toInt()
    private val TEXT_COLOR = 0xFF1E1E2E.toInt()
    private val MUTED_COLOR = 0xFF8A8A9A.toInt()
    private val SUCCESS_COLOR = 0xFF00C853.toInt()
    private val SUCCESS_SOFT = 0xFFE6F7EB.toInt()
    private val START_COLOR = 0xFF6C5CE7.toInt()
    private val STOP_COLOR = 0xFFEF5350.toInt()
    private val DISABLED_COLOR = 0xFFD0D0D8.toInt()

    private lateinit var topBarTitle: TextView
    private lateinit var drawer: LinearLayout
    private lateinit var dimOverlay: View
    private lateinit var mainScreen: LinearLayout
    private lateinit var configScreen: LinearLayout
    private lateinit var antiAfkScreen: LinearLayout
    private lateinit var biomesScreen: LinearLayout
    private lateinit var logsScreen: LinearLayout
    private lateinit var aboutScreen: LinearLayout
    private val menuItems = mutableListOf<TextView>()
    private var currentScreen = 0
    private var drawerWidth = 0

    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var setupBanner: LinearLayout
    private lateinit var setupBannerText: TextView
    private lateinit var setupList: LinearLayout
    private lateinit var setupCard: LinearLayout

    private lateinit var webhookInput: EditText
    private lateinit var serverInput: EditText

    // Anti-AFK
    private lateinit var antiAfkToggleBtn: Button
    private lateinit var antiAfkIntervalSlider: SeekBar
    private lateinit var antiAfkIntervalValue: TextView
    private lateinit var antiAfkStatus: TextView
    private var antiAfkEnabled = false

    private lateinit var streamStatus: TextView
    private lateinit var logView: TextView
    private lateinit var eventView: TextView
    private lateinit var logScroll: ScrollView

    private lateinit var biomesContainer: LinearLayout

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }

            val maxDim = 1920
            var sampleSize = 1
            while (opts.outWidth / sampleSize > maxDim ||
                opts.outHeight / sampleSize > maxDim) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            }

            if (bmp == null) {
                event("couldn't decode screenshot")
                return@registerForActivityResult
            }

            val file = java.io.File(cacheDir, "jump_screenshot.png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()

            startActivity(Intent(this@MainActivity, JumpSetupActivity::class.java))
        } catch (e: Exception) {
            event("screenshot load failed: ${e.message}")
            Log.e("HexCro", "screenshot load failed", e)
        }
    }

    private var fgService: HexCroForegroundService? = null
    private var fgBound = false
    private var isRunning = false
    private val logLines = ArrayDeque<String>()
    private val biomeCounts = mutableMapOf<String, Int>()

    private val prefs by lazy { getSharedPreferences("HexCro", Context.MODE_PRIVATE) }

    private val TRACKED_BIOMES = listOf(
        "CYBERSPACE", "DREAMSPACE", "GLITCHED",
        "WINDY", "RAINY", "SNOWY", "SAND STORM",
        "STARFALL", "CORRUPTION", "NULL", "HELL",
        "HEAVEN", "SINGULARITY"
    )

    private val BIOME_COLORS = mapOf(
        "CYBERSPACE"  to 0xFF00FFFF.toInt(),
        "DREAMSPACE"  to 0xFF9B59B6.toInt(),
        "GLITCHED"    to 0xFFFF00FF.toInt(),
        "WINDY"       to 0xFF95A5A6.toInt(),
        "RAINY"       to 0xFF4385FF.toInt(),
        "SNOWY"       to 0xFFE0F7FF.toInt(),
        "SAND STORM"  to 0xFFC2B180.toInt(),
        "STARFALL"    to 0xFF1B23B6.toInt(),
        "CORRUPTION"  to 0xFF4A2B50.toInt(),
        "NULL"        to 0xFF363636.toInt(),
        "HELL"        to 0xFFFF0000.toInt(),
        "HEAVEN"      to 0xFFE8C49E.toInt(),
        "SINGULARITY" to 0xFFFF6A00.toInt()
    )

    private val RARE_BIOMES = setOf("CYBERSPACE", "DREAMSPACE", "GLITCHED")

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD_COLOR, 16)
        setPadding(dp(20), dp(20), dp(20), dp(20))
        elevation = dp(2).toFloat()
    }

    private fun attachPressAnimation(view: View) {
        view.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    }
                }
                return false // don't consume — the click listener still fires
            }
        })
    }

    private fun styledButton(text: String): Button = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 15f
        isAllCaps = false
        setPadding(dp(20), dp(14), dp(20), dp(14))
        attachPressAnimation(this)
    }

    private fun label(text: String, size: Float = 12f, color: Int = MUTED_COLOR): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
        }

    private fun pretty(biome: String): String =
        biome.lowercase().split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    private fun event(msg: String) {
        Log.i("HexCro", msg)
        runOnUiThread {
            if (!::eventView.isInitialized) return@runOnUiThread
            if (eventView.text.length > 4000) eventView.text = ""
            val ts = (System.currentTimeMillis() % 100000).toString().padStart(5, '0')
            eventView.append("[$ts] $msg\n")
        }
    }

    private fun showAccessibilityRequiredDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Accessibility service required")
            .setMessage(
                "Anti-AFK needs HexCro's Accessibility service to send taps.\n\n" +
                        "Enable it in Settings → Accessibility → HexCro, then come back and turn Anti-AFK on."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: Exception) {
                    event("couldn't open accessibility settings")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Anti-AFK UI refresh ───
    private fun updateAntiAfkUi() {
        runOnUiThread {
            if (!::antiAfkToggleBtn.isInitialized) return@runOnUiThread

            val a11yOk = TapInjector.canTap()

            // If anti-AFK is on but accessibility is off, force it off
            if (antiAfkEnabled && !a11yOk) {
                antiAfkEnabled = false
                prefs.edit().putBoolean("antiAfkEnabled", false).apply()
                fgService?.setAntiAfkEnabled(false)
                event("Anti-AFK auto-disabled — accessibility lost")
            }

            antiAfkToggleBtn.text = if (antiAfkEnabled) "Anti-AFK: ON" else "Anti-AFK: OFF"
            antiAfkToggleBtn.background = rounded(
                if (antiAfkEnabled) SUCCESS_COLOR else DISABLED_COLOR, 14
            )

            val interval = prefs.getInt("antiAfkInterval", 10)
            if (::antiAfkIntervalSlider.isInitialized) {
                antiAfkIntervalSlider.progress = (interval - 1).coerceIn(0, 14)
                antiAfkIntervalValue.text = "$interval min"
            }

            val armed = fgService?.isAntiAfkArmed() ?: false
            antiAfkStatus.text = when {
                !a11yOk -> "Accessibility service not connected"
                !antiAfkEnabled -> "Disabled"
                armed -> "Armed — next tap in ~$interval min"
                else -> "Enabled — waiting for biome"
            }
            antiAfkStatus.setTextColor(
                when {
                    !a11yOk -> STOP_COLOR
                    armed -> SUCCESS_COLOR
                    else -> MUTED_COLOR
                }
            )
        }
    }

    // ─── FG service connection ───
    private val fgConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as HexCroForegroundService.LocalBinder
            fgService = binder.getService()
            fgBound = true
            fgService?.setListener(this@MainActivity)

            val recent = fgService?.getRecentLines() ?: emptyList()
            runOnUiThread {
                logLines.clear()
                logLines.addAll(recent)
                while (logLines.size > 500) logLines.removeFirst()
                logView.text = logLines.joinToString("\n")
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
                isRunning = fgService?.isRunning() ?: false
                updateButtonState()
                updateAntiAfkUi()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            fgBound = false
            fgService = null
            isRunning = false
            updateButtonState()
            updateAntiAfkUi()
        }
    }

    override fun onLogLine(line: String) {
        runOnUiThread {
            val ts = (System.currentTimeMillis() % 100000).toString().padStart(5, '0')
            when {
                line.startsWith("BIOME:") -> {
                    val biome = line.removePrefix("BIOME:")
                    logLines.addLast("[$ts] 🌍 $biome started")
                    recordBiome(biome)
                }
                line.startsWith("BIOME_END:") -> {
                    val biome = line.removePrefix("BIOME_END:")
                    logLines.addLast("[$ts] 💤 $biome ended")
                }
                else -> logLines.addLast(line)
            }
            while (logLines.size > 500) logLines.removeFirst()
            logView.text = logLines.joinToString("\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }

            if (line == "ANTI_AFK_ARMED" || line.startsWith("ANTI_AFK_TAP") ||
                line.startsWith("ANTI_AFK_ENABLED") || line.startsWith("ANTI_AFK_INTERVAL") ||
                line == "ANTI_AFK_BLOCKED no_accessibility"
            ) {
                if (line == "ANTI_AFK_BLOCKED no_accessibility") {
                    event("Anti-AFK blocked — accessibility not connected")
                }
                updateAntiAfkUi()
            }
        }
    }

    override fun onLogError(error: String) {
        event("logcat error: $error")
    }

    override fun onStateChanged(running: Boolean) {
        runOnUiThread {
            isRunning = running
            updateButtonState()
            updateAntiAfkUi()
        }
    }

    // ─── Battery optimization ───
    private fun isBatteryOptimizationDisabled(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryOptimizationDisabled() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                event("battery settings unavailable")
            }
        }
    }

    private fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun openNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (e2: Exception) {
                event("notification settings unavailable")
            }
        }
    }

    // ─── Setup ───
    private data class SetupItem(
        val name: String,
        val done: Boolean,
        val actionText: String?,
        val action: (() -> Unit)?
    )

    private fun getSetupItems(): List<SetupItem> {
        val shizukuRunning = Shizuku.pingBinder()
        val shizukuGranted = shizukuRunning && Shizuku.checkSelfPermission() == 0
        val batteryOk = isBatteryOptimizationDisabled()
        val notificationsOk = notificationsEnabled()
        val webhookSet = prefs.getString("webhook", "").orEmpty().trim().isNotEmpty()

        return listOf(
            SetupItem("Shizuku running", shizukuRunning,
                if (!shizukuRunning) "Open Shizuku" else null,
                if (!shizukuRunning) {
                    {
                        try {
                            val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                            if (i != null) startActivity(i) else event("Shizuku not installed")
                        } catch (e: Exception) { event("couldn't open Shizuku") }
                    }
                } else null
            ),
            SetupItem("Shizuku permission granted", shizukuGranted,
                if (shizukuRunning && !shizukuGranted) "Grant" else null,
                if (shizukuRunning && !shizukuGranted) { { Shizuku.requestPermission(100) } } else null
            ),
            SetupItem("Battery optimization disabled", batteryOk,
                if (!batteryOk) "Disable" else null,
                if (!batteryOk) { { requestBatteryOptimizationDisabled() } } else null
            ),
            SetupItem("Notifications enabled", notificationsOk,
                if (!notificationsOk) "Enable" else null,
                if (!notificationsOk) { { openNotificationSettings() } } else null
            ),
            SetupItem("Webhook URL configured", webhookSet,
                if (!webhookSet) "Configure" else null,
                if (!webhookSet) { { showScreen(1) } } else null
            )
        )
    }

    private fun rebuildSetup() {
        runOnUiThread {
            if (!::setupList.isInitialized) return@runOnUiThread
            val items = getSetupItems()
            val allDone = items.all { it.done }

            if (allDone) {
                setupCard.visibility = View.GONE
                setupBanner.visibility = View.VISIBLE
                setupBanner.background = rounded(SUCCESS_SOFT, 16)
                setupBannerText.text = "Everything is set up"
                setupBannerText.setTextColor(SUCCESS_COLOR)
                return@runOnUiThread
            }

            setupCard.visibility = View.VISIBLE
            setupBanner.visibility = View.GONE
            setupList.removeAllViews()

            items.forEach { item ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, dp(10))
                }
                val dot = View(this).apply {
                    background = rounded(if (item.done) SUCCESS_COLOR else 0xFFD0D0D8.toInt(), 999)
                }
                row.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(12) })

                val name = TextView(this).apply {
                    text = item.name
                    textSize = 13f
                    setTextColor(if (item.done) MUTED_COLOR else TEXT_COLOR)
                    if (!item.done) setTypeface(null, Typeface.BOLD)
                }
                row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                if (item.actionText != null && item.action != null) {
                    val btn = styledButton(item.actionText).apply {
                        textSize = 12f
                        background = rounded(ACCENT_SOFT, 10)
                        setTextColor(ACCENT)
                        setPadding(dp(14), dp(8), dp(14), dp(8))
                        setOnClickListener { item.action.invoke() }
                    }
                    row.addView(btn)
                }
                setupList.addView(row)
            }
        }
    }

    // ─── Biome counts ───
    private fun loadBiomeCounts() {
        biomeCounts.clear()
        TRACKED_BIOMES.forEach { biomeCounts[it] = 0 }
        try {
            val obj = JSONObject(prefs.getString("biomeCounts", "{}") ?: "{}")
            obj.keys().forEach { key ->
                if (key in TRACKED_BIOMES) biomeCounts[key] = obj.getInt(key)
            }
        } catch (_: Exception) {}
    }

    private fun saveBiomeCounts() {
        val obj = JSONObject()
        biomeCounts.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString("biomeCounts", obj.toString()).apply()
    }

    private fun recordBiome(biome: String) {
        if (biome == "NORMAL" || biome !in TRACKED_BIOMES) return
        biomeCounts[biome] = (biomeCounts[biome] ?: 0) + 1
        saveBiomeCounts()
        rebuildBiomesScreen()
    }

    private fun rebuildBiomesScreen() {
        runOnUiThread {
            if (!::biomesContainer.isInitialized) return@runOnUiThread
            biomesContainer.removeAllViews()
            TRACKED_BIOMES.forEach { biome ->
                val count = biomeCounts[biome] ?: 0
                val isRare = biome in RARE_BIOMES
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(12), 0, dp(12))
                }
                val dot = View(this).apply {
                    background = rounded(BIOME_COLORS[biome] ?: ACCENT, 999)
                }
                row.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(12) })
                val name = TextView(this).apply {
                    text = pretty(biome) + if (isRare) "  ⭐" else ""
                    textSize = 14f
                    setTextColor(TEXT_COLOR)
                    if (isRare) setTypeface(null, Typeface.BOLD)
                }
                row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                val countText = TextView(this).apply {
                    text = "×$count"
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(if (count > 0) TEXT_COLOR else MUTED_COLOR)
                }
                row.addView(countText)
                biomesContainer.addView(row)
                val divider = View(this).apply { setBackgroundColor(0xFFEEEEF2.toInt()) }
                biomesContainer.addView(divider, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                ))
            }
        }
    }

    // ─── Navigation ───
    private fun showScreen(index: Int) {
        currentScreen = index
        mainScreen.visibility = if (index == 0) View.VISIBLE else View.GONE
        configScreen.visibility = if (index == 1) View.VISIBLE else View.GONE
        antiAfkScreen.visibility = if (index == 2) View.VISIBLE else View.GONE
        biomesScreen.visibility = if (index == 3) View.VISIBLE else View.GONE
        logsScreen.visibility = if (index == 4) View.VISIBLE else View.GONE
        aboutScreen.visibility = if (index == 5) View.VISIBLE else View.GONE
        topBarTitle.text = when (index) {
            0 -> "Home"
            1 -> "Config"
            2 -> "Anti-AFK"
            3 -> "Biomes"
            4 -> "Developer Logs"
            5 -> "About"
            else -> "HexCro"
        }
        menuItems.forEachIndexed { i, item ->
            if (i == index) {
                item.background = rounded(DRAWER_ACTIVE, 10)
                item.setTypeface(null, Typeface.BOLD)
            } else {
                item.background = null
                item.setTypeface(null, Typeface.NORMAL)
            }
        }
        if (index == 0) rebuildSetup()
        if (index == 2) updateAntiAfkUi()
        if (index == 3) rebuildBiomesScreen()
    }

    private fun openDrawer() {
        dimOverlay.visibility = View.VISIBLE
        drawer.animate().translationX(0f).setDuration(220).start()
    }

    private fun closeDrawer() {
        dimOverlay.visibility = View.GONE
        drawer.animate().translationX(-drawerWidth.toFloat()).setDuration(220).start()
    }

    private fun updateButtonState() {
        runOnUiThread {
            if (isRunning) {
                startBtn.isEnabled = false
                startBtn.background = rounded(DISABLED_COLOR, 14)
                startBtn.setTextColor(0xFFA0A0AA.toInt())
                stopBtn.isEnabled = true
                stopBtn.background = rounded(STOP_COLOR, 14)
                stopBtn.setTextColor(Color.WHITE)
                if (::streamStatus.isInitialized) {
                    streamStatus.text = "🟢 Logcat streaming"
                    streamStatus.setTextColor(0xFF00A040.toInt())
                }
            } else {
                startBtn.isEnabled = true
                startBtn.background = rounded(START_COLOR, 14)
                startBtn.setTextColor(Color.WHITE)
                stopBtn.isEnabled = false
                stopBtn.background = rounded(DISABLED_COLOR, 14)
                stopBtn.setTextColor(0xFFA0A0AA.toInt())
                if (::streamStatus.isInitialized) {
                    streamStatus.text = "🔴 Logcat stopped"
                    streamStatus.setTextColor(STOP_COLOR)
                }
            }
        }
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { code, _ ->
            if (code == 100) {
                rebuildSetup()
            }
        }

    override fun onResume() {
        super.onResume()
        if (::setupList.isInitialized) setupList.post { rebuildSetup() }
        updateAntiAfkUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drawerWidth = dp(280)
        loadBiomeCounts()
        Shizuku.addRequestPermissionResultListener(permissionListener)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
            }
        }

        // ─── Top bar ───
        topBarTitle = TextView(this).apply {
            text = "Home"; textSize = 20f
            setTypeface(null, Typeface.BOLD); setTextColor(TEXT_COLOR)
        }
        val menuBtn = TextView(this).apply {
            text = "☰"; textSize = 24f; setTextColor(TEXT_COLOR)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            setOnClickListener { openDrawer() }
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(12))
            setBackgroundColor(BG_COLOR)
            addView(topBarTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(menuBtn)
        }

        // ─── MAIN ───
        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF6C5CE7.toInt(), 0xFF8E7CF0.toInt())
            ).apply { cornerRadius = dp(20).toFloat() }
            setPadding(dp(24), dp(28), dp(24), dp(28))
            elevation = dp(4).toFloat()
            addView(TextView(this@MainActivity).apply {
                text = "HexCro"; textSize = 36f
                setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Real-time biome detection\nfor Sol's RNG"; textSize = 13f
                setTextColor(0xFFE0D9FF.toInt())
                setPadding(0, dp(6), 0, 0)
                setLineSpacing(dp(4).toFloat(), 1f)
            })
        }

        setupBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = rounded(SUCCESS_SOFT, 16)
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        setupBannerText = TextView(this).apply {
            text = "Everything is set up"; textSize = 15f
            setTypeface(null, Typeface.BOLD); setTextColor(SUCCESS_COLOR)
        }
        setupBanner.addView(setupBannerText)

        setupList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setupCard = card().apply {
            addView(TextView(this@MainActivity).apply {
                text = "REQUIRED SETUP"; textSize = 11f
                setTextColor(MUTED_COLOR); setPadding(0, 0, 0, dp(8))
            })
            addView(setupList)
        }

        startBtn = styledButton("Start HexCro")
        stopBtn = styledButton("Stop HexCro")

        val version = TextView(this).apply {
            text = "v1.1.0"; textSize = 11f
            gravity = Gravity.CENTER; setTextColor(MUTED_COLOR)
        }

        mainScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(BG_COLOR)
            addView(heroCard)
            addView(setupBanner, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) })
            addView(setupCard, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) })
            addView(startBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) })
            addView(stopBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) })
            addView(version, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(24) })
        }

        // ─── CONFIG ───
        webhookInput = EditText(this).apply {
            hint = "https://discord.com/api/webhooks/..."
            setSingleLine(false); maxLines = 3; textSize = 12f
            background = rounded(0xFFF0F0F5.toInt(), 10)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setTextColor(TEXT_COLOR); setHintTextColor(MUTED_COLOR)
        }
        serverInput = EditText(this).apply {
            hint = "https://www.roblox.com/share?code=..."
            setSingleLine(false); maxLines = 2; textSize = 12f
            background = rounded(0xFFF0F0F5.toInt(), 10)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setTextColor(TEXT_COLOR); setHintTextColor(MUTED_COLOR)
        }
        val saveBtn = styledButton("Save Config").apply { background = rounded(ACCENT, 14) }

        val configCard = card().apply {
            addView(label("WEBHOOK URL", 11f, MUTED_COLOR))
            addView(webhookInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6); bottomMargin = dp(16) })
            addView(label("PRIVATE SERVER LINK (OPTIONAL)", 11f, MUTED_COLOR))
            addView(serverInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
        }

        configScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(BG_COLOR)
            addView(configCard)
            addView(saveBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) })
        }

        // ─── ANTI-AFK (separate screen) ───
        antiAfkEnabled = prefs.getBoolean("antiAfkEnabled", false)
        val antiAfkInterval = prefs.getInt("antiAfkInterval", 10)

        antiAfkToggleBtn = styledButton(
            if (antiAfkEnabled) "Anti-AFK: ON" else "Anti-AFK: OFF"
        ).apply {
            background = rounded(if (antiAfkEnabled) SUCCESS_COLOR else DISABLED_COLOR, 14)
            setOnClickListener {
                if (!antiAfkEnabled && !TapInjector.canTap()) {
                    event("Anti-AFK blocked — accessibility service not connected")
                    showAccessibilityRequiredDialog()
                    return@setOnClickListener
                }
                antiAfkEnabled = !antiAfkEnabled
                prefs.edit().putBoolean("antiAfkEnabled", antiAfkEnabled).apply()
                fgService?.setAntiAfkEnabled(antiAfkEnabled)
                event("anti-AFK ${if (antiAfkEnabled) "enabled" else "disabled"}")
                updateAntiAfkUi()
            }
        }

        antiAfkIntervalValue = TextView(this).apply {
            text = "$antiAfkInterval min"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(TEXT_COLOR)
            gravity = Gravity.END
            minWidth = dp(60)
        }

        antiAfkIntervalSlider = SeekBar(this).apply {
            max = 14          // 0..14 → 1..15
            progress = (antiAfkInterval - 1).coerceIn(0, 14)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val mins = value + 1
                    antiAfkIntervalValue.text = "$mins min"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val mins = sb?.progress?.plus(1) ?: 10
                    prefs.edit().putInt("antiAfkInterval", mins).apply()
                    fgService?.setAntiAfkInterval(mins)
                    event("anti-AFK interval set to $mins min")
                    updateAntiAfkUi()
                }
            })
            try {
                progressDrawable?.setColorFilter(ACCENT, PorterDuff.Mode.SRC_IN)
                thumb?.setColorFilter(ACCENT, PorterDuff.Mode.SRC_IN)
            } catch (_: Exception) {}
        }

        antiAfkStatus = TextView(this).apply {
            text = "Disabled"
            textSize = 13f
            setTextColor(MUTED_COLOR)
            setPadding(0, dp(8), 0, 0)
        }

        val antiAfkTapSetupBtn = styledButton("Set jump button location").apply {
            background = rounded(ACCENT, 14)
            setOnClickListener {
                pickImageLauncher.launch("image/*")
            }
        }

        val antiAfkStatusCard = card().apply {
            addView(label("STATUS", 11f, MUTED_COLOR))
            addView(antiAfkStatus, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
        }

        val antiAfkControlCard = card().apply {
            addView(label("ANTI-AFK", 11f, MUTED_COLOR))
            addView(antiAfkToggleBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })

            val intervalHeader = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(16), 0, 0)
                addView(label("INTERVAL (MINUTES)", 11f, MUTED_COLOR),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(antiAfkIntervalValue)
            }
            addView(intervalHeader)
            addView(antiAfkIntervalSlider, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
        }

        val antiAfkTapCard = card().apply {
            addView(label("JUMP BUTTON LOCATION", 11f, MUTED_COLOR))
            addView(TextView(this@MainActivity).apply {
                text = "Pick a screenshot with the Roblox jump button visible, then drag the marker onto it."
                textSize = 12f
                setTextColor(MUTED_COLOR)
                setPadding(0, dp(6), 0, 0)
                setLineSpacing(dp(3).toFloat(), 1f)
            })
            addView(antiAfkTapSetupBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
        }

        antiAfkScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(BG_COLOR)
            addView(antiAfkStatusCard)
            addView(antiAfkControlCard, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) })
            addView(antiAfkTapCard, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) })
        }

        // ─── BIOMES ───
        biomesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val biomesCard = card().apply {
            addView(biomesContainer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        val biomesScroll = ScrollView(this).apply { addView(biomesCard) }
        biomesScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(BG_COLOR)
            addView(biomesScroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }

        // ─── LOGS ───
        streamStatus = TextView(this).apply {
            text = "🔴 Logcat stopped"; textSize = 13f
            setTextColor(STOP_COLOR); setPadding(0, 0, 0, dp(10))
        }
        eventView = TextView(this).apply {
            textSize = 10f; typeface = Typeface.MONOSPACE
            setTextColor(MUTED_COLOR)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(0xFFF0F0F5.toInt(), 10)
            maxLines = 8
        }
        logView = TextView(this).apply {
            textSize = 10f; typeface = Typeface.MONOSPACE
            setTextIsSelectable(true); setTextColor(TEXT_COLOR)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        logScroll = ScrollView(this).apply {
            addView(logView)
            background = rounded(CARD_COLOR, 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            elevation = dp(2).toFloat()
        }
        val clearBtn = styledButton("Clear").apply { background = rounded(0xFF78909C.toInt(), 14) }
        val copyBtn = styledButton("Copy Logs").apply { background = rounded(ACCENT, 14) }
        val logBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(clearBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            addView(copyBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        }
        logsScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(BG_COLOR)
            addView(streamStatus)
            addView(eventView)
            addView(logBtns, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12); bottomMargin = dp(12) })
            addView(logScroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }

        // ─── ABOUT ───
        val aboutCard = card().apply {
            addView(TextView(this@MainActivity).apply {
                text = "HexCro"; textSize = 32f
                setTypeface(null, Typeface.BOLD); setTextColor(TEXT_COLOR)
            })
            addView(TextView(this@MainActivity).apply {
                text = "v1.1.0"; textSize = 12f
                setTextColor(MUTED_COLOR); setPadding(0, 0, 0, dp(12))
            })
            addView(TextView(this@MainActivity).apply {
                text = "A mobile biome detector for Sol's RNG.\nListens to Roblox logs via Shizuku and sends Discord alerts when biomes change."
                textSize = 12f; setTextColor(TEXT_COLOR)
                setLineSpacing(dp(4).toFloat(), 1f)
            })
        }
        val creditsCard = card().apply {
            addView(label("CREDITS", 11f, MUTED_COLOR))
            addView(TextView(this@MainActivity).apply {
                text = "Built by DanielHexGaming"; textSize = 13f
                setTextColor(TEXT_COLOR); setPadding(0, dp(6), 0, dp(4))
            })
            addView(TextView(this@MainActivity).apply {
                text = "Powered by Shizuku"; textSize = 12f; setTextColor(MUTED_COLOR)
            })
        }
        val featuresCard = card().apply {
            addView(label("FEATURES", 11f, MUTED_COLOR))
            listOf(
                "🌍 Real-time biome detection",
                "🔔 Discord webhook alerts",
                "⭐ Rare biome pings",
                "🚶 Anti-AFK",
            ).forEach { f ->
                addView(TextView(this@MainActivity).apply {
                    text = f; textSize = 13f; setTextColor(TEXT_COLOR)
                    setPadding(0, dp(8), 0, 0)
                })
            }
        }
        aboutScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(BG_COLOR)
            addView(aboutCard)
            addView(creditsCard, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
            addView(featuresCard, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
        }

        // ─── Container ───
        val screenContainer = FrameLayout(this).apply {
            setBackgroundColor(BG_COLOR)
            addView(mainScreen, FrameLayout.LayoutParams(-1, -1))
            addView(configScreen, FrameLayout.LayoutParams(-1, -1))
            addView(antiAfkScreen, FrameLayout.LayoutParams(-1, -1))
            addView(biomesScreen, FrameLayout.LayoutParams(-1, -1))
            addView(logsScreen, FrameLayout.LayoutParams(-1, -1))
            addView(aboutScreen, FrameLayout.LayoutParams(-1, -1))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_COLOR)
            addView(topBar)
            addView(screenContainer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }

        // ─── Drawer ───
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(DRAWER_COLOR)
            setPadding(dp(12), dp(40), dp(12), dp(24))
        }
        drawer.addView(TextView(this).apply {
            text = "HexCro"; textSize = 22f
            setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
            setPadding(dp(20), dp(8), dp(20), dp(4))
        })
        drawer.addView(TextView(this).apply {
            text = "v1.1.0"; textSize = 11f
            setTextColor(0xFF8888AA.toInt())
            setPadding(dp(20), 0, dp(20), dp(28))
        })
        addMenuItem("Home", 0)
        addMenuItem("Config", 1)
        addMenuItem("Anti-AFK", 2)
        addMenuItem("Biomes", 3)
        addMenuItem("Developer Logs", 4)
        addMenuItem("About", 5)

        dimOverlay = View(this).apply {
            setBackgroundColor(0x99000000.toInt())
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(BG_COLOR)
            addView(content, FrameLayout.LayoutParams(-1, -1))
            addView(dimOverlay, FrameLayout.LayoutParams(-1, -1))
            addView(drawer, FrameLayout.LayoutParams(drawerWidth, -1, Gravity.START))
        }
        setContentView(root)
        drawer.post { drawer.translationX = -drawerWidth.toFloat() }

        webhookInput.setText(prefs.getString("webhook", "") ?: "")
        serverInput.setText(prefs.getString("server", "") ?: "")

        showScreen(0)
        updateButtonState()
        updateAntiAfkUi()
        rebuildSetup()
        rebuildBiomesScreen()

        bindService(
            Intent(this, HexCroForegroundService::class.java),
            fgConn,
            Context.BIND_AUTO_CREATE
        )

        startBtn.setOnClickListener {
            if (isRunning) return@setOnClickListener
            event("Start tapped")

            prefs.edit()
                .putString("webhook", webhookInput.text.toString().trim())
                .putString("server", serverInput.text.toString().trim())
                .apply()

            if (Shizuku.checkSelfPermission() != 0) {
                Shizuku.requestPermission(100)
                return@setOnClickListener
            }

            val intent = Intent(this, HexCroForegroundService::class.java).apply {
                action = HexCroForegroundService.ACTION_START
            }
            startForegroundService(intent)
        }

        stopBtn.setOnClickListener {
            if (!isRunning) return@setOnClickListener
            event("Stop tapped")
            val intent = Intent(this, HexCroForegroundService::class.java).apply {
                action = HexCroForegroundService.ACTION_STOP
            }
            startService(intent)
        }

        saveBtn.setOnClickListener {
            prefs.edit()
                .putString("webhook", webhookInput.text.toString().trim())
                .putString("server", serverInput.text.toString().trim())
                .apply()
            event("config saved")
            rebuildSetup()
        }

        clearBtn.setOnClickListener {
            logLines.clear()
            logView.text = ""
            eventView.text = ""
            fgService?.clearLines()
            event("cleared")
        }

        copyBtn.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("HexCro Logs", logView.text))
            event("copied ${logView.text.length} chars")
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        if (fgBound) {
            fgService?.setListener(null)
            unbindService(fgConn)
            fgBound = false
        }
        super.onDestroy()
    }

    private fun addMenuItem(label: String, index: Int) {
        val item = TextView(this).apply {
            text = label; textSize = 15f; setTextColor(Color.WHITE)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            isClickable = true
            attachPressAnimation(this)
            setOnClickListener {
                showScreen(index)
                closeDrawer()
            }
        }
        drawer.addView(item, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) })
        menuItems.add(item)
    }
}