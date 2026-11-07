package net.die.phoneapi.browser

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import net.die.phoneapi.model.BrowserSnapshotOptions

/**
 * Turns `Accessibility.getFullAXTree` into the same kind of indented text the native snapshot uses.
 * Ignored nodes are skipped and their children promoted. Node ids are Chrome's, so a later tap can
 * address them.
 */
internal data class AxText(val title: String?, val compact: String)

internal fun formatAxTree(
    result: JsonObject,
    url: String? = null,
    options: BrowserSnapshotOptions = BrowserSnapshotOptions(),
): AxText {
    val raw = result.decodeCdp<NodeList>()?.nodes ?: return blank(url)
    var unread = 0
    val nodes = raw.mapNotNull { element ->
        element.decodeCdp<AxNode>() ?: unreadNode(element)?.also { unread++ }
    }
    return AxTree(nodes, unread).format(url, options)
}

/**
 * A node whose fields did not decode, kept by its id and children as an ignored node, so its
 * children stay in its place rather than becoming roots of their own. Null without an id.
 */
private fun unreadNode(element: JsonElement): AxNode? {
    val fields = element as? JsonObject ?: return null
    val id = (fields["nodeId"] as? JsonPrimitive)?.contentOrNull ?: return null
    val children =
        (fields["childIds"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
    return AxNode(nodeId = id, ignored = true, childIds = children)
}

private fun blank(url: String?) = AxText(null, header(url, title = null, notes = emptyList()))

/** The tree of [nodes], [unread] of which Chrome sent in a form that did not decode. */
private class AxTree(nodes: List<AxNode>, private val unread: Int) {
    private val byId = LinkedHashMap<String, AxNode>()
    private val childrenOf = HashMap<String, List<String>>()
    private val claimed = HashSet<String>()

    init {
        for (node in nodes) add(node)
    }

    fun format(url: String?, options: BrowserSnapshotOptions): AxText {
        val walk = Walk(this)
        walk.emitList(roots(), 0)
        val title =
            roots().firstOrNull()?.let {
                byId[it]?.name.text()?.takeIf { name -> name.isNotBlank() }
            }
        val kept = keep(walk.lines, options)
        val notes = buildList {
            if (kept.size < walk.lines.size) {
                add(
                    "# showing ${kept.size} of ${walk.lines.size} nodes; " +
                        "raise maxNodes or maxChars for more"
                )
            }
            if (walk.depthCut) add("# nodes more than $MAX_DEPTH levels deep left out")
            if (unread > 0) {
                add("# nodes that could not be read: $unread; their children stand in their place")
            }
        }
        val body = kept.joinToString("") { "\n" + it }
        return AxText(title, header(url, title, notes) + body)
    }

    fun node(id: String): AxNode? = byId[id]

    fun children(id: String): List<String> = childrenOf[id].orEmpty()

    fun ignored(node: AxNode): Boolean = node.ignored

    private fun roots(): List<String> = byId.keys.filter { it !in claimed }

    private fun add(node: AxNode) {
        val id = node.nodeId ?: return
        byId[id] = node
        childrenOf[id] = node.childIds
        claimed += node.childIds
    }
}

private fun keep(lines: List<String>, options: BrowserSnapshotOptions): List<String> {
    val maxChars = options.maxChars ?: Int.MAX_VALUE
    var chars = 0
    return lines.take(options.maxNodes).takeWhile { line ->
        chars += line.length + 1
        chars <= maxChars
    }
}

private class Walk(private val tree: AxTree) {
    val lines = ArrayList<String>()
    var depthCut = false
    private val seen = HashSet<String>()

    fun emitList(ids: List<String>, depth: Int) {
        var index = 0
        while (index < ids.size) {
            val run = textRun(ids, index)
            if (run == null) {
                emit(ids[index], depth)
                index++
            } else {
                appendRun(run, depth)
                index += run.size
            }
        }
    }

    fun emit(id: String, depth: Int) {
        if (!enter(id, depth)) return
        val node = tree.node(id) ?: return
        val ignored = tree.ignored(node)
        if (!ignored) append(node, id, depth, node.name.text()?.takeIf { it.isNotBlank() })
        val nextDepth = if (ignored) depth else depth + 1
        emitList(tree.children(id), nextDepth)
    }

    private fun textRun(ids: List<String>, start: Int): List<String>? {
        if (!leafText(ids[start])) return null
        var end = start + 1
        while (end < ids.size && leafText(ids[end])) end++
        return ids.subList(start, end).takeIf { it.size > 1 }
    }

    private fun leafText(id: String): Boolean {
        val node = tree.node(id) ?: return false
        if (tree.ignored(node) || id in seen) return false
        if (node.role.text() != "StaticText") return false
        return tree.children(id).isEmpty() && flags(node).isEmpty()
    }

    private fun appendRun(ids: List<String>, depth: Int) {
        val first = ids.first()
        if (!enter(first, depth)) return
        ids.drop(1).forEach { seen.add(it) }
        val node = tree.node(first) ?: return
        val name = ids.joinToString("") { tree.node(it)?.name.text().orEmpty() }.ifBlank { null }
        append(node, first, depth, name)
    }

    private fun enter(id: String, depth: Int): Boolean {
        if (!seen.add(id)) return false
        if (depth <= MAX_DEPTH) return true
        depthCut = true
        return false
    }

    private fun append(node: AxNode, id: String, depth: Int, name: String?) {
        lines += line(node, id, depth, name)
    }
}

/** The header line, ending with ` truncated` when [notes] say what was left out. */
private fun header(url: String?, title: String?, notes: List<String>): String = buildString {
    append("#")
    title?.let { append(" title=").append(quote(it)) }
    url?.let { append(" url=").append(it) }
    if (notes.isNotEmpty()) append(" truncated")
    for (note in notes) append('\n').append(note)
}

private fun line(node: AxNode, id: String, depth: Int, name: String?): String = buildString {
    repeat(depth) { append("  ") }
    append('[').append(id).append("] ")
    append(node.role.text()?.takeIf { it.isNotBlank() } ?: "node")
    name?.let { append(' ').append(quote(it)) }
    for (flag in flags(node)) append(' ').append(flag)
}

private fun flags(node: AxNode): List<String> =
    node.properties.mapNotNull { property ->
        if (property.name !in SHOWN_FLAGS) return@mapNotNull null
        val value = property.value?.value as? JsonPrimitive ?: return@mapNotNull null
        if (value.booleanOrNull != true) return@mapNotNull null
        property.name
    }

private fun AxValue?.text(): String? {
    val value = this?.value as? JsonPrimitive ?: return null
    return value.contentOrNull
}

private fun quote(text: String): String = buildString {
    append('"')
    for (ch in text) {
        when (ch) {
            '\\' -> append('\\').append('\\')
            '"' -> append('\\').append('"')
            '\n',
            '\r' -> append(' ')
            else -> append(ch)
        }
    }
    append('"')
}

@Serializable private data class NodeList(val nodes: List<JsonElement> = emptyList())

@Serializable
private data class AxNode(
    val nodeId: String? = null,
    val ignored: Boolean = false,
    val role: AxValue? = null,
    val name: AxValue? = null,
    val childIds: List<String> = emptyList(),
    val properties: List<AxProperty> = emptyList(),
)

@Serializable private data class AxValue(val value: JsonElement? = null)

@Serializable private data class AxProperty(val name: String = "", val value: AxValue? = null)

private val SHOWN_FLAGS = setOf("focused", "checked", "disabled", "selected", "expanded")

private const val MAX_DEPTH = 40
