package app.neifur.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.neifur.Container
import app.neifur.MainActivity
import app.neifur.R
import app.neifur.container
import app.neifur.data.a365.ListEntry
import app.neifur.data.a365.ListStatus
import app.neifur.data.a365.Picker
import app.neifur.data.a365.Series
import app.neifur.data.a365.Translation
import app.neifur.data.a365.TranslationKind
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal fun shouldCheckSeries(entry: ListEntry, card: Series?, knownSeen: Int?): Boolean {
    if (card?.airing == true) return true
    if (knownSeen == null) return false
    val total = card?.numberOfEpisodes?.takeIf { it > 0 } ?: entry.total?.takeIf { it > 0 } ?: return false
    return knownSeen < total && entry.watched < total
}

/** A new episode (or several) of a followed title, ready to be shown. */
data class EpisodeAlert(
    val series: Series,
    val numbers: List<Int>,
    /** The translation to name: the viewer's own studio when it has the episode, otherwise what would play. */
    val translation: Translation?,
    /** The viewer chose a studio for this title and it hasn't put the episode out yet. */
    val waitingFor: String?,
)

/**
 * New-episode notifications. About once an hour (with a network) the viewer's "Смотрю" on Anime 365 is checked:
 * a title whose newest episode is past the last one we told about, and past what the viewer has watched, gets a
 * notification with its poster, the episode and the studio. The first look at a title only remembers where it is,
 * so turning this on never floods the shade with episodes the viewer already knows about.
 */
object EpisodeAlerts {
    const val CHANNEL = "new_episodes"
    private const val GROUP = "app.neifur.NEW_EPISODES"
    private const val SUMMARY_ID = 365
    private const val WORK = "new-episodes"
    private const val WORK_NOW = "new-episodes-now"
    const val EXTRA_SERIES = "open_series"
    private val checkMutex = Mutex()

    private fun notifId(seriesId: Long): Int = 10_000 + (seriesId % 1_000_000L).toInt()

