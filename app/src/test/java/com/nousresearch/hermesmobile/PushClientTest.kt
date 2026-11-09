package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.push.PushClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PushClientTest {
    @Test
    fun suggestsTheApiServerOnTheChatHost() {
        assertEquals("http://192.168.1.10:8642", PushClient.suggestedBase("http://192.168.1.10:9119"))
        assertEquals("https://hermes.example.com:8642", PushClient.suggestedBase("https://hermes.example.com"))
        assertEquals("", PushClient.suggestedBase("not a url"))
    }

    @Test
    fun plainHttpOnlyReachesPrivateHosts() {
        assertEquals("http://192.168.1.10:8642/p/default/api/platforms/hermes_android/events",
            PushClient.endpoint("http://192.168.1.10:8642", "default"))
        assertEquals("https://hermes.example.com/p/work/api/platforms/hermes_android/events",
            PushClient.endpoint("https://hermes.example.com", "work"))
        assertThrows(IllegalArgumentException::class.java) { PushClient.endpoint("http://hermes.example.com:8642", "default") }
        assertThrows(IllegalArgumentException::class.java) { PushClient.endpoint("http://user:pw@192.168.1.2:8642", "default") }
    }

    @Test
    fun fetchRetriesThenSucceedsOrGivesUp() {
        var n = 0
        val ok = com.nousresearch.hermesmobile.push.PushDelivery.fetchWithRetry(3, 0) { if (++n < 3) error("cold vpn") else "event" }
        assertEquals("event", ok)
        assertEquals(3, n)
        n = 0
        val none = com.nousresearch.hermesmobile.push.PushDelivery.fetchWithRetry<String>(3, 0) { n++; error("down") }
        assertEquals(null, none)
        assertEquals(3, n)
    }
}
