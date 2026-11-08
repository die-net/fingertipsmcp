package com.fingertipsmcp.tabs

import java.io.File

/** Keeps the ids of tabs sessions opened in [file], one per line. */
class FileTabStore(private val file: File) : TabStore {
    override fun load(): Set<String> =
        if (file.exists()) file.readLines().filter { it.isNotBlank() }.toSet() else emptySet()

    override fun save(ids: Set<String>) {
        file.writeText(ids.joinToString("\n"))
    }
}
