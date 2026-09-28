<div align="center">

# TG WS Proxy для Android

**By Harrisan**

Локальный MTProto-прокси для Telegram на телефоне: Telegram подключается
к `127.0.0.1`, а приложение передаёт трафик к серверам Telegram через
WebSocket поверх TLS. Помогает там, где прямое подключение заблокировано.

[![Discord](https://img.shields.io/badge/Discord-Сервер-5865F2?style=flat-square&logo=discord&logoColor=white)](https://discord.com/invite/XYBvdvfv8t)
[![Сайт](https://img.shields.io/badge/Сайт-lvl.su-ff5c5c?style=flat-square)](https://lvl.su/)
[![Телеграм](https://img.shields.io/badge/Телеграм-Котамарин-229ED9?style=flat-square&logo=telegram&logoColor=white)](https://t.me/kotamarine)
[![Автор](https://img.shields.io/badge/Автор-AveHarrisan-229ED9?style=flat-square&logo=telegram&logoColor=white)](https://t.me/aveharrisan)

### Скачать

[![Android](https://img.shields.io/badge/Скачать_для-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/AveHarrisan/TG-WS-proxy-android/releases/latest/download/TG-WS-Proxy.apk)

[![Версия](https://img.shields.io/github/v/release/AveHarrisan/TG-WS-proxy-android?style=flat-square&label=Версия)](https://github.com/AveHarrisan/TG-WS-proxy-android/releases/latest)
[![Загрузок](https://img.shields.io/github/downloads/AveHarrisan/TG-WS-proxy-android/total?style=flat-square&label=Загрузок)](https://github.com/AveHarrisan/TG-WS-proxy-android/releases)
[![Все файлы](https://img.shields.io/badge/Все_файлы-релизы-lightgrey?style=flat-square)](https://github.com/AveHarrisan/TG-WS-proxy-android/releases)

### Поддержать и найти меня

Проект делается в свободное время. Если он вам пригодился:

<table>
<tr>
<td align="center" width="120">
<a href="https://boosty.to/aveharrisan">
<img src="docs/images/links/boosty.png" width="72" height="72" alt="Boosty"><br>
<b>Boosty</b>
</a><br>
<sub>разово или подпиской</sub>
</td>
<td align="center" width="120">
<a href="https://www.donationalerts.com/r/aveharrisan">
<img src="https://img.shields.io/badge/DA-%20-F57D07?style=for-the-badge&logo=donationalerts&logoColor=white" height="72" alt="DonationAlerts"><br>
<b>DonationAlerts</b>
</a><br>
<sub>разовый донат<br>без подписки</sub>
</td>
<td align="center" width="120">
<a href="https://lvl.su/">
<img src="docs/images/links/lvl.png" width="72" height="72" alt="lvl.su"><br>
<b>lvl.su</b>
</a><br>
<sub>гайды и вики</sub>
</td>
<td align="center" width="120">
<a href="https://t.me/kotamarine">
<img src="docs/images/links/kotamarine.png" width="72" height="72" alt="Котамарин"><br>
<b>Котамарин</b>
</a><br>
<sub>канал про игры<br>и раздачи</sub>
</td>
<td align="center" width="120">
<a href="https://discord.com/invite/XYBvdvfv8t">
<img src="docs/images/links/discord.png" width="72" height="72" alt="Discord"><br>
<b>Discord</b>
</a><br>
<sub>вопросы и ошибки</sub>
</td>
<td align="center" width="120">
<a href="https://t.me/aveharrisan">
<img src="docs/images/links/aveharrisan.png" width="72" height="72" alt="AveHarrisan"><br>
<b>AveHarrisan</b>
</a><br>
<sub>телеграм<br>автора</sub>
</td>
</tr>
</table>

</div>

---

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

1. Скачайте [APK последней версии](https://github.com/AveHarrisan/TG-WS-proxy-android/releases/latest/download/TG-WS-Proxy.apk) и установите его.
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

## Другие проекты

- **[KotaMusic](https://github.com/AveHarrisan/KotaMusic)** — мод Яндекс Музыки для компьютера
- **[USB-of_on](https://github.com/AveHarrisan/USB-of_on)** — USB-устройства Windows: имена, скрытие, заряд

## Поддержать

Ссылки — [в начале страницы](#поддержать-и-найти-меня): **Boosty** для разовой
или регулярной поддержки и **DonationAlerts** для разового доната. Кнопка
«Sponsor» в правой колонке репозитория ведёт туда же.

## Лицензия

Все права защищены, см. [LICENSE](LICENSE). Код открыт для просмотра, но копировать, изменять и распространять его без разрешения автора нельзя.

