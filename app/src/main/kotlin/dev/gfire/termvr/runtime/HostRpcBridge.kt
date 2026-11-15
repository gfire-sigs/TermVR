package dev.gfire.termvr.runtime

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import dev.gfire.termvr.R
import dev.gfire.termvr.core.model.ClipboardReadPolicy
import dev.gfire.termvr.core.runtime.HostRpcDelegate
import dev.gfire.termvr.core.runtime.MuxCodec
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Represents a pending guest request to read the Android host clipboard.
 */
data class ClipboardReadPrompt(
    val requestId: String,
    val profileId: String,
)

/**
 * Bridges guest `HOST_RPC` calls to Android Custom Tabs, ClipboardManager, and FileProvider.
 */
class HostRpcBridge(
    private val context: Context,
    private val clipboardPolicyProvider: (String) -> ClipboardReadPolicy,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _clipboardPrompt = MutableStateFlow<ClipboardReadPrompt?>(null)
    val clipboardPrompt: StateFlow<ClipboardReadPrompt?> = _clipboardPrompt.asStateFlow()

    @Volatile
    private var pendingConfirmation: CompletableFuture<Boolean>? = null

    fun createDelegateForProfile(profileId: String): HostRpcDelegate = object : HostRpcDelegate {
        override fun openUrl(url: String) {
            openExternalUrl(url)
        }

        override fun writeClipboard(text: String) {
            writeHostClipboard(text)
        }

        override fun readClipboard(): String? {
            if (clipboardPolicyProvider(profileId) == ClipboardReadPolicy.DISABLED) {
                return null
            }
            return readHostClipboard()
        }

        override fun confirmClipboardRead(): Boolean {
            return when (clipboardPolicyProvider(profileId)) {
                ClipboardReadPolicy.DISABLED -> false
                ClipboardReadPolicy.SESSION_ALLOWED -> true
                ClipboardReadPolicy.ASK_EACH_TIME -> requestClipboardConfirmation(profileId)
            }
        }

        override fun shareFile(sharedFile: File, displayName: String) {
            shareStagedFile(sharedFile, displayName)
        }
    }

    /**
     * Resolves an active `ASK_EACH_TIME` clipboard modal prompt from the UI.
     */
    fun resolveClipboardPrompt(allow: Boolean) {
        pendingConfirmation?.complete(allow)
        pendingConfirmation = null
        _clipboardPrompt.value = null
    }

    /**
     * Opens an external `http` or `https` URI using standard Custom Tabs extras.
     */
    fun openExternalUrl(rawUrl: String) {
        val uri = runCatching { Uri.parse(rawUrl.trim()) }.getOrNull() ?: return
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val customTabsExtras = Bundle().apply {
                putBinder(EXTRA_CUSTOM_TABS_SESSION, null)
            }
            putExtras(customTabsExtras)
            putExtra(EXTRA_CUSTOM_TABS_SHARE_MENU_ITEM, true)
        }
        try {
            appContext.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            val fallback = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { appContext.startActivity(fallback) }
        }
    }

    /**
     * Copies text up to 1 MiB into the Android clipboard on the main looper.
     */
    fun writeHostClipboard(text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MuxCodec.MAX_CLIPBOARD_BYTES) {
            "Clipboard payload exceeds ${MuxCodec.MAX_CLIPBOARD_BYTES} bytes"
        }
        runOnMainBlocking {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("TermVR", text))
        }
    }

    /**
     * Reads current plain text from the Android clipboard on the main looper.
     */
    fun readHostClipboard(): String? {
        return runOnMainBlocking {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return@runOnMainBlocking null
            if (clip.itemCount <= 0) return@runOnMainBlocking null
            clip.getItemAt(0).coerceToText(appContext)?.toString()
        }
    }

    /**
     * Shares a file through Android's `FileProvider` `ACTION_SEND` chooser.
     */
    fun shareStagedFile(sourceFile: File, displayName: String) {
        if (!sourceFile.isFile) return
        val safeName = sanitizeShareFileName(displayName)
        val sharesDir = File(appContext.cacheDir, "shares").apply { mkdirs() }
        val stagedFile = File(sharesDir, safeName)
        sourceFile.inputStream().use { input ->
            stagedFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        launchShareChooser(stagedFile, safeName)
    }

    /**
     * Writes [bytes] into `<cacheDir>/shares/<fileName>` and opens Android's share sheet.
     */
    fun shareBytesAsFile(fileName: String, bytes: ByteArray) {
        val safeName = sanitizeShareFileName(fileName)
        val sharesDir = File(appContext.cacheDir, "shares").apply { mkdirs() }
        val stagedFile = File(sharesDir, safeName)
        stagedFile.writeBytes(bytes)
        launchShareChooser(stagedFile, safeName)
    }

    /**
     * Opens Android's plain-text share sheet for terminal selection text.
     */
    fun sharePlainText(text: String) {
        if (text.isEmpty()) return
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(
            sendIntent,
            appContext.getString(R.string.share_file_chooser_title),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(chooser) }
    }

    private fun launchShareChooser(stagedFile: File, displayName: String) {
        val uri = FileProvider.getUriForFile(appContext, FILE_PROVIDER_AUTHORITY, stagedFile)
        val mime = when {
            displayName.endsWith(".yaml", ignoreCase = true) ||
                displayName.endsWith(".yml", ignoreCase = true) -> "text/yaml"
            displayName.endsWith(".json", ignoreCase = true) -> "application/json"
            displayName.endsWith(".txt", ignoreCase = true) -> "text/plain"
            else -> "application/octet-stream"
        }
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(
            sendIntent,
            appContext.getString(R.string.share_file_chooser_title),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(chooser) }
    }

    private fun requestClipboardConfirmation(profileId: String): Boolean {
        pendingConfirmation?.complete(false)
        val future = CompletableFuture<Boolean>()
        pendingConfirmation = future
        _clipboardPrompt.value = ClipboardReadPrompt(
            requestId = UUID.randomUUID().toString(),
            profileId = profileId,
        )
        return try {
            future.get(60L, TimeUnit.SECONDS)
        } catch (_: Exception) {
            false
        } finally {
            if (pendingConfirmation === future) {
                pendingConfirmation = null
                _clipboardPrompt.value = null
            }
        }
    }

    private fun <T> runOnMainBlocking(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        val future = CompletableFuture<T>()
        mainHandler.post {
            try {
                future.complete(block())
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return future.get(5L, TimeUnit.SECONDS)
    }

    private fun sanitizeShareFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "shared-file.bin" else cleaned
    }

    companion object {
        const val FILE_PROVIDER_AUTHORITY = "dev.gfire.termvr.fileprovider"
        private const val EXTRA_CUSTOM_TABS_SESSION = "android.support.customtabs.extra.SESSION"
        private const val EXTRA_CUSTOM_TABS_SHARE_MENU_ITEM = "android.support.customtabs.extra.SHARE_MENU_ITEM"
    }
}
