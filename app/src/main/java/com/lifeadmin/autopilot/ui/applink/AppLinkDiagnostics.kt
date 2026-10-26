package com.lifeadmin.autopilot.ui.applink

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.os.Build
import java.security.MessageDigest

/**
 * Read-only facts that tell a verified App Link apart from a mere explicit-intent launch, shown on the
 * test screen so the phone check needs no adb. Nothing here is private: the certificate digest is the
 * value published in `assetlinks.json`.
 */
data class AppLinkDiagnostics(val hostState: String, val signingSha256: String?)

fun readAppLinkDiagnostics(context: Context, host: String): AppLinkDiagnostics =
    AppLinkDiagnostics(hostState(context, host), signingSha256(context))

private fun hostState(context: Context, host: String): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "Not reported before Android 12"
    val state = runCatching {
        context.getSystemService(DomainVerificationManager::class.java)?.getDomainVerificationUserState(context.packageName)
    }.getOrNull() ?: return "Unavailable"
    val label = when (state.hostToStateMap[host]) {
        DomainVerificationUserState.DOMAIN_STATE_VERIFIED -> "Verified"
        DomainVerificationUserState.DOMAIN_STATE_SELECTED -> "Not verified (selected by you in Settings)"
        DomainVerificationUserState.DOMAIN_STATE_NONE -> "Not verified"
        else -> "Not declared"
    }
    return if (state.isLinkHandlingAllowed) label else "$label; link handling turned off in Settings"
}

private fun signingSha256(context: Context): String? = runCatching {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    val cert = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
    MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString(":") { "%02X".format(it) }
}.getOrNull()
