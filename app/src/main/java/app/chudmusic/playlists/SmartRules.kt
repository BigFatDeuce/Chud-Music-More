package app.chudmusic.playlists

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The description of a smart playlist (rules, exclusions, order, size), and the SQL that answers
 * it. Nothing is stored but the description: the list is asked again whenever the library or the
 * plays change.
 *
 * The JSON is the one of Chud Music for PC (its `src/shared/smart.ts`), in the database and in the
 * playlist files both apps exchange: the keys are part of that format and mean the same on both sides.
 */
data class SmartRules(
    /** true: a track must fit every rule; false: one is enough. Without rules, every track fits. */
    val all: Boolean = true,
    val rules: List<Rule> = emptyList(),
    /** A track fitting any of these is left out… */
    val exclude: List<Rule> = emptyList(),
    /** …and so are these tracks, taken out by hand (ids of this device). */
    val excludeIds: List<String> = emptyList(),
    val order: Order = Order.ADDED,
    val descending: Boolean = true,
    /** At most this many tracks; null = no limit of its own ([MAX] all the same). */
    val limit: Int? = null,
) {
    enum class Kind { TEXT, NUMBER, MINUTES, DAYS }

    enum class Field(val key: String, val kind: Kind, val column: String) {
        GENRE("genre", Kind.TEXT, "genre"),
        ARTIST("artist", Kind.TEXT, "artist"),
        ALBUM("album", Kind.TEXT, "album"),
        TITLE("title", Kind.TEXT, "title"),
        FOLDER("folder", Kind.TEXT, "path"),
        YEAR("year", Kind.NUMBER, "year"),
        PLAYS("plays", Kind.NUMBER, "playCount"),
        ADDED("added", Kind.DAYS, "addedAt"),
        PLAYED("played", Kind.DAYS, "lastPlayedAt"),
        DURATION("duration", Kind.MINUTES, "durationMs"),
        ;

        val ops: List<Op>
            get() = when (this) {
                PLAYS -> listOf(Op.EQ, Op.GTE, Op.LTE)
                YEAR, DURATION -> listOf(Op.EQ, Op.GTE, Op.LTE, Op.UNKNOWN)
                ADDED -> listOf(Op.WITHIN, Op.BEFORE)
                PLAYED -> listOf(Op.NEVER, Op.EVER, Op.WITHIN, Op.BEFORE)
                else -> listOf(Op.CONTAINS, Op.NOT_CONTAINS, Op.IS, Op.IS_NOT, Op.EMPTY)
            }
    }

    enum class Op(val key: String, val needsValue: Boolean = true) {
        CONTAINS("contains"), NOT_CONTAINS("notContains"), IS("is"), IS_NOT("isNot"), EMPTY("empty", false),
        EQ("eq"), GTE("gte"), LTE("lte"), UNKNOWN("unknown", false),
        WITHIN("within"), BEFORE("before"), NEVER("never", false), EVER("ever", false),
    }

    enum class Order {
        ADDED, PLAYS, PLAYED, TITLE, ARTIST, ALBUM, YEAR, DURATION, RANDOM;

        val key get() = name.lowercase()
    }

    data class Rule(val field: Field, val op: Op, val value: String = "") {
        /** A rule not filled in yet counts for nothing (it would empty the list). */
        val complete: Boolean
            get() = op in this.field.ops && (!op.needsValue || (if (this.field.kind == Kind.TEXT) value.isNotBlank() else number() != null))

        fun number(): Double? = value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 && it < 1e9 }

        companion object {
            /** What a new rule on this field starts with. */
            fun new(f: Field) = Rule(
                f, f.ops.first(),
                when (f) { Field.YEAR -> "2000"; Field.PLAYS -> "0"; Field.DURATION -> "3"; Field.ADDED -> "30"; else -> "" },
            )
        }
    }

    /** [where] holds for a track in the list; [args] are its values, in order. */
    class Sql(val where: String, val order: String, val limit: Int, val args: List<Any>) {
        val text get() = "SELECT * FROM tracks WHERE $where ORDER BY $order LIMIT $limit"
        val totals get() = "SELECT COUNT(*) AS count, COALESCE(SUM(durationMs), 0) AS durationMs FROM (SELECT durationMs FROM tracks WHERE $where ORDER BY $order LIMIT $limit)"
        val covers get() = "SELECT DISTINCT coverUri FROM (SELECT coverUri FROM tracks WHERE $where ORDER BY $order LIMIT $limit) WHERE coverUri IS NOT NULL LIMIT 4"
    }

    /** [now] gives the day limits, and the random order its day (the list does not jump at each refresh). */
    fun sql(now: Long): Sql {
        val args = mutableListOf<Any>()
        val parts = mutableListOf("inLibrary = 1", "available = 1")
        val inc = rules.filter { it.complete }.map { condition(it, now, args) }
        if (inc.isNotEmpty()) parts += inc.joinToString(if (all) " AND " else " OR ", "(", ")")
        // An unknown value (NULL) answers no rule: it never leaves a track out.
        exclude.filter { it.complete }.forEach { parts += "NOT COALESCE(${condition(it, now, args)}, 0)" }
        if (excludeIds.isNotEmpty()) {
            // SQLite takes a limited number of values: the oldest exclusions beyond it are dropped.
            val ids = excludeIds.takeLast(MAX_EXCLUDED)
            parts += "id NOT IN (" + ids.joinToString(",") { "?" } + ")"
            args.addAll(ids)
        }
        val dir = if (descending) "DESC" else "ASC"
        val by = when (order) {
            Order.ADDED -> "addedAt $dir, album, discNo, trackNo"
            Order.PLAYS -> "playCount $dir, lastPlayedAt $dir"
            Order.PLAYED -> "lastPlayedAt IS NULL, lastPlayedAt $dir"
            Order.TITLE -> "title COLLATE NOCASE $dir"
            Order.ARTIST -> "COALESCE(albumArtist, artist) IS NULL, COALESCE(albumArtist, artist) COLLATE NOCASE $dir, album COLLATE NOCASE, discNo, trackNo"
            Order.ALBUM -> "album IS NULL, album COLLATE NOCASE $dir, discNo, trackNo"
            Order.YEAR -> "year IS NULL, year $dir, album COLLATE NOCASE, discNo, trackNo"
            Order.DURATION -> "durationMs IS NULL, durationMs $dir"
            Order.RANDOM -> {
                // Quadratic in rowid: two days give two orders, not the same one started elsewhere.
                val h = ((((now / DAY_MS).toInt() + 1) * GOLDEN) ushr 1).toLong() % P
                "(((rowid * rowid) % $P * ${h + 1} + rowid * ${(h * 48_271) % P + 1}) % $P)"
            }
        }
        return Sql(parts.joinToString(" AND "), "$by, id", (limit ?: MAX).coerceIn(1, MAX), args)
    }

    private fun condition(r: Rule, now: Long, args: MutableList<Any>): String {
        val col = r.field.column
        return when (r.field.kind) {
            Kind.TEXT -> {
                // A track's artist or its album's: "artist is X" finds an album whose tracks name guests.
                val names = if (r.field == Field.ARTIST) listOf("artist", "albumArtist") else listOf(col)
                if (r.op == Op.EMPTY) return names.joinToString(" AND ", "(", ")") { "COALESCE($it, '') = ''" }
                val g = glob(r.value.trim()).let { if (r.op == Op.IS || r.op == Op.IS_NOT) it else "*$it*" }
                val any = names.joinToString(" OR ", "(", ")") { args += g; "COALESCE($it, '') GLOB ?" }
                if (r.op == Op.NOT_CONTAINS || r.op == Op.IS_NOT) "(NOT $any)" else any
            }
            Kind.NUMBER, Kind.MINUTES -> {
                if (r.op == Op.UNKNOWN) return "($col IS NULL OR $col = 0)"
                val minutes = r.field.kind == Kind.MINUTES
                val n = r.number()!!.let { if (minutes) (it * 60_000).toLong() else it.toLong() }
                args += n
                // "= 3 minutes" means 3:00 to 3:59.
                if (minutes && r.op == Op.EQ) { args += n + 60_000; return "($col >= ? AND $col < ?)" }
                val cmp = when (r.op) { Op.GTE -> ">="; Op.LTE -> "<="; else -> "=" }
                // An unknown year or length answers no comparison.
                if (r.field == Field.PLAYS) "($col $cmp ?)" else "($col IS NOT NULL AND $col > 0 AND $col $cmp ?)"
            }
            Kind.DAYS -> when (r.op) {
                Op.NEVER -> "(playCount = 0 AND lastPlayedAt IS NULL)"
                Op.EVER -> "(playCount > 0 OR lastPlayedAt IS NOT NULL)"
                else -> { args += now - (r.number()!! * DAY_MS).toLong(); if (r.op == Op.WITHIN) "($col >= ?)" else "($col < ?)" }
            }
        }
    }

    // ── JSON ──────────────────────────────────────────────────────────────────────────────

    /** [raw]: values as they are being typed ("3." on the way to "3.5"). */
    fun toJson(raw: Boolean = false): String = toJsonObject(raw).toString()

    fun toJsonObject(raw: Boolean = false): JsonObject = buildJsonObject {
        put("match", if (all) "all" else "any")
        put("rules", list(rules, raw))
        put("exclude", list(exclude, raw))
        put("excludeIds", buildJsonArray { excludeIds.forEach { add(JsonPrimitive(it)) } })
        put("order", order.key)
        put("desc", descending)
        put("limit", limit?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    private fun list(l: List<Rule>, raw: Boolean) = buildJsonArray {
        l.forEach { r ->
            add(buildJsonObject {
                put("field", r.field.key)
                put("op", r.op.key)
                val n = r.number()
                put(
                    "value",
                    when {
                        !r.op.needsValue -> JsonNull
                        raw || r.field.kind == Kind.TEXT || n == null -> JsonPrimitive(r.value)
                        n % 1.0 == 0.0 -> JsonPrimitive(n.toLong())
                        else -> JsonPrimitive(n)
                    },
                )
            })
        }
    }

    companion object {
        /** Beyond, a smart playlist is the library itself: it is not held in memory whole. */
        const val MAX = 10_000
        private const val MAX_EXCLUDED = 800
        const val DAY_MS = 86_400_000L
        private const val P = 2_147_483_647L
        private const val GOLDEN = 0x9e3779b1.toInt()

        /** null: not a description at all. What is not valid in one is left out. */
        fun parse(text: String, keepIncomplete: Boolean = false): SmartRules? =
            runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()?.let { from(it, keepIncomplete) }

        fun from(o: JsonObject, keepIncomplete: Boolean = false): SmartRules {
            fun text(e: JsonElement?) = (e as? JsonPrimitive)?.contentOrNull
            fun rules(e: JsonElement?) = (e as? JsonArray).orEmpty().take(50).mapNotNull { x ->
                val r = x as? JsonObject ?: return@mapNotNull null
                val f = Field.entries.firstOrNull { it.key == text(r["field"]) } ?: return@mapNotNull null
                val op = Op.entries.firstOrNull { it.key == text(r["op"]) }?.takeIf { it in f.ops } ?: return@mapNotNull null
                val value = text(r["value"]).orEmpty().take(200)
                Rule(f, op, if (op.needsValue) (if (keepIncomplete) value else value.trim()) else "").takeIf { keepIncomplete || it.complete }
            }
            return SmartRules(
                all = text(o["match"]) != "any",
                rules = rules(o["rules"]),
                exclude = rules(o["exclude"]),
                excludeIds = (o["excludeIds"] as? JsonArray).orEmpty().mapNotNull(::text),
                order = Order.entries.firstOrNull { it.key == text(o["order"]) } ?: Order.ADDED,
                descending = text(o["desc"]) != "false",
                limit = text(o["limit"])?.toDoubleOrNull()?.toInt()?.takeIf { it >= 1 },
            )
        }

        private val LETTERS = listOf("aàâäáãå", "cç", "eéèêë", "iîïíì", "nñ", "oôöóòõø", "uùûüúů", "yÿý")

        /** A GLOB pattern that ignores case and accents: "electro" finds "Électro" (LIKE does not). */
        fun glob(text: String): String = buildString {
            for (ch in text.lowercase()) {
                val family = LETTERS.firstOrNull { ch in it }
                when {
                    family != null -> append('[').append(family).append(family.uppercase()).append(']')
                    ch == '*' || ch == '?' || ch == '[' -> append('[').append(ch).append(']')
                    ch.uppercaseChar() != ch -> append('[').append(ch).append(ch.uppercaseChar()).append(']')
                    else -> append(ch)
                }
            }
        }

        // ── The ready-made ones, the same as the PC's ─────────────────────────────────────────

        fun neverPlayed() = SmartRules(rules = listOf(Rule(Field.PLAYED, Op.NEVER)))

        /** The longest forgotten first. */
        fun rediscover(days: Int = 182) = SmartRules(rules = listOf(Rule(Field.PLAYED, Op.BEFORE, days.toString())), order = Order.PLAYED, descending = false)
        fun recentlyAdded(days: Int = 30) = SmartRules(rules = listOf(Rule(Field.ADDED, Op.WITHIN, days.toString())))
        fun mostPlayed() = SmartRules(rules = listOf(Rule(Field.PLAYS, Op.GTE, "1")), order = Order.PLAYS, limit = 100)
    }
}
