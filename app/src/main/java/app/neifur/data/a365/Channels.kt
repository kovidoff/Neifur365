package app.neifur.data.a365

import app.neifur.core.AppJson
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * One of Anime 365's video channels ("Канал 1 (CDN Cloudflare)" …). The account picks one, and every video link
 * the site hands out comes from it. From Russia some of them don't get through at all (Hetzner behind "без CDN"
 * freezes after ~20 KB on many networks), and then nothing plays until another channel is chosen.
 */
data class VideoChannel(val id: Int, val name: String, val desc: String, val testUrl: String, val fallbackUrl: String?) {
    /** "Cloudflare", "Без CDN", "Bunny", "РФ": the part people recognise. */
    val label: String get() = desc.trim('(', ')', ' ').removePrefix("CDN ").replaceFirstChar { it.uppercase() }.ifBlank { name }
}

/**
 * The account's channels as the site sees this phone. The site keeps two choices: one for visits from Russia
 * without a VPN and one for everything else, and [fromRussia] says which of them [current] is.
 */
data class ChannelSetup(val channels: List<VideoChannel>, val current: Int?, val fromRussia: Boolean? = null) {
    val currentChannel: VideoChannel? get() = channels.firstOrNull { it.id == current }
}

/** How a channel did in a short download from this phone. */
data class ChannelSpeed(val bytesPerSec: Double) {
    val works: Boolean get() = bytesPerSec >= WORKS
    val label: String get() = when {
        !works -> "не работает"
        bytesPerSec >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f МБ/с", bytesPerSec / 1024 / 1024)
        else -> "${(bytesPerSec / 1024).toInt()} КБ/с"
    }

    companion object {
        /** Below this a 720p stream can't keep up; the blocked channel gives a few KB/s. */
        const val WORKS = 300.0 * 1024
    }
}

/** Reads the channel list from the site's own connection test page (`/diagnostic`). */
object ChannelPage {
    fun parse(html: String): ChannelSetup? {
        val start = html.indexOf("var DIAG = ").takeIf { it >= 0 }?.plus("var DIAG = ".length) ?: return null
        val end = html.indexOf("};", start).takeIf { it > 0 }?.plus(1) ?: return null
        val root = runCatching { AppJson.parseToJsonElement(html.substring(start, end)).jsonObject }.getOrNull() ?: return null
        val channels = root["targets"]?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el.jsonObject
            if (o["reference"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            VideoChannel(
                id = id,
                name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                desc = o["desc"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                testUrl = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                fallbackUrl = o["fallbackUrl"]?.jsonPrimitive?.contentOrNull,
            )
        }
        if (channels.isEmpty()) return null
        return ChannelSetup(channels, root["currentChannel"]?.jsonPrimitive?.intOrNull, root["isRu"]?.jsonPrimitive?.booleanOrNull)
    }
}
