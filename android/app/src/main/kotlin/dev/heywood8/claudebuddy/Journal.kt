package dev.heywood8.claudebuddy

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.Executors

/**
 * What was decided, and what slipped past.
 *
 * A channel that approves shell commands on a workstation has to be reviewable afterwards, and
 * a one-sided record is no use: if the two ends disagree there is nothing to compare. The
 * bridge keeps its half in ~/Library/Logs/cmbridge.log; this is the phone's.
 *
 * Append-only JSONL rather than a database. Room would need an annotation processor, and KSP
 * is only built against Kotlin 2.3 while AGP 9 brings 2.4 — but the honest reason is that the
 * requirements are append, read recent, drop old, and export. A line-delimited file does all
 * four, and the export format is the file itself.
 */
object Journal {
    /** Requests are recorded even when nobody answered them, so a gap is visible as a gap. */
    @Serializable
    data class Entry(
        val at: Long,
        val id: String,
        val tool: String,
        val hint: String,
        val cwd: String = "",
        val host: String = "",
        /** `once`, `deny`, or `unanswered`. */
        val outcome: String,
        /** `notification`, `app`, or empty when nobody answered. */
        val source: String = "",
    )

    const val RETENTION_DAYS = 30
    private const val FILE = "journal.jsonl"

    // File IO never on the thread that just handled a tap.
    private val io = Executors.newSingleThreadExecutor()

    fun record(context: Context, entry: Entry) {
        val appContext = context.applicationContext
        io.execute {
            runCatching {
                file(appContext).appendText(Wire.json.encodeToString(entry) + "\n")
            }.onFailure { Log.w(TAG, "could not append to the journal", it) }
        }
    }

    /** Newest first. */
    fun entries(context: Context, limit: Int = 200): List<Entry> = runCatching {
        val file = file(context)
        if (!file.isFile) return emptyList()
        file.readLines()
            .asReversed()
            .asSequence()
            .mapNotNull { line ->
                runCatching { Wire.json.decodeFromString<Entry>(line) }.getOrNull()
            }
            .take(limit)
            .toList()
    }.getOrElse {
        Log.w(TAG, "could not read the journal", it)
        emptyList()
    }

    /** The whole file, for export. */
    fun exportText(context: Context): String =
        runCatching { file(context).readText() }.getOrDefault("")

    /**
     * Drops anything past the retention window by rewriting the file.
     *
     * Cheap enough to run at startup: a month of decisions is a few hundred short lines, and
     * rewriting is what keeps this a plain file instead of a database.
     */
    fun prune(context: Context, now: Long = System.currentTimeMillis() / 1000) {
        val appContext = context.applicationContext
        io.execute {
            runCatching {
                val file = file(appContext)
                if (!file.isFile) return@runCatching
                val cutoff = now - RETENTION_DAYS * 24L * 60 * 60
                val kept = file.readLines().filter { line ->
                    val entry = runCatching {
                        Wire.json.decodeFromString<Entry>(line)
                    }.getOrNull()
                    // An unparseable line is dropped rather than kept forever: it cannot be
                    // shown, exported usefully, or aged out on its own.
                    entry != null && entry.at >= cutoff
                }
                val temporary = File(file.parentFile, "$FILE.tmp")
                temporary.writeText(kept.joinToString("") { it + "\n" })
                temporary.renameTo(file)
            }.onFailure { Log.w(TAG, "could not prune the journal", it) }
        }
    }

    fun clear(context: Context) {
        val appContext = context.applicationContext
        io.execute { runCatching { file(appContext).delete() } }
    }

    private fun file(context: Context) = File(context.filesDir, FILE)

    private const val TAG = "Journal"
}
