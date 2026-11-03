package com.example.hexcro

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import java.io.File

class JumpSetupActivity : ComponentActivity() {

    private val SUCCESS = 0xFF00C853.toInt()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val screenshotFile = File(cacheDir, "jump_screenshot.png")
        val screenshotBmp = try {
            if (screenshotFile.exists())
                BitmapFactory.decodeFile(screenshotFile.absolutePath)
            else null
        } catch (e: Exception) {
            android.util.Log.e("HexCro", "screenshot reload failed", e)
            null
        }

        val picker = JumpPickerView(this).apply {
            setBackgroundColor(Color.BLACK)
            setScreenshot(screenshotBmp)
            try {
                val icon = BitmapFactory.decodeResource(resources, R.drawable.jump_button)
                setJumpIcon(icon)
            } catch (_: Exception) {}
        }

        val prefs = getSharedPreferences("HexCro", Context.MODE_PRIVATE)
        picker.setInitialPosition(
            prefs.getFloat("jumpTapXRatio", 0.85f),
            prefs.getFloat("jumpTapYRatio", 0.78f)
        )
        picker.setInitialSize(prefs.getFloat("jumpTapSizeRatio", 0.14f))
        picker.setIconOpacity(200)

        val cancelBtn = Button(this).apply {
            text = "Cancel"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = rounded(0xFF444444.toInt(), 12)
            setPadding(dp(24), dp(10), dp(24), dp(10))
            setOnClickListener { finish() }
        }

        val saveBtn = Button(this).apply {
            text = "Save position"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = rounded(SUCCESS, 12)
            setPadding(dp(24), dp(10), dp(24), dp(10))
            setOnClickListener {
                val (x, y) = picker.getCenterRatio()
                val size = picker.getSizeRatio()
                prefs.edit()
                    .putFloat("jumpTapXRatio", x)
                    .putFloat("jumpTapYRatio", y)
                    .putFloat("jumpTapSizeRatio", size)
                    .apply()
                finish()
            }
        }

        val opacityLabel = TextView(this).apply {
            text = "Opacity"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(16), 0, dp(8), 0)
        }

        val opacitySlider = SeekBar(this).apply {
            max = 255
            progress = 200
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    picker.setIconOpacity(progress)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            try {
                progressDrawable?.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
                thumb?.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
            } catch (_: Exception) {}
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(8), dp(10), dp(16), dp(10))
        }

        val dragHandle = TextView(this).apply {
            text = "⋮⋮"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(16), dp(8))
        }
        bar.addView(dragHandle)

        bar.addView(cancelBtn)
        bar.addView(LinearLayout(this@JumpSetupActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(opacityLabel)
            addView(opacitySlider, LinearLayout.LayoutParams(dp(160), LinearLayout.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(12); rightMargin = dp(12)
        })
        bar.addView(saveBtn)

        // Drag the bar with the ⋮⋮ handle, clamped to the screen.
        var dragStartX = 0f
        var dragStartY = 0f
        var barStartTx = 0f
        var barStartTy = 0f
        dragHandle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = e.rawX
                    dragStartY = e.rawY
                    barStartTx = bar.translationX
                    barStartTy = bar.translationY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val parent = bar.parent as? FrameLayout
                    if (parent == null) return@setOnTouchListener true

                    val rootW = parent.width.toFloat()
                    val rootH = parent.height.toFloat()
                    val barW  = bar.width.toFloat()
                    val barH  = bar.height.toFloat()
                    if (rootW <= 0f || rootH <= 0f || barW <= 0f || barH <= 0f) {
                        return@setOnTouchListener true
                    }

                    val newTx = barStartTx + (e.rawX - dragStartX)
                    val newTy = barStartTy + (e.rawY - dragStartY)

                    // Bar's zero-translation position: left=0, top=rootH-barH.
                    // Keep the entire bar inside the screen.
                    val minTx = 0f
                    val maxTx = (rootW - barW).coerceAtLeast(0f)
                    val minTy = -(rootH - barH)
                    val maxTy = 0f

                    bar.translationX = newTx.coerceIn(minTx, maxTx)
                    bar.translationY = newTy.coerceIn(minTy, maxTy)
                    true
                }
                else -> false
            }
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(picker, FrameLayout.LayoutParams(-1, -1))
            addView(bar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ))
        }

        setContentView(root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    )
        }
    }
}