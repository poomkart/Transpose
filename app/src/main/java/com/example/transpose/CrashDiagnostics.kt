package com.example.transpose

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashDiagnostics {
    private const val PREFS = "karaoke_crash_diagnostics"
    private const val KEY_LAST_CRASH = "last_crash"
    private const val KEY_LAST_STAGE = "last_stage"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val writer = StringWriter()
                throwable.printStackTrace(PrintWriter(writer))
                val timestamp = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss Z",
                    Locale.US,
                ).format(Date())

                val report = buildString {
                    appendLine("Transpose Karaoke crash report")
                    appendLine("Time: $timestamp")
                    appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Thread: ${thread.name}")
                    appendLine("Last stage: ${lastStage(context) ?: "unknown"}")
                    appendLine()
                    append(writer.toString())
                }

                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_CRASH, report)
                    .commit()
            } catch (_: Throwable) {
                // Never allow the diagnostic logger itself to hide the original crash.
            }

            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
    }

    fun markStage(context: Context, stage: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_STAGE, stage)
            .commit()
    }

    fun lastStage(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_STAGE, null)

    fun lastCrash(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_CRASH, null)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_CRASH)
            .apply()
    }
}
