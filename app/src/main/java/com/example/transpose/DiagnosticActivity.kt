package com.example.transpose

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class DiagnosticActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashDiagnostics.markStage(this, "safe_boot_visible")
        setContentView(buildScreen())
    }

    override fun onResume() {
        super.onResume()
        // If MainActivity crashes, Android returns here. Rebuild the screen so the
        // last persisted stage / Java crash report is immediately visible.
        setContentView(buildScreen())
    }

    private fun buildScreen(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val report = CrashDiagnostics.lastCrash(this)
        val lastStage = CrashDiagnostics.lastStage(this) ?: "none yet"

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(32), dp(20), dp(32))
            setBackgroundColor(Color.rgb(8, 11, 18))
        }

        container.addView(TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 27f
            gravity = Gravity.CENTER
            text = "🎤 Transpose Karaoke\nSafe Boot"
        })

        container.addView(TextView(this).apply {
            setTextColor(Color.rgb(117, 230, 164))
            textSize = 16f
            gravity = Gravity.CENTER
            text = "Safe launcher is running"
            setPadding(0, dp(10), 0, dp(18))
        })

        container.addView(TextView(this).apply {
            setTextColor(Color.rgb(190, 198, 215))
            textSize = 14f
            text = "Last stage before this screen:\n$lastStage"
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(19, 25, 39))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        if (report != null) {
            container.addView(TextView(this).apply {
                setTextColor(Color.rgb(255, 185, 185))
                textSize = 13f
                text = "Java/Kotlin crash captured:"
                setPadding(0, dp(18), 0, dp(8))
            })

            container.addView(TextView(this).apply {
                setTextColor(Color.rgb(225, 229, 238))
                setBackgroundColor(Color.rgb(19, 25, 39))
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                text = report
                setTextIsSelectable(true)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))

            container.addView(Button(this).apply {
                text = "Copy Crash Report"
                setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Transpose Karaoke crash", report))
                    text = "Copied ✓"
                }
            })
        }

        container.addView(Button(this).apply {
            text = "▶ Open Transpose Core"
            setOnClickListener {
                CrashDiagnostics.clear(this@DiagnosticActivity)
                CrashDiagnostics.markStage(this@DiagnosticActivity, "launching_main")
                startActivity(Intent(this@DiagnosticActivity, MainActivity::class.java))
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(22) })

        container.addView(TextView(this).apply {
            setTextColor(Color.rgb(150, 158, 176))
            textSize = 13f
            gravity = Gravity.CENTER
            text = "ถ้ากด Open Transpose Core แล้วเด้งกลับมาหน้านี้\nส่งรูปหน้าจอนี้มาให้ผม โดยเฉพาะ Last stage"
            setPadding(0, dp(12), 0, 0)
        })

        return ScrollView(this).apply {
            setBackgroundColor(Color.rgb(8, 11, 18))
            addView(container)
        }
    }
}
