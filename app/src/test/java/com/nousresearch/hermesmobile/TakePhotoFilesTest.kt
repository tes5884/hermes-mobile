package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.ui.screens.TakePhotoFiles
import org.junit.Assert.*
import org.junit.Test

class TakePhotoFilesTest {
    @Test fun nameIsJpgWithTimestamp() {
        val n = TakePhotoFiles.fileName(0L)
        assertTrue(n.startsWith("photo-") && n.endsWith(".jpg"))
    }
    @Test fun staleOnlyAfterADay() {
        val now = 10 * TakePhotoFiles.MAX_AGE_MS
        assertFalse(TakePhotoFiles.isStale(now - 1000, now))
        assertTrue(TakePhotoFiles.isStale(now - TakePhotoFiles.MAX_AGE_MS - 1, now))
    }
}
