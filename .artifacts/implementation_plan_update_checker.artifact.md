# Автообновление приложения через GitHub Release

## Проблема

Нет механизма информирования пользователя о новой версии приложения. Нужно показывать баннер на главном экране с возможностью скачать и установить новый APK из GitHub Release.

## Изменения по компонентам

### 1. Prefs.kt — настройки обновлений

Добавить поля:
- `updateLastCheck: Long` — время последней проверки (epoch ms)
- `updateAvailable: Boolean` — есть ли новая версия
- `updateVersion: String` — версия из тега (например, "1.0.5")
- `updateDownloadUrl: String` — ссылка на APK из release assets
- `updateFileName: String` — имя файла
- `updateFileSize: Long` — размер в байтах
- `updateNotes: String` — release notes (опционально)

Методы: `shouldCheckUpdates()` — true, если прошло > 24 часов с последней проверки.

### 2. UpdateChecker.kt — проверка версии

Объект/синглтон:
- `checkForUpdates(context)` — GET `https://api.github.com/repos/hl2mp/nodeway-vpn/releases/latest`
- Парсит `tag_name`, `assets[].browser_download_url`, `assets[].name`, `assets[].size`, `body`
- Сравнивает `tag_name` (без префикса `v`) с `BuildConfig.VERSION_NAME`
- Если новая версия — сохраняет в Prefs и возвращает true
- Кеширует результат на 24 часа

### 3. ApkDownloader.kt — скачивание APK

- Скачивает APK в `context.cacheDir` через `HttpURLConnection`
- Callback прогресса (проценты)
- Поддержка отмены (volatile флаг)
- Возвращает File или null при ошибке/отмене

### 4. AndroidManifest.xml

- Добавить `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />` (требуется для установки APK из приложения)
- Добавить `<provider android:name="androidx.core.content.FileProvider" ...>` с `authorities="com.nodetun.vpn.fileprovider"` и `grantUriPermissions="true"`
- XML ресурс `file_paths.xml` для `cache-path`

### 5. MainActivity.kt

- Вызвать `UpdateChecker.checkForUpdates()` в `onCreate()` (если `shouldCheckUpdates()`)
- При наличии обновления — отправить в WebView `window.onUpdateAvailable(version, url, fileName, fileSize)`
- Метод `startUpdate(version, url, fileName)` — запуск скачивания в фоне с прогрессом
- Метод `installApk(file)` — `Intent.ACTION_VIEW` с `FileProvider.getUriForFile()` и `FLAG_GRANT_READ_URI_PERMISSION`
- Обработка отмены загрузки

### 6. WebView UI — index.html + theme-m3.html

- CSS-баннер `.update-banner` (фиксированный сверху, цвет акцента, скрыт по умолчанию `display: none`)
- HTML-структура: версия, размер, имя файла, кнопки "Обновить" / "Закрыть"
- При скачивании: баннер остаётся, появляется прогресс-бар, кнопка "Отмена", кнопка "Обновить" меняется на "Установить" (активна после скачивания)
- JS-функции: `window.onUpdateAvailable(...)`, `window.onDownloadProgress(percent)`, `window.onDownloadComplete()`
- Кнопка "Проверить обновления" в настройках в секции "О приложении"

## Порядок выполнения

1. Добавить FileProvider и разрешение в манифест + `res/xml/file_paths.xml`
2. Обновить `Prefs.kt`
3. Создать `UpdateChecker.kt`
4. Создать `ApkDownloader.kt`
5. Обновить `MainActivity.kt` (проверка, скачивание, установка)
6. Обновить `index.html` и `theme-m3.html` (баннер + JS)
7. Добавить кнопку проверки в настройки (в секцию "О приложении")
8. Собрать, проверить на эмуляторе

## Версия

Текущая: `1.0.4`. Новая версия для тестирования — `1.0.5` (требует создания Release на GitHub с тегом `v1.0.5` и прикреплённым APK).
