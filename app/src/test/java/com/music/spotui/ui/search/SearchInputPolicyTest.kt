package com.music.spotui.ui.search

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class SearchInputPolicyTest {
    @Test fun requestNormalizationDoesNotRewriteEditableSpaces() {
        val editable = "אייל   גולן  "
        assertEquals("אייל גולן", searchRequestText(editable))
        assertEquals("אייל   גולן  ", editable)
        assertEquals("", searchRequestText("   "))
    }
    @Test fun staleResponsesCannotReplaceNewSearchOrClearedInput() {
        val requests = LatestSearchRequest()
        var result = ""
        val old = requests.begin()
        val current = requests.begin()
        requests.publish(current) { result = "new" }
        requests.publish(old) { result = "old" }
        assertEquals("new", result)
        requests.begin() // cleared field invalidates every in-flight response
        result = ""
        requests.publish(current) { result = "new" }
        assertEquals("", result)
    }
    @Test fun parserRejectsWrongQueryAndMalformedPayload() {
        assertTrue(SearchSuggestions.parse("""["old",["old artist"]]""", "new").isEmpty())
        assertTrue(SearchSuggestions.parse("<html>error</html>", "אייל").isEmpty())
        assertTrue(SearchSuggestions.parse("[]", "אייל").isEmpty())
    }
    @Test fun parserPreservesRealSuggestionsInOrderAndBoundsTheList() {
        assertEquals(listOf("אייל גולן", "אייל לוי", "אייל חדש", "אייל ישנים", "אייל שירים"),
            SearchSuggestions.parse("""["אייל",["אייל גולן","אייל גולן","  אייל לוי  ",null,5,"","אייל חדש","אייל ישנים","אייל שירים","עוד"]]""", "אייל "))
    }
    @Test fun httpRequestEncodesUserTextAndFailureProducesNoSuggestions() {
        val query = "אייל & גולן"
        val request = SearchSuggestions.request(query)
        assertEquals(query, request.url.queryParameter("q"))
        assertEquals("suggestqueries.google.com", request.url.host)
        val client = OkHttpClient.Builder().addInterceptor { chain -> Response.Builder()
            .request(chain.request()).protocol(Protocol.HTTP_1_1).code(400).message("Bad request")
            .body("""["אייל",["must not display"]]""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        assertTrue(SearchSuggestions.fetch(client, "אייל").isEmpty())
    }
    @Test fun productionTransportParsesSuccessfulResponse() {
        val client = OkHttpClient.Builder().addInterceptor { chain -> Response.Builder()
            .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body("""["אייל",["אייל גולן","אייל לוי"]]""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        assertEquals(listOf("אייל גולן", "אייל לוי"), SearchSuggestions.fetch(client, "אייל"))
    }
    @Test fun liveProductionAutocomplete() {
        assumeTrue(System.getenv("SPOTUI_SEARCH_LIVE") == "1")
        val suggestions = SearchSuggestions.fetch(OkHttpClient(), "אייל")
        assertTrue(suggestions.isNotEmpty())
        assertTrue(suggestions.all { it.contains("אייל") })
        println("Live production autocomplete: ${suggestions.joinToString()}")
    }
}
