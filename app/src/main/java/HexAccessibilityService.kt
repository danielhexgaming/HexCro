package com.example.hexcro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class HexCroAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "a11y connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not used */ }

    override fun onInterrupt() { /* not used */ }

    override fun onDestroy() {
        if (instance === this) instance = null
        Log.i(TAG, "a11y disconnected")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HexCro"

        @Volatile
        private var instance: HexCroAccessibilityService? = null

        fun isConnected(): Boolean = instance != null

        /** Queue a tap at absolute screen pixels. Returns true if queued. */
        fun tap(x: Int, y: Int): Boolean {
            val svc = instance ?: return false

            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
                lineTo(x.toFloat() + 1f, y.toFloat() + 1f)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 100L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val queued = svc.dispatchGesture(gesture, null, null)
            Log.i(TAG, "a11y tap: $x,$y queued=$queued")
            return queued
        }
    }
}