package app.neifur.data

import android.content.Context
import app.neifur.core.AppJson
import app.neifur.data.a365.ListedTitle
import app.neifur.data.shikimori.Anime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class CachedLists(val updatedAt: Long, val titles: List<ListedTitle>)

@Serializable
data class CachedSchedule(val updatedAt: Long, val animes: List<Anime>)

/**
 * Lightweight atomic JSON snapshots for offline browsing (user's lists per account and weekly schedule).
 * Kept outside DataStore Preferences so large lists don't bloat every preference edit.
 */
class OfflineCache(context: Context) {
    private val dir = File(context.filesDir, "offline_cache").apply { mkdirs() }

    private fun listsFile(userId: Long) = File(dir, "lists_$userId.json")
    private fun scheduleFile() = File(dir, "schedule.json")

    suspend fun readLists(userId: Long): CachedLists? = withContext(Dispatchers.IO) {
        val f = listsFile(userId)
        if (!f.exists()) return@withContext null
        runCatching { AppJson.decodeFromString(CachedLists.serializer(), f.readText()) }.getOrNull()
    }

    suspend fun writeLists(userId: Long, titles: Map<Long, ListedTitle>, updatedAt: Long) = withContext(Dispatchers.IO) {
        runCatching {
            // Strip bulky descriptions/episode arrays so the offline snapshot stays compact and fast to parse.
            val compact = titles.values.map { t ->
                t.copy(series = t.series?.copy(descriptions = null, episodes = null))
            }
            val payload = AppJson.encodeToString(CachedLists.serializer(), CachedLists(updatedAt, compact))
            atomicWrite(listsFile(userId), payload)
        }
    }

    suspend fun readSchedule(): CachedSchedule? = withContext(Dispatchers.IO) {
        val f = scheduleFile()
        if (!f.exists()) return@withContext null
        runCatching { AppJson.decodeFromString(CachedSchedule.serializer(), f.readText()) }.getOrNull()
    }

    suspend fun writeSchedule(animes: List<Anime>, updatedAt: Long) = withContext(Dispatchers.IO) {
        runCatching {
            val payload = AppJson.encodeToString(CachedSchedule.serializer(), CachedSchedule(updatedAt, animes))
            atomicWrite(scheduleFile(), payload)
        }
    }

    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            target.writeText(content)
            tmp.delete()
        }
    }
}
