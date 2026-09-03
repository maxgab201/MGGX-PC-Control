package com.mggx.pccontrol.next.home

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant

data class HomeServiceFailure(
    val stage: String,
    val exceptionType: String,
    val message: String,
    val stackTrace: String,
)

/** App-private, bounded and sanitized service diagnostics. Credentials are never written. */
object HomeServiceFailureLog {
    private const val TAG = "MGGXHomeService"
    private const val FILE_NAME = "home-service-failures.log"
    private const val MAX_BYTES = 64 * 1024
    private val authorization = Regex("(?i)authorization\\s*[:=]\\s*(?:bearer\\s+)?[^\\s,}]+")
    private val sensitive = Regex("(?i)(bearer|(?:pairing|agent|relay)?token|(?:pairing)?secret)(\\s*[:=]\\s*|\\s+)[^\\s,}]+")

    fun record(context: Context, stage: String, error: Throwable): HomeServiceFailure {
        val failure = HomeServiceFailure(
            stage = stage,
            exceptionType = error.javaClass.name,
            message = sanitize(error.message ?: "Sin mensaje"),
            stackTrace = sanitize(error.stackTraceToString()),
        )
        val text = buildString {
            append(Instant.now()).append(" stage=").append(failure.stage)
            append(" exception=").append(failure.exceptionType)
            append(" message=").append(failure.message).append('\n')
            append(failure.stackTrace).append("\n---\n")
        }
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists() && file.length() > MAX_BYTES) file.writeText("")
            file.appendText(text)
        }
        Log.e(TAG, "stage=${failure.stage} exception=${failure.exceptionType}: ${failure.message}")
        return failure
    }

    fun latest(context: Context): String? = runCatching {
        File(context.filesDir, FILE_NAME).takeIf(File::isFile)?.readText()?.takeLast(MAX_BYTES)
    }.getOrNull()

    internal fun sanitize(value: String): String = value
        .replace(authorization, "Authorization=<redacted>")
        .replace(sensitive) { match -> "${match.groupValues[1]}=<redacted>" }
}
