package com.example.hexcro

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import com.example.hexcro.HexCroAccessibilityService

object TapInjector {

    private const val TAG = "HexCro"

    /** Returns (width, height) in pixels of the *current* display. */
    fun screenSize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = context.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val component = ComponentName(context, HexCroAccessibilityService::class.java)
        val full  = component.flattenToString()
        val short = component.flattenToShortString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any {
            it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true)
        }
    }

    fun canTap(): Boolean = HexCroAccessibilityService.isConnected()

    /** Blocking tap. Never call from the main thread. */
    fun tap(context: Context, x: Int, y: Int): Boolean {
        return HexCroAccessibilityService.tap(x, y)
    }

    /** Convenience: tap using saved ratios. BLOCKING. */
    fun tapSaved(context: Context): Boolean {
        val prefs = context.getSharedPreferences("HexCro", Context.MODE_PRIVATE)
        val (w, h) = screenSize(context)
        // Ratios were saved in landscape. Force landscape so the tap lands
        // in the right physical spot even if the display is currently portrait.
        val (rw, rh) = if (w > h) w to h else h to w
        val x = (rw * prefs.getFloat("jumpTapXRatio", 0.85f)).toInt()
        val y = (rh * prefs.getFloat("jumpTapYRatio", 0.78f)).toInt()
        return tap(context, x, y)
    }

    /** Fire-and-forget tap. Callback runs on main thread. Safe to call from UI. */
    fun tapAsync(context: Context, x: Int, y: Int, onResult: ((Boolean) -> Unit)? = null) {
        Thread {
            val ok = try {
                tap(context, x, y)
            } catch (e: Exception) {
                Log.e(TAG, "tapAsync failed", e)
                false
            }
            if (onResult != null) {
                Handler(Looper.getMainLooper()).post { onResult(ok) }
            }
        }.start()
    }

    /** Fire-and-forget tap using saved ratios. */
    fun tapSavedAsync(context: Context, onResult: ((Boolean) -> Unit)? = null) {
        Thread {
            val ok = try {
                tapSaved(context)
            } catch (e: Exception) {
                Log.e(TAG, "tapSavedAsync failed", e)
                false
            }
            if (onResult != null) {
                Handler(Looper.getMainLooper()).post { onResult(ok) }
            }
        }.start()
    }
}