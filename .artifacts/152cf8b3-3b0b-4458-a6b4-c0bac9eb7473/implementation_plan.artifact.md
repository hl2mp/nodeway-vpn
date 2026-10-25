# Theme Style Switcher Implementation Plan

## Goal
Add a "Theme Style" setting that allows users to switch between two UI themes:
- **Classic** - current `index.html` (iOS-like design)
- **Material 3** - new `theme-m3.html` (Material 3 design)

The setting persists across app restarts and immediately reloads the WebView with the selected theme file.

## Files to Modify

### 1. [MODIFY] Prefs.kt
Add `themeStyle` property with constants:
- `KEY_THEME_STYLE = "theme_style"`
- `THEME_STYLE_CLASSIC = "classic"`
- `THEME_STYLE_M3 = "m3"`
- Default: `"classic"`

### 2. [MODIFY] VpnWebBridge.kt
- Add `onSetThemeStyleRequested(mode: String)` to `Host` interface
- Add `@JavascriptInterface fun setThemeStyle(mode: String)` method

### 3. [MODIFY] MainActivity.kt
- Add `themeHtmlFile()` helper returning correct asset filename based on `prefs.themeStyle`
- Update `webView.loadUrl()` in `onCreate()` to use dynamic filename
- Implement `onSetThemeStyleRequested(style: String)` - save pref and reload WebView
- Ensure `pushTheme()` still works correctly after style change

### 4. [MODIFY] index.html (Classic theme)
- Add "Стиль темы" row in Settings section (after Theme row)
- Add `themeStyleSheet` bottom sheet with options: Classic, Material 3, Cancel
- Add JS handlers for opening sheet, selecting option, calling bridge
- Add `window.onThemeStyle(style)` callback to update UI

### 5. [MODIFY] theme-m3.html (Material 3 theme)
- Add "Стиль темы" row in Settings section (after Theme row)
- Add `themeStyleSheet` bottom sheet with options: Classic, Material 3, Cancel
- Add JS handlers for opening sheet, selecting option, calling bridge
- Add `window.onThemeStyle(style)` callback to update UI

## Verification
- Build project successfully
- Test switching between Classic and Material 3 themes
- Verify persistence after app restart
- Verify theme (light/dark/system) still works within each style