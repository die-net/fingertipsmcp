package com.fingertipsmcp.mcp

import com.fingertipsmcp.model.BrowserFindResult
import com.fingertipsmcp.model.BrowserReadResult
import com.fingertipsmcp.model.BrowserSnapshot
import com.fingertipsmcp.model.DataBlob
import com.fingertipsmcp.model.FeedLink
import com.fingertipsmcp.model.PageHeading
import com.fingertipsmcp.model.PageLink
import com.fingertipsmcp.model.QueryMatches
import com.fingertipsmcp.model.TextMatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class McpReadingTest {
    private val read =
        BrowserReadResult(
            title = "Doc",
            url = "https://example.com/a",
            published = "2026-01-02",
            readyState = "complete",
            status = 200,
            extracted = "article",
            version = "0badf00d",
            totalChars = 120,
            offset = 0,
            returnedChars = 8,
            nextOffset = 8,
            truncated = true,
            content = "# Title\n",
            links = 3,
        )

    @Test
    fun `read has a header`() {
        assertEquals(
            listOf(
                    """# title="Doc" url=https://example.com/a published=2026-01-02 status=200""",
                    "# article content, chars 0-8 of 120, version 0badf00d; nextOffset=8",
                    "",
                    "# Title",
                    "",
                )
                .joinToString("\n"),
            readText(read),
        )
    }

    @Test
    fun `read text flags a blocked page`() {
        val blocked =
            read.copy(blocked = "captcha", sparse = true, readyState = "loading", nextOffset = null)
        assertEquals(
            listOf(
                "# blocked: captcha",
                "# sparse: under 200 characters of visible text; it may still be loading",
                "# readyState=loading: the page is still loading",
                "# article content, chars 0-8 of 120, version 0badf00d; end",
            ),
            readText(blocked).lines().subList(1, 5),
        )
    }

    @Test
    fun `outline reports a cut`() {
        val outline =
            read.copy(
                content = null,
                headings = listOf(PageHeading(1, "Title", 0), PageHeading(2, "Steps", 40)),
            )
        val lines = outlineText(outline, maxChars = 12).lines()
        assertEquals(
            "# article content, 120 chars, version 0badf00d; 2 headings, @ is each one's offset. " +
                "On the page: 3 links, 0 tables, 0 forms",
            lines[1],
        )
        assertEquals("# showing 1 of 2 headings; raise maxChars for more", lines[2])
        assertEquals("@0 # Title", lines[3])
    }

    @Test
    fun `one line per result`() {
        val found =
            BrowserFindResult(
                extracted = "page",
                version = "1",
                totalChars = 50,
                total = 4,
                matches = listOf(TextMatch(12, "Steps", "a\nb ", "First", " c")),
            )
        assertEquals("""@12 under "Steps": …a b «First» c…""", matchesText(found).lines()[2])
        val links =
            found.copy(
                matches = null,
                links = listOf(PageLink("a link", "https://example.com/b", 9, true)),
            )
        assertEquals(
            """@9 "a link" https://example.com/b same-origin""",
            linksText(links).lines()[2],
        )
        assertEquals(
            "# 4 links in page content (version 1); showing 1; raise limit, up to 200, for " +
                "more; @ is where each one's text starts; 2 repeats of a URL above left out",
            linksText(links.copy(repeats = 2)).lines()[1],
        )
    }

    @Test
    fun `gives each query a section`() {
        val match = TextMatch(12, null, "a ", "gate", " b")
        val found =
            BrowserFindResult(
                extracted = "article",
                version = "1",
                totalChars = 50,
                total = 3,
                queries =
                    listOf(
                        QueryMatches("gate", 2, listOf(match)),
                        QueryMatches("boarding", 0),
                        QueryMatches("door", 1, listOf(match.copy(match = "door"))),
                    ),
            )
        assertEquals(
            listOf(
                "#",
                "# 3 queries in article content (50 chars, version 1), 3 matches in all; @ is " +
                    "each one's offset",
                """## "gate": 2 matches, showing 1; raise limit, up to 200, for more""",
                "@12 …a «gate» b…",
                """## "boarding": no matches""",
                """## "door": 1 match""",
                "@12 …a «door» b…",
            ),
            matchesText(found).lines(),
        )
    }

    @Test
    fun `cut find says how to see more`() {
        val found =
            BrowserFindResult(
                extracted = "selector",
                selected = 3,
                version = "1",
                totalChars = 50,
                total = 4,
                matches = listOf(TextMatch(12, null, "a ", "b", " c")),
            )
        assertEquals(
            "# 4 matches in selector content (3 matched) (50 chars, version 1); showing 1; " +
                "raise limit, up to 200, for more; @ is each one's offset",
            matchesText(found).lines()[1],
        )
        val atMost = found.copy(total = 300, matches = List(200) { found.matches!!.first() })
        assertEquals(
            "# 300 matches in selector content (3 matched) (50 chars, version 1); showing 200, " +
                "the most limit allows, so narrow the search for the rest; @ is each one's offset",
            matchesText(atMost).lines()[1],
        )
        val all = found.copy(total = 1)
        assertEquals(
            "# 1 matches in selector content (3 matched) (50 chars, version 1); showing 1; @ is " +
                "each one's offset",
            matchesText(all).lines()[1],
        )
    }

    @Test
    fun `read notes feeds and paywall`() {
        val feeds = List(7) { FeedLink("https://example.com/feed$it", "rss") }
        val noted =
            read.copy(
                paywall = true,
                feeds = listOf(FeedLink("https://example.com/atom", "atom", "Posts")) + feeds,
                next = "https://example.com/a?page=2",
                canonical = "https://example.com/canonical",
            )
        assertEquals(
            listOf(
                "# paywall: the page says its content is not free to read",
                """# feeds: atom https://example.com/atom "Posts", rss https://example.com/feed0, """ +
                    "rss https://example.com/feed1, rss https://example.com/feed2, " +
                    "rss https://example.com/feed3, and 3 more",
                "# next: https://example.com/a?page=2",
                "# canonical: https://example.com/canonical",
            ),
            readText(noted).lines().subList(1, 5),
        )
    }

    @Test
    fun `unchanged read has no content`() {
        val unchanged = read.copy(changed = false, content = null, returnedChars = 0)
        assertEquals(
            listOf(
                    """# title="Doc" url=https://example.com/a published=2026-01-02 status=200""",
                    "# article content unchanged, version 0badf00d, 120 chars; content not returned",
                )
                .joinToString("\n"),
            unchangedText(unchanged),
        )
        assertEquals(
            "# article content, chars 0-8 of 120, version 0badf00d (changed); nextOffset=8",
            readText(read.copy(changed = true)).lines()[1],
        )
    }

    @Test
    fun `read names the data blobs`() {
        val blobs = List(7) { DataBlob("blob$it", "script#blob$it", 900 + it * 5_000) }
        assertEquals(
            "# data: 7 embedded JSON blobs: blob0 900 chars, blob1 5.9k chars, blob2 10k chars, " +
                "blob3 15k chars, blob4 20k chars, and 2 more; read with scope data",
            readText(read.copy(data = blobs)).lines()[1],
        )
        assertEquals(
            "# data: the response is JSON, 1.2M chars; read it with scope data, path response",
            readText(read.copy(data = listOf(DataBlob("response", "the response", 1_234_567))))
                .lines()[1],
        )
    }

    @Test
    fun `read notes blobs past the list`() {
        val blobs = List(50) { DataBlob("blob$it", "script#blob$it", 900) }
        assertEquals(
            "# data: 50 embedded JSON blobs: blob0 900 chars, blob1 900 chars, blob2 900 chars, " +
                "blob3 900 chars, blob4 900 chars, and 45 more; read with scope data; 30 more " +
                "blobs past these can't be read",
            readText(read.copy(data = blobs, moreData = 30)).lines()[1],
        )
    }

    @Test
    fun `find notes a clipped result`() {
        val found =
            BrowserFindResult(
                extracted = "data",
                version = "1",
                totalChars = 50,
                total = 0,
                matches = emptyList(),
                clipped = true,
            )
        val note =
            "# clipped: the page has more data than one read takes, so blobs or values past it " +
                "are left out"
        assertEquals(note, matchesText(found).lines()[1])
        assertEquals(
            "# clipped: the page has more text than reading takes",
            linksText(found.copy(extracted = "page", matches = null)).lines()[1],
        )
        assertFalse(matchesText(found.copy(clipped = false)).contains("clipped"))
    }

    @Test
    fun `data read names what it read`() {
        val data = read.copy(extracted = "data", values = 2, content = "a: 1\nb: 2\n")
        assertEquals(
            "# 2 values at a/*, chars 0-8 of 120, version 0badf00d; nextOffset=8",
            readText(data, label = "2 values at a/*").lines()[1],
        )
    }

    @Test
    fun `data find shows match paths`() {
        val found =
            BrowserFindResult(
                extracted = "data",
                version = "1",
                totalChars = 50,
                total = 1,
                matches = listOf(TextMatch(7, null, "\"", "gate", "\": 1", path = "b/x/gate")),
            )
        assertEquals(
            listOf(
                "#",
                "# 1 matches in data; showing 1. Each match shows its path, and @ is its offset " +
                    "into the JSON browser_read returns for that blob, or for find's path when " +
                    "one was given",
                """@7 at b/x/gate: …"«gate»": 1…""",
            ),
            matchesText(found).lines(),
        )
    }

    @Test
    fun `drawn tab adds no notes`() {
        assertEquals(readText(read), readText(read.copy(shown = true)))
    }

    @Test
    fun `hidden tab says how to show`() {
        val hidden =
            read.copy(shown = false, pending = listOf("busy region", "1 loading placeholder"))
        assertEquals(
            listOf(
                "# pending: busy region, 1 loading placeholder",
                "# hidden: this tab is not on screen, so content that loads when drawn may be " +
                    "missing; read again with show true to bring it forward",
            ),
            readText(hidden).lines().subList(1, 3),
        )
    }

    @Test
    fun `quiet hidden tab gets a note`() {
        assertEquals(
            "# hidden: this tab is not on screen",
            readText(read.copy(shown = false)).lines()[1],
        )
    }

    @Test
    fun `failed show says so`() {
        assertEquals(
            "# still hidden after show: the tab did not come on screen; the phone may be locked",
            readText(read.copy(shown = false, sparse = true), show = true).lines()[2],
        )
    }

    @Test
    fun `snapshot notes follow header`() {
        val snapshot =
            BrowserSnapshot(
                id = "s~t",
                compact = "# url=https://example.com/\n[1] RootWebArea",
                shown = false,
                pending = listOf("\"Load more\" control"),
            )
        assertEquals(
            listOf(
                    "# url=https://example.com/",
                    "# pending: \"Load more\" control",
                    "# hidden: this tab is not on screen, so content that loads when drawn may " +
                        "be missing; snapshot again with show true to bring it forward",
                    "[1] RootWebArea",
                )
                .joinToString("\n"),
            snapshotText(snapshot),
        )
        assertEquals("only", snapshotText(BrowserSnapshot(id = "s~t", compact = "only")))
    }
}
