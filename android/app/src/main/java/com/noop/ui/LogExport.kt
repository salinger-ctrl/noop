package com.noop.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import com.noop.BuildConfig
import java.io.File

/**
 * Shares the strap connection log as a plain-text file so users can attach it to a bug report.
 *
 * Android's `Log.d` output isn't reachable without adb, which is why people on issues #17/#18
 * couldn't share what was happening on their strap. [com.noop.ble.WhoopBleClient] now keeps an
 * in-memory ring buffer (`exportLogText()`); this writes it to a cache file and fires a share sheet.
 */
object LogExport {

    fun shareStrapLog(context: Context, logText: String) {
        runCatching {
            val header = buildString {
                appendLine("NOOP strap log")
                appendLine("App:     ${BuildConfig.VERSION_NAME} (${BuildConfig.TIER})")
                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("Device:  ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("─".repeat(40))
            }
            val body = logText.ifBlank { "(strap log is empty — connect to your strap, reproduce the issue, then share again)" }

            val dir = File(context.cacheDir, "logs").apply { mkdirs() }
            val file = File(dir, "noop-strap-log.txt")
            file.writeText(header + "\n" + body)

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "NOOP strap log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share strap log"))
        }.onFailure {
            Toast.makeText(context, "Couldn't share the log: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }
}
