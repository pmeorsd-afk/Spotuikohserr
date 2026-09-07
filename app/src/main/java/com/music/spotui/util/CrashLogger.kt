package com.music.spotui.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {
    private const val TAG = "CrashLogger"
    private const val CRASH_FILE = "crash_log.txt"
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recordCrash(context, thread, throwable)
            } catch (e: Exception) {
                Log.e(TAG, "Error writing crash log", e)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
        log(TAG, "CrashLogger initialized")
    }

    private fun recordCrash(context: Context, thread: Thread, throwable: Throwable) {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTrace = sw.toString()

        val logEntry = buildString {
            appendLine("==========================================")
            appendLine("CRASH DETECTED AT: $dateStr")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name} (ID: ${thread.id})")
            appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message}")
            appendLine("Stack Trace:")
            appendLine(stackTrace)
            appendLine("==========================================")
            appendLine()
        }

        Log.e(TAG, logEntry)

        try {
            val file = File(context.filesDir, CRASH_FILE)
            val fw = FileWriter(file, true)
            fw.write(logEntry)
            fw.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash log to file", e)
        }
    }

    fun log(tag: String, message: String, throwable: Throwable? = null) {
        val dateStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logLine = if (throwable != null) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            "[$dateStr] [$tag] $message\n$sw"
        } else {
            "[$dateStr] [$tag] $message"
        }
        Log.d(tag, message, throwable)
    }

    fun getCrashLogs(context: Context): String {
        return try {
            val file = File(context.filesDir, CRASH_FILE)
            if (file.exists()) file.readText() else "אין קריסות מתועדות - האפליקציה פועלת בצורה תקינה."
        } catch (e: Exception) {
            "שגיאה בקריאת קובץ הלוג: ${e.message}"
        }
    }

    fun clearLogs(context: Context) {
        try {
            val file = File(context.filesDir, CRASH_FILE)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear crash logs", e)
        }
    }

    fun hasCrashes(context: Context): Boolean {
        return try {
            val file = File(context.filesDir, CRASH_FILE)
            file.exists() && file.length() > 0
        } catch (e: Exception) {
            false
        }
    }
}
