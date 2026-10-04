package com.nodewayvpn.pro

import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService.Builder
import android.util.Log

/**
 * Режим раздельного туннелирования.
 *
 * - [ALL] - в VPN уходит трафик всех приложений;
 * - [ALLOW] - только перечисленных, трафик остальных идёт напрямую;
 * - [EXCLUDE] - все, кроме перечисленных.
 */
enum class TunnelMode(val code: String) {
    ALL("all"),
    ALLOW("allow"),
    EXCLUDE("exclude");

    companion object {
        fun fromCode(code: String): TunnelMode = entries.firstOrNull { it.code == code } ?: ALL
    }
}

/**
 * Пакеты, которые надо передать в [Builder], и ничего больше.
 *
 * Вынесено отдельной чистой функцией намеренно: решение о судьбе собственного пакета
 * приложения — единственное место здесь с нетривиальной логикой, и именно оно ломает
 * olcrtc при неверном [includeSelf]. Функция ничего не знает про Android, поэтому
 * её поведение проверяется обычным тестом.
 *
 * @param includeSelf входит ли пакет приложения в туннель.
 *
 * Обычному транспорту нужно `true`: через VPN приложение обновляет подписки.
 * olcrtc — отдельный процесс с тем же UID, он не умеет вызывать `protect()`, и его
 * сокеты попадают в подменённый маршрут. Ему нужен `false`, то есть наш UID вне
 * туннеля в любом режиме.
 */
internal fun tunnelTargets(
    mode: TunnelMode,
    packages: Set<String>,
    selfPackage: String,
    includeSelf: Boolean,
): Set<String> = when (mode) {
    TunnelMode.ALL -> emptySet()
    // Свой пакет убираем всегда: в разрешённый список он попасть не должен, а в
    // список исключений — тем более. Для этого случая хватает отсутствия в списке,
    // поэтому addDisallowedApplication здесь и не вызывается: Android не принимает
    // разрешённый и запрещённый списки одновременно.
    TunnelMode.ALLOW -> packages.toMutableSet().apply {
        if (includeSelf) add(selfPackage) else remove(selfPackage)
    }
    TunnelMode.EXCLUDE -> packages - selfPackage
}

/**
 * Применяет настройки раздельного туннелирования к [Builder] интерфейса VPN.
 *
 * Ошибки наружу не пробрасываются: приложение могли удалить после сохранения списка,
 * и такое не должно ронять подключение.
 *
 * @param selfPackage пакет самого приложения.
 * @param includeSelf см. [tunnelTargets].
 */
fun Builder.applySplitTunnel(
    context: Context,
    mode: TunnelMode,
    packages: Set<String>,
    selfPackage: String,
    includeSelf: Boolean = true,
) {
    val targets = tunnelTargets(mode, packages, selfPackage, includeSelf)
    if (targets.isEmpty()) return

    var applied = 0
    targets.forEach { pkg ->
        if (pkg.isBlank() || !isInstalled(context.packageManager, pkg)) {
            Log.w(TAG, "Skipping missing package: $pkg")
            return@forEach
        }
        val appliedOk = runCatching {
            if (mode == TunnelMode.ALLOW) {
                addAllowedApplication(pkg)
            } else {
                addDisallowedApplication(pkg)
            }
        }.isSuccess
        if (appliedOk) applied++ else Log.w(TAG, "System rejected package: $pkg")
    }
    Log.i(TAG, "Split tunneling ${mode.code}: applied $applied of ${targets.size}")
}

/**
 * Пакеты, фактически уходящие в туннель, с учётом текущих настроек.
 *
 * В режиме [TunnelMode.ALLOW] к списку всегда добавляется пакет самого приложения.
 */
fun splitPackages(context: Context): Set<String> {
    val prefs = Prefs(context)
    val packages = prefs.splitPackages
    return when (TunnelMode.fromCode(prefs.tunnelMode)) {
        TunnelMode.ALLOW -> LinkedHashSet(packages).apply { add(context.packageName) }
        TunnelMode.ALL, TunnelMode.EXCLUDE -> packages
    }
}

private fun isInstalled(packageManager: PackageManager, pkg: String): Boolean = runCatching {
    packageManager.getPackageInfo(pkg, 0)
}.isSuccess

private const val TAG = "SplitTunnel"