package com.fingertipsmcp.browser

/**
 * The scripts FingertipsMCP runs in a page's isolated world, from this module's resources.
 * Readability and pdf.js are Mozilla's, under the Apache License 2.0, with their licenses beside
 * them.
 */
object PageScripts {
    /** Readability.js, page-pending.js, page-data.js, then page-read.js, for reads and finds. */
    fun read(): String =
        listOf("readability/Readability.js", "page-pending.js", "page-data.js", "page-read.js")
            .joinToString("\n", transform = ::resourceText)

    /** page-data.js, for reads and finds with scope data. */
    fun data(): String = resourceText("page-data.js")

    /** page-pending.js, for snapshots. */
    fun pending(): String = resourceText("page-pending.js")

    /** pdf.js and pdf-read.js, as [pdfSource] joins them. */
    fun pdf(): String =
        pdfSource(
            resourceText("pdfjs/pdf.worker.min.mjs"),
            resourceText("pdfjs/pdf.min.mjs"),
            resourceText("pdf-read.js"),
        )

    private fun resourceText(name: String): String {
        val path = "com/fingertipsmcp/browser/$name"
        val stream =
            PageScripts::class.java.classLoader.getResourceAsStream(path)
                ?: error("Missing resource $path")
        return stream.use { it.readBytes().decodeToString() }
    }
}
