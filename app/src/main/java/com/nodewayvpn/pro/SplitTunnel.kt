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
 * Применяет настройки раздельного туннелирования к [Builder] интерфейса VPN.
 *
 * Ошибки наружу не пробрасываются: приложение могли удалить после сохранения списка,
 * и такое не должно ронять подключение.
 *
 * @param selfPackage пакет самого приложения: в режиме [TunnelMode.ALLOW] он всегда
 * попадает в разрешённые, иначе приложение не сможет закрыть туннель и обновить
 * подписку, а в [TunnelMode.EXCLUDE] - всегда исключается из списка.
 */
fun Builder.applySplitTunnel(
    context: Context,
    mode: TunnelMode,
    packages: Set<String>,
    selfPackage: String,
) {
    val targets = when (mode) {
        TunnelMode.ALLOW -> packages + selfPackage
        TunnelMode.EXCLUDE -> packages.filter { it != selfPackage }
        TunnelMode.ALL -> return
    }

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