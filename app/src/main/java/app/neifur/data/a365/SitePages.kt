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
        // The "Подписка" card: "Не оплачена." without one, the paid period ("Оплачена до 01.03.2027.") otherwise.
        val subCard = doc.select("h2").firstOrNull { it.text().trim() == "Подписка" }?.parent()?.text()
        val subscribed = subCard?.let { !it.contains("Не оплачена", ignoreCase = true) }
        val subscribedUntil = if (subscribed == true) parseSubscriptionUntil(subCard) else null
        return Profile(id, name, avatar, subscribed, subscribedUntil)
    }

    private val RU_MONTHS = mapOf(
        "янв" to 1, "фев" to 2, "мар" to 3, "апр" to 4, "май" to 5, "мая" to 5, "июн" to 6,
        "июл" to 7, "авг" to 8, "сен" to 9, "окт" to 10, "ноя" to 11, "дек" to 12,
    )

    /** Extracts a subscription end date from the profile card text and normalises it to ISO `yyyy-MM-dd`. */
    internal fun parseSubscriptionUntil(text: String): String? {
        Regex("""(\d{1,2})\.(\d{1,2})\.(\d{4})""").find(text)?.let { m ->
            val (d, mo, y) = m.destructured
            return runCatching { java.time.LocalDate.of(y.toInt(), mo.toInt(), d.toInt()).toString() }.getOrNull()
        }
        Regex("""(\d{4})-(\d{2})-(\d{2})""").find(text)?.let { m ->
            return runCatching { java.time.LocalDate.parse(m.value).toString() }.getOrNull()
        }
        Regex("""(\d{1,2})\s+([а-яА-ЯёЁ]{3,})\.?\s+(\d{4})""").find(text)?.let { m ->
            val (d, mon, y) = m.destructured
            val month = RU_MONTHS[mon.lowercase().take(3)] ?: return null
            return runCatching { java.time.LocalDate.of(y.toInt(), month, d.toInt()).toString() }.getOrNull()
        }
        return null
    }

    /** `/catalog/<slug>-<seriesId>` (with or without an episode segment) → the series id. */
    fun seriesId(href: String): Long? {
        val parts = href.substringBefore('?').substringBefore('#').trim('/').split('/')
        val i = parts.indexOf("catalog").takeIf { it >= 0 } ?: return null
        return parts.getOrNull(i + 1)?.substringAfterLast('-')?.toLongOrNull()
    }

    /** `/catalog/<slug>-<seriesId>/<slug>-<episodeId>` → the two ids. */
    fun ids(href: String): Pair<Long, Long>? {
        val parts = href.substringBefore('?').substringBefore('#').trim('/').split('/')
        val i = parts.indexOf("catalog").takeIf { it >= 0 } ?: return null
        val series = parts.getOrNull(i + 1)?.substringAfterLast('-')?.toLongOrNull() ?: return null
        val episode = parts.getOrNull(i + 2)?.substringAfterLast('-')?.toLongOrNull() ?: return null
        return series to episode
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
        val comment = doc.selectFirst("textarea#UsersRates_comment, textarea[name='UsersRates[comment]'], input[name='UsersRates[comment]']")
            ?.let { if (it.tagName().equals("textarea", true)) it.text() else it.attr("value") }
            .orEmpty()
        return ListRecord(status, watched, score, comment)
    }

    fun resolve(base: String, url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        else -> base.trimEnd('/') + "/" + url.trimStart('/')
    }
}
