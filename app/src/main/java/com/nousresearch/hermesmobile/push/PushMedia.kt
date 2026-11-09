package com.nousresearch.hermesmobile.push

/**
 * A file sent to the phone rides a notice as `MEDIA:<path>` lines (the server's push has no attachments).
 * The notice text hides them; the inbox shows each as a card that downloads the file and opens it.
 */
object PushMedia {
    private val LINE = Regex("(?im)^[ \\t]*MEDIA:[ \\t]*(\\S.*?)[ \\t]*$")

    /** Absolute paths named by `MEDIA:` lines, in order, without repeats. */
    fun paths(body: String): List<String> =
        LINE.findAll(body).map { it.groupValues[1].trim('`', '"', '\'') }.filter { it.startsWith("/") || it.startsWith("~/") }.distinct().toList()

    /** The notice text without its `MEDIA:` lines. */
    fun text(body: String): String = LINE.replace(body, "").replace(Regex("\\n{3,}"), "\n\n").trim()

    fun name(path: String): String = path.substringAfterLast('/').ifBlank { path }
}

/** Downloads a pushed file into the app cache and hands it to the right viewer (the package installer for an APK). */
object PushFileOpener {
    const val APK_MIME = "application/vnd.android.package-archive"

    /** The server's type, unless it is generic and the name says better (an APK must reach the installer). */
    fun mimeFor(name: String, served: String): String {
        if (name.endsWith(".apk", ignoreCase = true)) return APK_MIME
        if (served.isNotBlank() && served != "application/octet-stream") return served
        val ext = name.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /** Opens [file]. Returns a message for the user when the phone must be set up first, else null. */
    fun open(context: android.content.Context, file: java.io.File, mime: String, name: String): String? {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val type = mimeFor(name, mime)
        val isApk = type == APK_MIME
        if (isApk && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:${context.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Allow Hermes to install apps, then tap the file again."
        }
        val view = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, type)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        view.clipData = android.content.ClipData.newRawUri(name, uri)
        // The installer and most viewers answer a plain VIEW; a chooser would hide the installer behind "no app".
        return try { context.startActivity(view); null }
        catch (_: android.content.ActivityNotFoundException) { "No app on this phone can open $name ($type)." }
    }

    /** Download with progress (0..1, or -1 when the size is unknown), then open. Returns an error text or null. */
    suspend fun fetchAndOpen(
        context: android.content.Context, path: String,
        download: suspend (path: String, output: java.io.File, onProgress: (Long, Long) -> Unit) -> String,
        onProgress: (Float) -> Unit = {}
    ): String? {
        val name = PushMedia.name(path)
        val file = com.nousresearch.hermesmobile.features.MobileFiles.target(context, path)
        return try {
            val mime = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                download(path, file) { got, total -> onProgress(if (total > 0) got.toFloat() / total else -1f) }
            }
            open(context, file, mime, name)
        } catch (e: kotlinx.coroutines.CancellationException) { file.delete(); throw e }
        catch (e: Exception) { file.delete(); "Couldn't download $name: ${e.message ?: e.javaClass.simpleName}" }
    }
}
