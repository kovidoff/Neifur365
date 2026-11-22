<p align="center">
  <img src="promo/banner.png" alt="Neifur 365" width="100%" />
</p>

<h1 align="center">Neifur 365</h1>

<p align="center">
  Неофициальный Android-клиент для <strong>Anime 365</strong> и <strong>Shikimori</strong>.<br/>
  Написан на Kotlin + Jetpack Compose с плеером на базе <code>libmpv</code>. Без рекламы, аналитики и собственных серверов.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-8.0%2B%20(arm64--v8a)-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
  <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin + Compose" />
  <img src="https://img.shields.io/badge/Player-libmpv-8B5CF6?style=flat-square" alt="libmpv" />
  <img src="https://img.shields.io/badge/License-GPL--3.0-blue?style=flat-square" alt="GPL-3.0" />
</p>

---

## Возможности

- **Плеер на `libmpv`** — качество до 1080p, переключение озвучек и субтитров (`.ass`) прямо в плеере, скорость воспроизведения, режимы кадра, жесты яркости/громкости с запоминанием яркости, пропуск опенинга (`+85 с`), таймер сна (по минутам или до конца серии), картинка в картинке (PiP), управление с наушников и шторки уведомлений.
- **Любимые студии и умный выбор перевода** — запоминает выбранную студию для каждого тайтла и поднимает избранные озвучки в начало списка.
- **Офлайн-просмотр** — фоновая загрузка серий (по одной или пакетом `+3 серии`) вместе с субтитрами, выбор качества для скачивания, автоудаление просмотренных серий и отдельный блок «Доступно офлайн» при отсутствии сети.
- **Каталог и расписание онгоингов** — поиск и фильтры по типу, статусу, жанру и году, отметки тайтлов из ваших списков прямо на постерах, расписание выхода серий на неделю по вашему часовому поясу с фильтром «Мои».
- **Синхронизация списков** — «Смотрю», «Запланировано», «Просмотрено», «Отложено» и «Брошено» синхронизируются с аккаунтом Anime 365; встроен поиск, сортировка и редактирование по долгому нажатию.
- **Уведомления о новых сериях** — фоновая проверка выхода серий по расписанию (можно ждать именно выбранную озвучку).
- **Устойчивая сеть и ссылки** — автоматический подбор рабочего зеркала и самого быстрого видеоканала, открытие ссылок `anime365.ru` / `smotret-anime.*` прямо в приложении.

---

## Скриншоты

<table>
  <tr>
    <td align="center" width="33%">
      <img src="promo/banner/shots/home.png" alt="Главная" />
      <br/><sub>Главная</sub>
    </td>
    <td align="center" width="33%">
      <img src="promo/banner/shots/detail.png" alt="Карточка тайтла" />
      <br/><sub>Карточка аниме</sub>
    </td>
    <td align="center" width="33%">
      <img src="promo/banner/shots/player.png" alt="Плеер" />
      <br/><sub>Плеер на libmpv</sub>
    </td>
  </tr>
</table>

---

## Приватность и безопасность

У Neifur 365 **нет собственного бэкенда, телеметрии, рекламы и трекеров**. Исходный код открыт, чтобы любой желающий мог проверить, куда именно уходят запросы:

| Куда идут запросы | Зачем | Где в коде |
| --- | --- | --- |
| **Anime 365** (`smotret-anime.*` / `anime365.ru`) | Вход в аккаунт, списки, каталог, ссылки на серии и субтитры | [`Anime365.kt`](app/src/main/java/app/neifur/data/a365/Anime365.kt), [`SitePages.kt`](app/src/main/java/app/neifur/data/a365/SitePages.kt) |
| **Shikimori** (`shikimori.one` / `shikimori.io`) | Описания, оценки, персонажи, кадры, расписание онгоингов | [`Shikimori.kt`](app/src/main/java/app/neifur/data/shikimori/Shikimori.kt) |
| **YouTube** (только при открытии трейлера / превью) | Превью и переход к трейлерам тайтла | [`DetailScreen.kt`](app/src/main/java/app/neifur/ui/screens/DetailScreen.kt) |

- Логин и пароль отправляются **напрямую** на Anime 365 и не сохраняются на устройстве (хранятся только session cookies в приватном `SharedPreferences` приложения).
- История просмотра, настройки и скачанные серии хранятся локально на телефоне.

---

## Сборка из исходников

### Требования
- Android Studio (или Android SDK с `compileSdk 36`)
- JDK 17
- Устройство на Android 8.0+ (`arm64-v8a`)

### Команды

```bash
# Прогон юнит-тестов
./gradlew testDebugUnitTest

# Сборка debug APK (устанавливается рядом с релизной как «Neifur 365 Dev»)
./gradlew assembleDebug

# Сборка release APK (с R8-оптимизацией)
./gradlew assembleRelease
```

> По умолчанию `assembleRelease` подпишется debug-ключом, если не задан собственный keystore. Чтобы подписать релиз своим ключом, укажите в `local.properties` (файл в `.gitignore`):
>
> ```properties
> release.storeFile=/path/to/keystore.jks
> release.storePassword=...
> release.keyAlias=...
> ```

---

## Структура проекта

```text
app/src/main/java/app/neifur/
├── core/        — HTTP-клиент (OkHttp), JSON, обработка ошибок
├── data/
│   ├── a365/    — клиент Anime 365, зеркала, каналы, списки, студии
│   └── shikimori/ — GraphQL-клиент Shikimori и расписание
├── downloads/   — менеджер офлайн-загрузок и Foreground Service
├── alerts/      — фоновая проверка новых серий (WorkManager)
├── player/      — интеграция libmpv, медиа-сессия, PiP, UI плеера
└── ui/          — тема, общие компоненты и экраны Compose
```

---

## Дисклеймер и лицензия

- **Neifur 365** — независимый неофициальный клиент. Проект не связан с администрацией **Anime 365** или **Shikimori**, не хранит и не распространяет видеоматериалы.
- Для воспроизведения видео требуется собственный аккаунт Anime 365 с действующей подпиской (без неё сервис не отдаёт видеопотоки).
- Исходный код распространяется по лицензии **[GNU General Public License v3.0](LICENSE)**.
