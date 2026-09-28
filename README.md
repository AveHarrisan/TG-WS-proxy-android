# TG WS Proxy для Android

Локальный MTProto-прокси для Telegram на телефоне. Приложение принимает подключение от Telegram на `127.0.0.1` и передаёт трафик до серверов Telegram через WebSocket поверх TLS (`kws*.web.telegram.org`). Там, где прямое подключение к серверам Telegram заблокировано, это помогает Telegram снова подключаться.

Написано целиком на Kotlin, без нативных библиотек.

## Возможности

- Фоновая служба с уведомлением, работает при выключенном экране.
- Кнопка «Подключить в Telegram» открывает ссылку `tg://proxy` и сразу добавляет прокси в Telegram.
- Кнопка в шторке для быстрого включения и выключения. На Android 13 и новее добавляется одним нажатием прямо из приложения.
- Обновления внутри приложения: плашка о новой версии со списком изменений, скачивание и установка поверх без браузера.
- Автозапуск после перезагрузки (включается в настройках).
- Проверка связи: какие способы подключения к Telegram работают в текущей сети.
- Журнал работы с копированием и отправкой.
- Настройки: порт, секрет, IP дата-центров, размер пула соединений, запасной путь через Cloudflare, свои домены и Workers, Fake TLS, доступ из локальной сети, запрет засыпания.
- Светлая и тёмная тема.

Требуется Android 7.0 или новее.

## Установка

1. Скачайте APK на странице [Releases](../../releases) и установите его.
2. Откройте приложение и нажмите «Запустить прокси».
3. Нажмите «Подключить в Telegram» и подтвердите добавление прокси в Telegram.

Если Android завершает приложение в фоне, разрешите ему уведомления и отключите для него экономию батареи.

Подходит любой клиент: Telegram, Telegram X, Nekogram, AyuGram и другие.

Новые версии приложение находит само и предлагает обновиться. При первом обновлении Android попросит разрешить установку из этого источника. Что менялось по версиям — в [CHANGELOG.md](CHANGELOG.md).

## Сборка

Нужны JDK 17 и Android SDK (platform 35).

```bash
./gradlew :core:test          # тесты ядра
./gradlew :app:assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
```

Ядро можно запустить на компьютере без Android:

```bash
./gradlew :core:run --args="--port 1443 --secret <32 hex-символа>"
./gradlew :core:run --args="--probe"   # проверка связи
```

Для подписанной release-сборки положите в `keystore/` файл хранилища и `keystore.properties` с полями `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.

## Поддержать и найти меня

- [Boosty](https://boosty.to/aveharrisan) — разово или подпиской
- [DonationAlerts](https://www.donationalerts.com/r/aveharrisan) — разовый донат без подписки
- [lvl.su](https://lvl.su/) — сайт: гайды и вики по играм
- [Котамарин](https://t.me/kotamarine) — телеграм-канал про игры и раздачи
- [Discord](https://discord.com/invite/XYBvdvfv8t) — вопросы и ошибки
- [AveHarrisan](https://t.me/aveharrisan) — телеграм автора

Другие проекты: [KotaMusic](https://github.com/AveHarrisan/KotaMusic) — мод Яндекс Музыки для компьютера, [USB-of_on](https://github.com/AveHarrisan/USB-of_on) — USB-устройства Windows.

## Лицензия

MIT, см. [LICENSE](LICENSE).

---

Использованы наработки проекта [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) (MIT).
