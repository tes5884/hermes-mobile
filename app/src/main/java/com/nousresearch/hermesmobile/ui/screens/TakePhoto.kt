package com.nousresearch.hermesmobile.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.nousresearch.hermesmobile.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** File naming and cleanup for camera captures; kept free of Compose so it is unit-testable. */
object TakePhotoFiles {
    const val DIR = "photos"
    const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    fun fileName(now: Long): String =
        "photo-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now)) + ".jpg"

    fun newFile(context: Context, now: Long = System.currentTimeMillis()): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        return File(dir, fileName(now))
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".files", file)

    /** Old captures only; a fresh capture and anything under a day old stays. */
    fun isStale(lastModified: Long, now: Long): Boolean = now - lastModified > MAX_AGE_MS

    fun clean(context: Context, now: Long = System.currentTimeMillis()) {
        File(context.cacheDir, DIR).listFiles()?.filter { isStale(it.lastModified(), now) }?.forEach { it.delete() }
    }
}

/**
 * Returns a function that opens the camera: asks for CAMERA on first use, captures with TakePicture into a
 * FileProvider uri, shows a preview with "Use photo"/"Retake", and hands the confirmed uri to [onPhoto]
 * (the same callback a picked image goes through).
 */
@Composable
fun rememberTakePhoto(onPhoto: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var previewPath by rememberSaveable { mutableStateOf<String?>(null) }
    var denied by remember { mutableStateOf(false) }

    val capture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pendingPath
        pendingPath = null
        if (ok && path != null && File(path).length() > 0) previewPath = path else path?.let { File(it).delete() }
    }
    fun launchCamera() {
        val file = TakePhotoFiles.newFile(context)
        pendingPath = file.absolutePath
        runCatching { capture.launch(TakePhotoFiles.uriFor(context, file)) }.onFailure { pendingPath = null; denied = true }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else denied = true
    }

    previewPath?.let { path ->
        val bitmap by produceState<Bitmap?>(null, path) { value = withContext(Dispatchers.IO) { decodeScaled(path, 1600) } }
        AlertDialog(
            onDismissRequest = { File(path).delete(); previewPath = null },
            text = {
                Box(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
                }
            },
            confirmButton = {
                TextButton(onClick = { previewPath = null; onPhoto(TakePhotoFiles.uriFor(context, File(path))) }) {
                    Text(stringResource(R.string.photo_use))
                }
            },
            dismissButton = {
                TextButton(onClick = { File(path).delete(); previewPath = null; launchCamera() }) {
                    Text(stringResource(R.string.photo_retake))
                }
            }
        )
    }
    if (denied) {
        AlertDialog(
            onDismissRequest = { denied = false },
            text = { Text(stringResource(R.string.photo_camera_denied)) },
            confirmButton = { TextButton(onClick = { denied = false }) { Text(stringResource(android.R.string.ok)) } }
        )
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }
}

private fun decodeScaled(path: String, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
    val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val rot = runCatching {
        when (android.media.ExifInterface(path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
            6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
        }
    }.getOrDefault(0f)
    if (rot == 0f) return bmp
    val m = android.graphics.Matrix().apply { postRotate(rot) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}
