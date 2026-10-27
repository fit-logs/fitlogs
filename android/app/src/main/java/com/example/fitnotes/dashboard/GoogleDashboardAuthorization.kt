package com.example.fitnotes.dashboard

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.*
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun dashboardAuthorization(email: String = "", chooseAccount: Boolean = false): AuthorizationRequest = AuthorizationRequest.builder()
    .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE))).setOptOutIncludingGrantedScopes(true).apply {
        if (chooseAccount) setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
        else if (email.isNotBlank()) setAccount(Account(email, "com.google"))
    }.build()

fun dashboardAccessToken(result: AuthorizationResult): String {
    if (DRIVE_FILE_SCOPE !in result.grantedScopes || result.accessToken.isNullOrBlank()) throw DashboardSyncException(DashboardFailure.AUTH)
    return result.accessToken!!
}
suspend fun <T> Task<T>.dashboardAwait(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
suspend fun clearDashboardToken(context: Context, token: String) {
    Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(token).build()).dashboardAwait()
}
suspend fun revokeDashboardAccess(context: Context, email: String) {
    val request = RevokeAccessRequest.builder().setScopes(listOf(Scope(DRIVE_FILE_SCOPE))).apply {
        if (email.isNotBlank()) setAccount(Account(email, "com.google"))
    }.build()
    Identity.getAuthorizationClient(context).revokeAccess(request).dashboardAwait()
}
