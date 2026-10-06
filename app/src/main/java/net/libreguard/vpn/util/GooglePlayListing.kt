package net.libreguard.vpn.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Tries Play first, then the browser, without querying installed packages. */
fun openGooglePlayListing(context: Context): Boolean {
    fun open(base: String, playStore: Boolean): Boolean = try {
        val uri = Uri.parse(base).buildUpon().appendQueryParameter("id", context.packageName).build()
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (playStore) intent.setPackage("com.android.vending")
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
    return open("market://details", true) || open("https://play.google.com/store/apps/details", false)
}