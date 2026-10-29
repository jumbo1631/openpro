package com.openpro.server

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Sign-in through the official Nextcloud app. That app keeps each account's app password in
 * Android's account system, and with the user's consent (Android's own "allow access" prompt)
 * hands it to OpenPro, so nothing has to be typed and no browser is needed. OpenPro then talks
 * to the server itself, the same as after a browser sign-in; the Nextcloud app isn't involved in
 * uploads.
 */
object NextcloudAppAccount {
    /** Account types registered by the Nextcloud Files app and its beta. */
    private val accountTypes = arrayOf("nextcloud", "nextcloud.beta")

    data class Credentials(val address: String, val loginName: String, val appPassword: String)

    /** True if a Nextcloud app that can provide accounts is installed. */
    fun available(context: Context): Boolean =
        AccountManager.get(context).authenticatorTypes.any { it.type in accountTypes }

    /** Android's account picker, showing only Nextcloud accounts. Picking one lets OpenPro see that account. */
    fun chooser(): Intent = AccountManager.newChooseAccountIntent(null, null, accountTypes, null, null, null, null)

    /**
     * Splits the app's account name, "loginname@host[:port][/path]", into login name and address.
     * The login name may itself contain @ (an e-mail address); the server part never does.
     */
    fun parse(accountName: String): Pair<String, String>? {
        val at = accountName.lastIndexOf('@')
        if (at <= 0 || at == accountName.length - 1) return null
        return accountName.substring(0, at) to accountName.substring(at + 1)
    }

    /**
     * Asks the Nextcloud app for the account's app password. Android shows its consent prompt over
     * [activity] the first time; declining throws [android.accounts.OperationCanceledException].
     */
    suspend fun credentials(activity: Activity, accountName: String, accountType: String): Credentials {
        val (login, address) = parse(accountName) ?: throw IOException("The Nextcloud app's account name isn't one OpenPro understands")
        val future = AccountManager.get(activity)
            .getAuthToken(Account(accountName, accountType), "$accountType.password", null, activity, null, null)
        val password = withContext(Dispatchers.IO) { future.result }.getString(AccountManager.KEY_AUTHTOKEN)
            ?: throw IOException("The Nextcloud app didn't provide a password for $accountName")
        return Credentials(address, login, password)
    }
}
