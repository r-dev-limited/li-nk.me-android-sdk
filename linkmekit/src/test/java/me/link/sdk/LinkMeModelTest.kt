package me.link.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkMeModelTest {
    @Test
    fun payloadDefaultsAreStable() {
        val payload = LinkPayload(cid = "abc12345", path = "/welcome", params = mapOf("ref" to "a"), duplicate = true)
        assertEquals("abc12345", payload.cid)
        assertEquals("/welcome", payload.path)
        assertEquals("a", payload.params?.get("ref"))
        assertEquals(true, payload.duplicate)
        assertNull(payload.linkId)
        assertNull(payload.forceRedirectWeb)
    }

    @Test
    fun payloadEqualitySupportsDeterministicAssertions() {
        val first = LinkPayload(linkId = "link_1", isLinkMe = true)
        val second = LinkPayload(linkId = "link_1", isLinkMe = true)
        assertEquals(first, second)
    }

    @Test
    fun sharedV1GoldenFixtureParsesEscapedUnicodeAndMaps() {
        val fixture = javaClass.getResourceAsStream("/contract-v1-link-payload.json")!!.bufferedReader().use { it.readText() }
        val payload = LinkMe.shared.parsePayload(fixture)
        assertEquals("cid-golden-001", payload?.cid)
        assertEquals("link-golden-001", payload?.linkId)
        assertEquals("/welcome/春", payload?.path)
        assertEquals("He said \"go\"", payload?.params?.get("quote"))
        assertEquals("line\nfeed", payload?.custom?.get("control"))
        assertEquals(false, payload?.duplicate)
    }

    @Test
    fun claimPolicyMetadataIsPreserved() {
        val fixture = javaClass.getResourceAsStream("/link-payload.claim-policy-v2.json")!!.bufferedReader().use { it.readText() }
        val payload = LinkMe.shared.parsePayload(fixture)
        assertEquals("direct_cid", payload?.claimMethod)
        assertEquals("high", payload?.matchConfidence)
        assertEquals("deterministic", payload?.attributionStatus)
        assertEquals(2, payload?.claimPolicyVersion)
        assertEquals(1, payload?.contractVersion)
        assertEquals("e325270c-35a9-4380-a423-c338c4a1bd1f", payload?.claimId)
    }
}
