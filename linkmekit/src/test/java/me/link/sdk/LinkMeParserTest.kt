package me.link.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkMeParserTest {
    @Test
    fun parsesEscapedStringsAndNestedMaps() {
        val payload = LinkMe.shared.parsePayload(
            """{"cid":"abc12345","linkId":"link_1","path":"/hello/\"world","params":{"message":"a,b"},"duplicate":true,"forceRedirectWeb":true,"webFallbackUrl":"https://example.test/?q=a%20b"}"""
        )

        assertEquals("abc12345", payload?.cid)
        assertEquals("link_1", payload?.linkId)
        assertEquals("/hello/\"world", payload?.path)
        assertEquals("a,b", payload?.params?.get("message"))
        assertEquals(true, payload?.duplicate)
        assertEquals(true, payload?.forceRedirectWeb)
        assertEquals("https://example.test/?q=a%20b", payload?.webFallbackUrl)
    }

    @Test
    fun rejectsMalformedOrNonObjectJson() {
        assertNull(LinkMe.shared.parsePayload("not-json"))
        assertNull(LinkMe.shared.parsePayload("[]"))
        assertNull(LinkMe.shared.parsePayload("null"))
        assertNull(LinkMe.shared.parsePayload("{}"))
        assertNull(LinkMe.shared.parsePayload("{\"unknown\":true}"))

        val wrongTypes = LinkMe.shared.parsePayload("""{"path":42,"forceRedirectWeb":"true","params":{"count":3}}""")
        assertNull(wrongTypes?.path)
        assertNull(wrongTypes?.forceRedirectWeb)
        assertNull(wrongTypes?.params)
    }
}
