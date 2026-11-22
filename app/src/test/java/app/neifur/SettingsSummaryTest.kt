package app.neifur

import app.neifur.ui.screens.SettingsSection
import app.neifur.ui.screens.alertsSummary
import app.neifur.ui.screens.connectionSummary
import app.neifur.ui.screens.dataSummary
import app.neifur.ui.screens.playerSummary
import app.neifur.ui.screens.translationSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Live category summaries on the Settings hub and route lookup for subpages. */
class SettingsSummaryTest {
    @Test fun sectionsResolveByRoute() {
        SettingsSection.entries.forEach { assertEquals(it, SettingsSection.of(it.route)) }
        assertNull(SettingsSection.of("unknown"))
        assertNull(SettingsSection.of(null))
    }

    @Test fun playerAndTranslationSummaries() {
        assertEquals("1080p · ±10 с · 2× удержание", playerSummary(1080, 10, holdBoost = true))
        assertEquals("720p · ±5 с", playerSummary(720, 5, holdBoost = false))
        assertEquals("Озвучка", translationSummary("VoiceRu", 0))
        assertEquals("Субтитры · 1 любимая", translationSummary("SubRu", 1))
        assertEquals("Оригинал · 3 любимые", translationSummary("Raw", 3))
    }

    @Test fun alertsAndConnectionSummariesFlagWarnings() {
        assertEquals("Нужен вход в аккаунт" to false, alertsSummary(signedIn = false, enabled = true, permitted = true, myStudio = true))
        assertEquals("Выключены" to false, alertsSummary(signedIn = true, enabled = false, permitted = true, myStudio = true))
        assertEquals("Нет разрешения системы" to true, alertsSummary(signedIn = true, enabled = true, permitted = false, myStudio = true))
        assertEquals("Включены · моя студия" to false, alertsSummary(signedIn = true, enabled = true, permitted = true, myStudio = true))
        assertEquals("Включены · любой перевод" to false, alertsSummary(signedIn = true, enabled = true, permitted = true, myStudio = false))

        assertEquals("Нет рабочих адресов" to true, connectionSummary(null, anyMirrorWorking = false, channelLabel = null))
        assertEquals("smotret-anime.app · CDN" to false, connectionSummary("smotret-anime.app", anyMirrorWorking = true, channelLabel = "CDN"))
        assertEquals("smotret-anime.app" to false, connectionSummary("smotret-anime.app", anyMirrorWorking = true, channelLabel = null))
        assertEquals("Адреса, каналы и ссылки" to false, connectionSummary(null, anyMirrorWorking = null, channelLabel = null))
    }

    @Test fun dataSummaryFormatsCacheAndHistory() {
        assertEquals("История пуста", dataSummary(cacheBytes = null, titleCount = 0))
        assertEquals("Кэш < 1 МБ", dataSummary(cacheBytes = 500_000L, titleCount = 0))
        assertEquals("Кэш 12 МБ · 5 тайтлов", dataSummary(cacheBytes = 12L shl 20, titleCount = 5))
        assertEquals("Загрузки 450 МБ · кэш 12 МБ · 2 тайтла", dataSummary(cacheBytes = 12L shl 20, titleCount = 2, downloadBytes = 450L shl 20))
    }
}
