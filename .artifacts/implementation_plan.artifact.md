# План: ручное добавление и редактирование профилей Xray / olcrtc

## Цель

Дать пользователю возможность вручную создавать и редактировать профили `vless://` (Xray) и `olcrtc://` через форму в WebView-UI, вместо импорта только из буфера обмена и подписок.

## Ключевое решение по правке профилей из подписки

Правка разрешена. Профиль сохраняется **как есть**, `subscriptionId` и `source` не трогаются. При следующем обновлении подписки `replaceSubscriptionProfiles` заменит запись серверной версией — это ожидаемое поведение. На карточке покажем плашку-предупреждение.

## Изменения

### 1. `ProfileModels.kt`
Добавить `MANUAL("manual")` в `Source`. `from()` уже использует `entries.firstOrNull` — подхватит автоматически.

### 2. `VlessProfile.kt` — обратный `toLink()`
Симметрично `parse()`: `vless://uuid@host:port?query#remark`. Покрыть round-trip-тестом.

### 3. `OlcrtcProfile.kt` — обратный `toLink()`
Симметрично `parse()`: `olcrtc://provider?transport<payload>@room#key$remark`. Покрыть round-trip-тестом.

### 4. `ProfileStore.kt` — `saveProfile(id, link, name)`
- `id == null` → создаём `Source.MANUAL`.
- иначе → `existing.copy(link, name)`, `subscriptionId`/`source` не меняются.

### 5. `VpnWebBridge.kt` — ручка `saveProfile`
`@JavascriptInterface fun saveProfile(id: String?, link: String, name: String): String` + метод в `Host`.

### 6. `MainActivity.kt` — реализация `Host.onSaveProfileRequested`
Валидация через парсер, затем `runOnUiThread { store.saveProfile(...); pushProfiles() }`, возврат `result(ok, message)`.

### 7. `index.html` — форма
- Кнопка «+» на экране профилей, «✎» на карточке.
- Модалка: переключатель схемы, поля по типу, валидация, кнопка «Вставить ссылку».
- Плашка «из подписки, правки перезапишутся при обновлении».

## Тесты
- Round-trip `parse(toLink(parse(link))) == parse(link)` для VLESS и olcrtc.
- `ProfileStore.saveProfile`: создание / обновление / сохранение `subscriptionId`.
- `onSaveProfileRequested`: невалидный link → `ok:false`.

## Порядок реализации
1. `toLink()` + round-trip-тесты (ядро).
2. `ProfileStore.saveProfile` + тесты.
3. `VpnWebBridge` + `MainActivity`.
4. Форма в `index.html`.
