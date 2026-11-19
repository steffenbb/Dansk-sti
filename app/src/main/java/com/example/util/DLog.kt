package com.example.util

import android.util.Log
import com.example.BuildConfig

object DLog {
    fun d(tag: String, msg: String, tr: Throwable? = null): Int {
        if (!BuildConfig.DEBUG) return 0
        val fullMsg = if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg
        return runCatching { Log.println(Log.DEBUG, tag, fullMsg) }.getOrDefault(0)
    }

    fun i(tag: String, msg: String, tr: Throwable? = null): Int {
        if (!BuildConfig.DEBUG) return 0
        val fullMsg = if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg
        return runCatching { Log.println(Log.INFO, tag, fullMsg) }.getOrDefault(0)
    }

    fun v(tag: String, msg: String, tr: Throwable? = null): Int {
        if (!BuildConfig.DEBUG) return 0
        val fullMsg = if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg
        return runCatching { Log.println(Log.VERBOSE, tag, fullMsg) }.getOrDefault(0)
    }
}
