package app.neifur.data.a365

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Parsers for the Anime 365 pages that have no JSON counterpart. Pure functions over HTML,
 * so they are tested against saved pages and fail soft (empty result) when the markup shifts.
 */
internal object SitePages {
    /** Any page the site serves to a signed-out visitor carries the login form. */
    fun isSignedOut(html: String): Boolean =
        html.contains("Вход по паролю") || html.contains("Вход или регистрация") || html.contains("Вход - Anime 365")

    fun loginFailed(html: String): Boolean = html.contains("Неверный E-mail или пароль")

    fun profile(html: String, base: String): Profile? {
        if (isSignedOut(html)) return null
        val id = Regex("""ID аккаунта:\s*(\d+)""").find(html)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val doc = Jsoup.parse(html, base)
        val name = doc.selectFirst("content .m-small-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst(".m-small-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Профиль"
        val avatar = (doc.selectFirst("content .card-image.hide-on-small-and-down img") ?: doc.selectFirst(".card-image img"))
            ?.absUrl("src")?.takeIf { it.isNotEmpty() }
        return Profile(id, name, avatar)
    }

    /** `/catalog/<slug>-<seriesId>/<slug>-<episodeId>` → the two ids. */
    fun ids(href: String): Pair<Long, Long>? {
        val parts = href.substringBefore('?').trim('/').split('/')
        val i = parts.indexOf("catalog").takeIf { it >= 0 } ?: return null
        val series = parts.getOrNull(i + 1)?.substringAfterLast('-')?.toLongOrNull() ?: return null
        val episode = parts.getOrNull(i + 2)?.substringAfterLast('-')?.toLongOrNull() ?: return null
        return series to episode
    }

    fun newEpisodes(html: String, base: String): List<NewEpisode> {
        val doc = Jsoup.parse(html, base)
        return doc.select("#m-index-personal-episodes div.m-new-episode").mapNotNull { el ->
            val href = el.selectFirst("a[href]")?.absUrl("href") ?: return@mapNotNull null
            val (series, episode) = ids(href) ?: return@mapNotNull null
            val style = el.selectFirst("div.circle[style]")?.attr("style").orEmpty()
            val poster = Regex("""url\('?(.+?)'?\)""").find(style)?.groupValues?.get(1)
                ?.replace(".140x140.1", "")?.let { resolve(base, it) }
            val title = el.selectFirst("h5.line-1 a")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val label = el.selectFirst("span.online-h")?.text()?.trim().orEmpty()
            val note = Regex("""\((.+?)\)""").find(el.selectFirst("span.title")?.text().orEmpty())?.groupValues?.get(1)?.trim()
            NewEpisode(series, episode, title, poster, label, note)
        }
    }

    /**
     * Rows of a list page. The "all" page (`/users/<id>/list`) holds every list, one card per status
     * (`data-status`), so one request brings in everything.
     */
    fun list(html: String): List<ListEntry> {
        val doc = Jsoup.parse(html)
        return doc.select("tr.m-animelist-item").mapNotNull { el ->
            val status = el.parents().firstOrNull { it.hasAttr("data-status") }?.attr("data-status")?.toIntOrNull()?.let { ListStatus.of(it) }
            val id = el.attr("data-id").toLongOrNull() ?: return@mapNotNull null
            val title = el.selectFirst("a[href]")?.text()?.trim().orEmpty()
            val eps = el.selectFirst("td[data-name=episodes]")?.text()?.trim().orEmpty().split("/").map { it.trim() }
            val watched = eps.getOrNull(0)?.toIntOrNull() ?: 0
            val total = eps.getOrNull(1)?.toIntOrNull()
            val score = el.selectFirst("td[data-name=score]")?.text()?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            ListEntry(id, title, watched, total, score, status)
        }
    }

    fun listRecord(html: String): ListRecord {
        if (html.contains("Добавить в список")) return ListRecord(null, 0, null)
        val doc: Document = Jsoup.parse(html)
        val watched = doc.selectFirst("input#UsersRates_episodes")?.attr("value")?.toIntOrNull() ?: 0
        val status = doc.selectFirst("select#UsersRates_status option[selected]")?.attr("value")?.toIntOrNull()?.let { ListStatus.of(it) }
        val score = doc.selectFirst("#UsersRates_score option[selected]")?.attr("value")?.toIntOrNull()?.takeIf { it > 0 }
        return ListRecord(status, watched, score)
    }

    fun resolve(base: String, url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        else -> base.trimEnd('/') + "/" + url.trimStart('/')
    }
}