    fun schedule(ctx: Context, on: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (!on) { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<NewEpisodesWorker>(1, TimeUnit.HOURS, 20, TimeUnit.MINUTES)
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** A check right away: on turning notifications on (it sets where each title is) and from Settings. */
    fun checkNow(ctx: Context, demo: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<NewEpisodesWorker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setInputData(workDataOf("demo" to demo))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.REPLACE, req)
    }

    fun permitted(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Новые серии", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Когда у тайтлов из «Смотрю» выходит серия"
            }
        )
    }

    /**
     * Looks for new episodes. [demo] (debug builds) pretends the newest episode of every followed title is new,
     * to see the notifications without waiting for a release.
     */
    suspend fun check(c: Container, demo: Boolean = false): List<EpisodeAlert> = checkMutex.withLock {
        val profile = c.a365.profile() ?: return@withLock emptyList()
        val planned = c.prefs.alertsPlanned.first()
        val myStudio = c.prefs.alertsMyStudio.first()
        val preferred = runCatching { TranslationKind.valueOf(c.prefs.preferredKind.first()) }.getOrDefault(TranslationKind.VoiceRu)
        val studios = c.prefs.favouriteStudios.first().toMap()
        val favourites = studios.keys.toList()
        val entries = c.a365.allLists(profile.id)
            .filter { it.status == ListStatus.Watching || (planned && it.status == ListStatus.Planned) }
        if (entries.isEmpty()) return@withLock emptyList()
        // One request for the cards tells which titles still air (or recently finished before our last seen mark).
        val cards = c.a365.cards(entries.map { it.seriesId }).associateBy { it.id }
        val seen = ConcurrentHashMap(c.prefs.alertsSeen())
        val gate = Semaphore(3)
        val alerts = coroutineScope {
            entries.filter { demo || shouldCheckSeries(it, cards[it.seriesId], seen[it.seriesId]) }.map { e ->
                async {
                    gate.withPermit {
                        runCatching {
                            val s = c.a365.series(e.seriesId, force = true)
                            val eps = s.playable.filter { it.number > 0 }
                            val newest = eps.maxByOrNull { it.number } ?: return@runCatching null
                            val known = if (demo) newest.number - 1 else seen[e.seriesId]
                            if (known == null) { seen[e.seriesId] = newest.number; return@runCatching null }
                            val freshEps = eps.filter { it.number > known && it.number > e.watched }.sortedBy { it.number }
                            if (freshEps.isEmpty()) {
                                seen[e.seriesId] = maxOf(known, newest.number)
                                return@runCatching null
                            }

                            val pick = c.prefs.pick(e.seriesId.toString())
                            if (!myStudio) {
                                val trs = c.a365.translations(listOf(newest.id)).filter { it.shown }
                                if (trs.isEmpty()) return@runCatching null
                                val mine = pick?.let { k -> Picker.best(trs.filter { it.pickKey == k }, 1080) }
                                seen[e.seriesId] = newest.number
                                val shown = mine ?: Picker.choose(trs, pick, preferred, favourites, 1080)
                                val waiting = if (pick != null && mine == null) studios[pick] ?: pick.substringAfter('|').replaceFirstChar { it.uppercase() } else null
                                EpisodeAlert(s, freshEps.map { it.number }.distinct().sorted(), shown, waiting)
                            } else {
                                val candidates = freshEps.takeLast(5)
                                val allTrs = c.a365.translations(candidates.map { it.id }).filter { it.shown }.groupBy { it.episodeId }
                                val readyPairs = candidates.mapNotNull { ep ->
                                    val epTrs = allTrs[ep.id].orEmpty()
                                    val chosen = if (pick != null) {
                                        Picker.best(epTrs.filter { it.pickKey == pick }, 1080)
                                    } else {
                                        Picker.best(epTrs.filter { it.kind == preferred }, 1080)
                                    }
                                    chosen?.let { ep to it }
                                }
                                // Not out the way the viewer watches yet: ask again next time, without moving the mark.
                                if (readyPairs.isEmpty()) return@runCatching null
                                val readyMax = readyPairs.maxOf { it.first.number }
                                seen[e.seriesId] = maxOf(known, readyMax)
                                val nums = readyPairs.map { it.first.number }.distinct().sorted()
                                val shown = readyPairs.maxBy { it.first.number }.second
                                EpisodeAlert(s, nums, shown, null)
                            }
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
        // Titles that left the followed lists stop being remembered.
        val followed = entries.map { it.seriesId }.toSet()
        c.prefs.saveAlertsSeen(seen.filterKeys { it in followed }, System.currentTimeMillis())
        alerts
    }

    /** "Вышла 7 серия", "Вышли 6 и 7 серии", "Вышли серии 5–8". */
    fun headline(numbers: List<Int>): String = when {
        numbers.size == 1 -> "Вышла ${numbers[0]} серия"
        numbers.size == 2 -> "Вышли ${numbers[0]} и ${numbers[1]} серии"
        else -> "Вышли серии ${numbers.first()}–${numbers.last()}"
    }

    fun detail(a: EpisodeAlert): String {
        val t = a.translation
        val where = t?.let { if (it.author.isBlank()) it.kind.short else "${it.author}, ${it.kind.inline}" }
        return when {
            a.waitingFor != null && where != null -> "У ${a.waitingFor} пока нет, есть: $where"
            where != null -> where
            else -> "Уже на Anime 365"
        }
    }

    suspend fun show(ctx: Context, alerts: List<EpisodeAlert>) {
        if (alerts.isEmpty() || !permitted(ctx)) return
        ensureChannel(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        alerts.forEach { a ->
            val id = a.series.id
            val text = detail(a)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_new_episode)
                .setColor(0xFF5CF2B0.toInt())
                .setContentTitle(a.series.title)
                .setContentText("${headline(a.numbers)} — $text")
                .setStyle(NotificationCompat.BigTextStyle().bigText("${headline(a.numbers)}\n$text"))
                .setLargeIcon(poster(ctx, a.series.posterUrl ?: a.series.posterUrlSmall))
                .setContentIntent(openTitle(ctx, id))
                .setAutoCancel(true)
                .setGroup(GROUP)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .build()
            post(ctx, nm, notifId(id), n)
        }
        // Several titles at once fold into one group under a summary.
        val active = (ctx.getSystemService(NotificationManager::class.java).activeNotifications
            .count { it.notification.group == GROUP && it.id != SUMMARY_ID })
        if (active >= 2) {
            val summary = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_new_episode)
                .setColor(0xFF5CF2B0.toInt())
                .setContentTitle("Новые серии")
                .setStyle(NotificationCompat.InboxStyle().also { s -> alerts.forEach { s.addLine("${it.series.title} — ${headline(it.numbers).lowercase()}") } })
                .setGroup(GROUP).setGroupSummary(true).setAutoCancel(true)
                .build()
            post(ctx, nm, SUMMARY_ID, summary)
        }
    }

    /** Posts only while Android allows it (the viewer can revoke the permission at any time). */
    @android.annotation.SuppressLint("MissingPermission") // checked right here, through permitted()
    private fun post(ctx: Context, nm: NotificationManagerCompat, id: Int, n: android.app.Notification) {
        if (permitted(ctx)) runCatching { nm.notify(id, n) }
    }

    private fun openTitle(ctx: Context, seriesId: Long): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .putExtra(EXTRA_SERIES, seriesId.toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(ctx, notifId(seriesId), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The poster, cropped to its middle square (the shade shows large icons square or round). */
    private suspend fun poster(ctx: Context, url: String?): Bitmap? {
        url ?: return null
        val req = ImageRequest.Builder(ctx).data(url).allowHardware(false).build()
        val bmp = (SingletonImageLoader.get(ctx).execute(req) as? SuccessResult)?.image?.toBitmap() ?: return null
        val side = minOf(bmp.width, bmp.height)
        val top = ((bmp.height - side) * 0.3f).toInt()
        return runCatching {
            Bitmap.createScaledBitmap(Bitmap.createBitmap(bmp, (bmp.width - side) / 2, top, side, side), 256, 256, true)
        }.getOrNull()
    }
}

class NewEpisodesWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val demo = inputData.getBoolean("demo", false)
        if (!c.prefs.alerts.first() && !demo) return Result.success()
        return runCatching { EpisodeAlerts.show(applicationContext, EpisodeAlerts.check(c, demo)) }
            .fold({ Result.success() }, { if (runAttemptCount < 2) Result.retry() else Result.success() })
    }
}
