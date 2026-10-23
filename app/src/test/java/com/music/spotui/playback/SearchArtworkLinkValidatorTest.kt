package com.music.spotui.playback

import com.music.spotui.util.SearchArtworkLinkValidator
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SearchArtworkLinkValidatorTest {
    private val url = "https://i.scdn.co/image/ExactImage?size=1"
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte())
    private fun validator(code: Int = 200, type: String = "image/jpeg", bytes: ByteArray = jpeg,
        inspect: (okhttp3.Request) -> Unit = {}) = SearchArtworkLinkValidator(OkHttpClient.Builder().addInterceptor { chain ->
        inspect(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body(bytes.toResponseBody(type.toMediaType())).build()
    }.build())

    @Test fun exactReviewUrlAndBoundedImageRequestArePreserved() {
        assertTrue(validator(inspect = { assertEquals(url, it.url.toString()); assertEquals("bytes=0-31", it.header("Range")) }).isAvailable(url))
        assertTrue(validator(code = 206).isAvailable(url))
    }
    @Test fun missingForbiddenRedirectAndServerFailureAreNotReviewable() {
        listOf(404, 403, 302, 500).forEach { assertFalse(validator(code = it).isAvailable(url)) }
    }
    @Test fun htmlAndInvalidImageBodiesAreRejectedEvenWithSuccessfulHttp() {
        assertFalse(validator(type = "text/html", bytes = "<html>error</html>".toByteArray()).isAvailable(url))
        assertFalse(validator(bytes = "<html>error</html>".toByteArray()).isAvailable(url))
        assertFalse(validator(bytes = byteArrayOf()).isAvailable(url))
    }
    @Test fun supportedImageSignaturesAreCheckedAgainstContentType() {
        assertTrue(validator(type = "image/png", bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)).isAvailable(url))
        assertTrue(validator(type = "image/webp", bytes = "RIFF0000WEBPdata".toByteArray()).isAvailable(url))
        assertFalse(validator(type = "image/png").isAvailable(url))
    }
    @Test fun networkFailureRemainsRetryableAndDoesNotThrow() {
        val failing = SearchArtworkLinkValidator(OkHttpClient.Builder().addInterceptor { throw IOException("offline") }.build())
        assertFalse(failing.isAvailable(url))
    }
    @Test fun invalidOrChangedUrlIsRejectedBeforeNetwork() {
        val checked = validator(inspect = { fail("Unexpected network request") })
        assertFalse(checked.isAvailable("http://example.com/image"))
        assertFalse(checked.isAvailable(" $url"))
    }
}
