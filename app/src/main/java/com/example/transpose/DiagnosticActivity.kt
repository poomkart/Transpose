package com.example.transpose

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class DiagnosticActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val report = CrashDiagnostics.lastCrash(this)
        if (report == null) {
            setContentView(statusView())
            Handler(Looper.getMainLooper()).postDelayed({
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }, 350L)
            return
        }

        setContentView(crashView(report))
    }

    private fun statusView(): TextView = TextView(this).apply {
        setBackgroundColor(Color.rgb(8, 11, 18))
        setTextColor(Color.WHITE)
        textSize = 18f
        gravity = Gravity.CENTER
        text = "Transpose Karaoke\nStarting…"
        setPadding(32, 32, 32, 32)
    }

    private fun crashView(report: String): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(24))
            setBackgroundColor(Color.rgb(8, 11, 18))
        }

        val title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 24f
            text = "Karaoke Crash Diagnostic"
        }
        container.addView(title)

        val hint = TextView(this).apply {
            setTextColor(Color.rgb(190, 198, 215))
            textSize = 15f
            text = "แอปจับสาเหตุที่เด้งได้แล้ว กด Copy Error แล้วส่งข้อความนี้ให้ ChatGPT"
            setPadding(0, dp(8), 0, dp(14))
        }
        container.addView(hint)

        val reportView = TextView(this).apply {
            setTextColor(Color.rgb(225, 229, 238))
            setBackgroundColor(Color.rgb(19, 25, 39))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            text = report
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        container.addView(
            reportView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val copyButton = Button(this).apply {
            text = "Copy Error"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Transpose Karaoke crash", report))
                text = "Copied ✓"
            }
        }
        container.addView(copyButton)

        val retryButton = Button(this).apply {
            text = "Clear Error & Try Again"
            setOnClickListener {
                CrashDiagnostics.clear(this@DiagnosticActivity)
                startActivity(Intent(this@DiagnosticActivity, MainActivity::class.java))
                finish()
            }
        }
        container.addView(retryButton)

        return ScrollView(this).apply {
            setBackgroundColor(Color.rgb(8, 11, 18))
            addView(container)
        }
    }
}
