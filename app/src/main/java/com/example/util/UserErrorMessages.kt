package com.example.util

import com.example.data.remote.NoInternetException
import com.example.data.remote.OverpassUnavailableException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

fun userMessageFor(e: Throwable): String {
    val cause = e.cause
    return when {
        e is NoInternetException || cause is NoInternetException ->
            AppLocaleHelper.strOrNull(null, com.example.R.string.user_error_no_internet)
                ?: "Ingen internetforbindelse."
        e is UnknownHostException || cause is UnknownHostException ||
            e is ConnectException || cause is ConnectException ->
            AppLocaleHelper.strOrNull(null, com.example.R.string.user_error_no_internet)
                ?: "Ingen internetforbindelse."
        e is OverpassUnavailableException || cause is OverpassUnavailableException ||
            e is SocketTimeoutException || cause is SocketTimeoutException ->
            AppLocaleHelper.strOrNull(null, com.example.R.string.user_error_server_unavailable)
                ?: "Serveren svarer ikke lige nu. Prøv igen om lidt."
        e is FileNotFoundException || cause is FileNotFoundException ||
            e is SecurityException || cause is SecurityException ->
            AppLocaleHelper.strOrNull(null, com.example.R.string.user_error_file_open)
                ?: "Filen kunne ikke åbnes."
        e is IOException && (
            e.message?.contains("file", ignoreCase = true) == true ||
                e.message?.contains("stream", ignoreCase = true) == true ||
                e.message?.contains("uri", ignoreCase = true) == true ||
                e.message?.contains("gpx", ignoreCase = true) == true
            ) ->
            AppLocaleHelper.strOrNull(null, com.example.R.string.user_error_file_open)
                ?: "Filen kunne ikke åbnes."
        else ->
            AppLocaleHelper.strOrNull(null, com.example.R.string.user_error_generic)
                ?: "Noget gik galt. Prøv igen."
    }
}
