package app.chudmusic.youtube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Small helpers to walk InnerTube's deeply nested JSON without a model per renderer:
// a missing step gives null instead of an exception (YouTube adds and moves fields often).

internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
internal operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)

internal fun JsonElement?.path(vararg steps: Any): JsonElement? {
    var cur = this
    for (s in steps) {
        cur = when (s) {
            is String -> cur[s]
            is Int -> cur[s]
            else -> null
        } ?: return null
    }
    return cur
}

internal val JsonElement?.arr: List<JsonElement> get() = (this as? JsonArray) ?: emptyList()
internal val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.contentOrNull
internal val JsonElement?.long: Long? get() = (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()

/** "runs" → their concatenated text; also accepts "simpleText". */
internal val JsonElement?.text: String?
    get() {
        if (this == null) return null
        this["simpleText"].str?.let { return it }
        val runs = this["runs"].arr
        if (runs.isEmpty()) return null
        return runs.joinToString("") { it["text"].str.orEmpty() }.ifBlank { null }
    }

/** Every object found anywhere under this element having this key (depth-first). */
internal fun JsonElement?.findAll(key: String, out: MutableList<JsonElement> = mutableListOf()): List<JsonElement> {
    when (this) {
        is JsonObject -> for ((k, v) in this) {
            if (k == key) out += v
            v.findAll(key, out)
        }
        is JsonArray -> for (v in this) v.findAll(key, out)
        else -> {}
    }
    return out
}

internal fun JsonElement?.findFirst(key: String): JsonElement? {
    when (this) {
        is JsonObject -> {
            this[key]?.let { return it }
            for (v in values) v.findFirst(key)?.let { return it }
        }
        is JsonArray -> for (v in this) v.findFirst(key)?.let { return it }
        else -> {}
    }
    return null
}

/** "3:45" or "1:02:03" → milliseconds. */
internal fun parseDuration(s: String?): Long? {
    if (s == null || !Regex("""^\d{1,2}(:\d{2}){1,2}$""").matches(s.trim())) return null
    return s.trim().split(':').fold(0L) { acc, p -> acc * 60 + p.toLong() } * 1000
}
