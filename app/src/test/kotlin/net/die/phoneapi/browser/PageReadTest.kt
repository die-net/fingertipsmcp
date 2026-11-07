package net.die.phoneapi.browser

import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserFindRequest
import net.die.phoneapi.model.BrowserReadRequest
import net.die.phoneapi.model.EvalResult
import net.die.phoneapi.model.FeedLink
import net.die.phoneapi.model.FindKind
import net.die.phoneapi.model.ReadFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageReadTest {
    private val blocks =
        listOf(
            """{"k":"h","l":1,"r":[{"t":"Title"}]}""",
            """{"k":"p","r":[{"t":" Some "},{"t":"bold","s":"b"},{"t":" text with "},""" +
                """{"t":"a link","h":"https://example.com/b"},{"t":" and "},""" +
                """{"t":"another","h":"https://other.org/c"},{"t":". "}]}""",
            """{"k":"h","l":2,"r":[{"t":"Steps"}]}""",
            """{"k":"li","d":1,"o":1,"r":[{"t":"First"}]}""",
            """{"k":"li","d":2,"r":[{"t":"Nested"}]}""",
            """{"k":"tr","t":0,"c":[[{"t":"Name"}],[{"t":"Value"}]],"th":true}""",
            """{"k":"tr","t":0,"c":[[{"t":"a|b"}],[{"t":"1"}]]}""",
            """{"k":"tr","t":0,"c":[[],[{"t":" "}]]}""",
            """{"k":"p","q":1,"r":[{"t":"Quoted"}]}""",
            """{"k":"pre","x":"code()"}""",
            """{"k":"p","r":[{"t":"   "}]}""",
        )

    private val extract =
        ApiJson.decodeFromString(
            PageExtract.serializer(),
            """{"title":"Doc","url":"https://example.com/a","readyState":"complete",""" +
                """"status":200,"pageChars":900,"links":2,"tables":1,"forms":0,""" +
                """"extracted":"article","blocks":[${blocks.joinToString(",")}]}""",
        )

    @Test
    fun `renders markdown`() {
        val page = renderPage(extract.blocks, ReadFormat.MARKDOWN)
        assertEquals(
            listOf(
                    "# Title",
                    "",
                    "Some **bold** text with a link and another.",
                    "",
                    "## Steps",
                    "",
                    "1. First",
                    "  - Nested",
                    "",
                    "| Name | Value |",
                    "| --- | --- |",
                    "| a\\|b | 1 |",
                    "",
                    "> Quoted",
                    "",
                    "```",
                    "code()",
                    "```",
                )
                .joinToString("\n"),
            page.text,
        )
        assertEquals(listOf(0, page.text.indexOf("## Steps")), page.headings.map { it.offset })
        val link = page.links.first()
        assertEquals("a link", link.text)
        assertEquals("a link", page.text.substring(link.offset, link.offset + link.text.length))
    }

    @Test
    fun `finds a link in a table cell`() {
        val row =
            """{"k":"tr","t":0,"c":[[{"t":"Spec "},{"t":"sheet","h":"https://example.com/s"}],""" +
                """[{"t":"a|b"}]]}"""
        val table = ApiJson.decodeFromString<List<PageBlock>>("[$row]")
        for (format in ReadFormat.entries) {
            val page = renderPage(table, format)
            val link = page.links.single()
            assertEquals("sheet", page.text.substring(link.offset, link.offset + 5), "$format")
        }
        val markdown = renderPage(table, ReadFormat.MARKDOWN).text
        assertEquals("| Spec sheet | a\\|b |\n| --- | --- |", markdown)
    }

    @Test
    fun `renders text without markup`() {
        val page = renderPage(extract.blocks, ReadFormat.TEXT)
        val start = listOf("Title", "", "Some bold text with a link and another.", "", "Steps")
        assertTrue(page.text.startsWith(start.joinToString("\n")))
        assertTrue("Name | Value\na|b | 1" in page.text)
        assertFalse("```" in page.text)
    }

    @Test
    fun `reads a slice at a time`() {
        val whole = readPage(extract, BrowserReadRequest())
        assertFalse(whole.truncated)
        assertNull(whole.nextOffset)
        assertEquals(whole.totalChars, whole.returnedChars)
        assertEquals(200, whole.status)
        assertFalse(whole.sparse)

        val first = readPage(extract, BrowserReadRequest(maxChars = 12))
        assertTrue(first.truncated)
        assertEquals("# Title\n\n", first.content)
        val next = readPage(extract, BrowserReadRequest(offset = first.nextOffset!!, maxChars = 30))
        assertTrue(next.content!!.startsWith("Some **bold**"))
        assertEquals(first.version, next.version)
        assertThrows(ApiException::class.java) {
            readPage(extract, BrowserReadRequest(offset = whole.totalChars + 1))
        }
    }

    @Test
    fun `unchanged version has no text`() {
        val whole = readPage(extract, BrowserReadRequest())
        assertNull(whole.changed)
        val same = readPage(extract, BrowserReadRequest(version = whole.version))
        assertEquals(false, same.changed)
        assertNull(same.content)
        assertNull(same.nextOffset)
        assertEquals(0, same.returnedChars)
        assertEquals(whole.totalChars, same.totalChars)
        val sameOutline =
            readPage(extract, BrowserReadRequest(version = whole.version, outline = true))
        assertNull(sameOutline.headings)
        // An unchanged page needs no slice, so an offset past its end is not an error.
        val pastEnd =
            readPage(
                extract,
                BrowserReadRequest(version = whole.version, offset = whole.totalChars + 1),
            )
        assertEquals(false, pastEnd.changed)

        val other = readPage(extract, BrowserReadRequest(version = "00000000", maxChars = 12))
        assertEquals(true, other.changed)
        assertEquals("# Title\n\n", other.content)
    }

    @Test
    fun `carries notes and match count`() {
        val noted =
            extract.copy(
                paywall = true,
                feeds = listOf(FeedLink("https://example.com/feed", "atom")),
                next = "https://example.com/a?page=2",
                canonical = "https://example.com/c",
                extracted = "selector",
                selected = 2,
            )
        val read = readPage(noted, BrowserReadRequest())
        assertTrue(read.paywall)
        assertEquals(noted.feeds, read.feeds)
        assertEquals(noted.next, read.next)
        assertEquals(noted.canonical, read.canonical)
        assertEquals(2, read.selected)
        val found = findInPage(noted, BrowserFindRequest(query = "first"))
        assertEquals(2, found.selected)
    }

    @Test
    fun `reports what caps left out`() {
        val capped = extract.copy(moreData = 30, clipped = true)
        assertEquals(30, readPage(capped, BrowserReadRequest()).moreData)
        assertEquals(0, readPage(extract, BrowserReadRequest()).moreData)
        assertTrue(findInPage(capped, BrowserFindRequest(query = "first")).clipped)
        assertTrue(findInPage(capped, BrowserFindRequest(kind = FindKind.LINKS)).clipped)
        assertFalse(findInPage(extract, BrowserFindRequest(query = "first")).clipped)
    }

    @Test
    fun `outline has only headings`() {
        val outline = readPage(extract, BrowserReadRequest(outline = true))
        assertNull(outline.content)
        assertEquals(listOf("Title", "Steps"), outline.headings!!.map { it.text })
        assertEquals(listOf(1, 2), outline.headings!!.map { it.level })
        assertEquals(2, outline.links)
    }

    @Test
    fun `cuts at a line or a space`() {
        assertEquals(6, cutPoint("abcde\nfghij", 0, 8))
        assertEquals(6, cutPoint("abcde fghij", 0, 8))
        assertEquals(4, cutPoint("abcdefghij", 0, 4))
        assertEquals(3, cutPoint("abc😀def", 0, 4))
        assertEquals(10, cutPoint("abcdefghij", 2, 100))
    }

    @Test
    fun `finds text in context`() {
        val result = findInPage(extract, BrowserFindRequest(query = "FIRST", contextChars = 5))
        assertEquals(1, result.total)
        val match = result.matches!!.single()
        assertEquals("First", match.match)
        assertEquals("Steps", match.heading)
        assertEquals("\n\n1. ", match.before)
        val page = renderPage(extract.blocks, ReadFormat.MARKDOWN)
        assertEquals(page.text.indexOf("First"), match.offset)

        val regex = findInPage(extract, BrowserFindRequest(query = "n[a-z]+d", regex = true))
        assertEquals(listOf("Nested"), regex.matches!!.map { it.match })
        assertThrows(ApiException::class.java) {
            findInPage(extract, BrowserFindRequest(query = "(", regex = true))
        }
        assertThrows(ApiException::class.java) { findInPage(extract, BrowserFindRequest()) }
    }

    @Test
    fun `lists links with filters`() {
        val all = findInPage(extract, BrowserFindRequest(kind = FindKind.LINKS))
        assertEquals(2, all.total)
        assertEquals(listOf(true, false), all.links!!.map { it.sameOrigin })
        val other =
            findInPage(extract, BrowserFindRequest(kind = FindKind.LINKS, hrefContains = "OTHER"))
        assertEquals(listOf("another"), other.links!!.map { it.text })
        val text = findInPage(extract, BrowserFindRequest(kind = FindKind.LINKS, query = "link"))
        assertEquals(listOf("https://example.com/b"), text.links!!.map { it.href })
    }

    @Test
    fun `finds several queries at once`() {
        val result =
            findInPage(extract, BrowserFindRequest(queries = listOf("first", "missing", "e")))
        val queries = result.queries!!
        assertEquals(listOf("first", "missing", "e"), queries.map { it.query })
        assertEquals(1, queries[0].total)
        assertEquals("First", queries[0].matches.single().match)
        assertEquals(0, queries[1].total)
        assertTrue(queries[1].matches.isEmpty())
        assertEquals(queries.sumOf { it.total }, result.total)
        assertNull(result.matches)
    }

    @Test
    fun `checks queries`() {
        val bad =
            listOf(
                BrowserFindRequest(query = "a", queries = listOf("b")),
                BrowserFindRequest(kind = FindKind.LINKS, queries = listOf("b")),
                BrowserFindRequest(queries = List(21) { "q$it" }),
                BrowserFindRequest(queries = listOf("")),
            )
        for (request in bad) {
            assertThrows(ApiException::class.java) { checkFind(request) }
        }
        checkFind(BrowserFindRequest(queries = List(20) { "q$it" }))
    }

    @Test
    fun `merges a link split by styling`() {
        val styled =
            ApiJson.decodeFromString(
                PageExtract.serializer(),
                """{"url":"https://shop.example/","extracted":"page","blocks":[""" +
                    """{"k":"p","r":[{"t":"Lint","s":"b","h":"https://shop.example/p"},""" +
                    """{"t":" ","h":"https://shop.example/p"},""" +
                    """{"t":"Roller ","h":"https://shop.example/p"},{"t":"now"},""" +
                    """{"t":" Lint","h":"https://shop.example/p"}]}]}""",
            )
        val page = renderPage(styled.blocks, ReadFormat.MARKDOWN)
        assertEquals("**Lint** Roller now Lint", page.text)
        assertEquals(listOf("Lint Roller", "Lint"), page.links.map { it.text })
        assertEquals(listOf(2, 20), page.links.map { it.offset })
    }

    @Test
    fun `lists each URL once as written`() {
        val shop =
            ApiJson.decodeFromString(
                PageExtract.serializer(),
                """{"url":"https://shop.example/s","extracted":"page","blocks":[""" +
                    """{"k":"p","r":[{"t":"Image","h":"https://shop.example/p/1""" +
                    """?ref=search&utm_source=a&fbclid=q"}]},""" +
                    """{"k":"p","r":[{"t":"Lint roller","h":"https://shop.example/p/1""" +
                    """?ref=search"}]},""" +
                    """{"k":"p","r":[{"t":"Other","h":"https://shop.example/p/2#top"}]},""" +
                    """{"k":"p","r":[{"t":"Pipes","h":"https://shop.example/q?a=1|2"}]}]}""",
            )
        val result = findInPage(shop, BrowserFindRequest(kind = FindKind.LINKS))
        assertEquals(3, result.total)
        assertEquals(1, result.repeats)
        assertEquals(
            listOf(
                "https://shop.example/p/1?ref=search&utm_source=a&fbclid=q",
                "https://shop.example/p/2#top",
                "https://shop.example/q?a=1|2",
            ),
            result.links!!.map { it.href },
        )
        assertEquals("Image", result.links!!.first().text)
        // A character java.net.URI refuses, as in the last link's query, keeps it same-origin.
        assertEquals(listOf(true, true, true), result.links!!.map { it.sameOrigin })
    }

    @Test
    fun `drops only known tracking`() {
        assertEquals(
            "https://example.com/a?id=3#x",
            withoutTracking("https://example.com/a?utm_source=n&id=3&fbclid=q#x"),
        )
        assertEquals(
            "https://github.com/o/r/tree/x?ref=main&qid=2",
            withoutTracking("https://github.com/o/r/tree/x?ref=main&qid=2"),
        )
        // No site has rules of its own, so a parameter that tracks on one site is kept.
        assertEquals(
            "https://aws.amazon.com/x/ref=y?tag=t&sr=1",
            withoutTracking("https://aws.amazon.com/x/ref=y?tag=t&sr=1&UTM_Campaign=z"),
        )
        assertEquals("https://example.com/", withoutTracking("https://example.com/?utm_medium=e"))
    }

    @Test
    fun `finds a link's origin`() {
        assertEquals("https://a.example:443", origin("https://a.example/x?q=1|2"))
        assertEquals("http://a.example:8080", origin("http://user@a.example:8080/"))
        assertEquals("http://[::1]:80", origin("http://[::1]/x"))
        assertEquals("http://[::1]:9", origin("http://[::1]:9"))
        assertNull(origin("mailto:a@example.com"))
    }

    @Test
    fun `carries pending signs`() {
        val waiting =
            ApiJson.decodeFromString(
                PageExtract.serializer(),
                """{"visibility":"hidden","pending":["busy region"],"pageChars":900}""",
            )
        assertEquals("hidden", waiting.visibility)
        val result = readPage(waiting, BrowserReadRequest())
        assertEquals(listOf("busy region"), result.pending)
        assertNull(result.shown)
    }

    @Test
    fun `counts all, keeps the first`() {
        val text = "the cat and the hat and the bat"
        val (total, matches) = matchesIn(text, emptyList(), Regex("the"), 4, 2)
        assertEquals(3, total)
        assertEquals(listOf(0, 12), matches.map { it.offset })
        assertEquals(" cat", matches[0].after)
        assertEquals("and ", matches[1].before)
    }

    @Test
    fun `skips empty matches`() {
        val (total, matches) = matchesIn("axbxx", emptyList(), Regex("x*"), 1, 10)
        assertEquals(2, total)
        assertEquals(listOf("x", "xx"), matches.map { it.match })
    }

    @Test
    fun `a bad selector is bad_request`() {
        val bad = EvalResult(value = ApiJson.parseToJsonElement("""{"badSelector":true}"""))
        val error = assertThrows(ApiException::class.java) { decodeExtract(bad) }
        assertEquals("bad_request", error.error)
        // A page whose own script error happens to mention selectors is still the page's failure.
        val thrown = EvalResult(exception = "Error: 'x' is not a valid selector")
        assertEquals(
            "page_error",
            assertThrows(ApiException::class.java) { decodeExtract(thrown) }.error,
        )
    }

    @Test
    fun `stops counting at the cap`() {
        val (total, matches) = matchesIn("a".repeat(12_000), emptyList(), Regex("a"), 0, 3)
        assertEquals(10_000, total)
        assertEquals(3, matches.size)
    }
}
