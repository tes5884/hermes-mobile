package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.push.CronWrapper
import org.junit.Assert.assertEquals
import org.junit.Test

class CronWrapperTest {
    @Test fun stripsHeaderLine() {
        val body = "Cronjob Response: Morning brief (job_id: abc123def)\n\nYour brief is ready."
        assertEquals("Your brief is ready.", CronWrapper.strip(body))
    }
    @Test fun leavesOtherBodies() {
        assertEquals("Hello\nworld", CronWrapper.strip("Hello\nworld"))
        assertEquals("Cronjob Response: no id", CronWrapper.strip("Cronjob Response: no id"))
    }
    @Test fun titleBecomesBotName() {
        assertEquals("Ezra", CronWrapper.title("Hermes reminder", "ezra"))
        assertEquals("Hermes reminder", CronWrapper.title("Hermes reminder", ""))
        assertEquals("Custom", CronWrapper.title("Custom", "ezra"))
    }
}
