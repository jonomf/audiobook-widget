package com.jono.audiobookwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/** Gets the installer's result, and runs once more after an update lands. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STATUS -> onStatus(ctx, intent)
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // New version is in: redraw (the update dot goes away) and look for anything newer.
                WidgetRenderer.renderAll(ctx)
                Updater.checkInBackground(ctx, goAsync()::finish)
            }
        }
    }

    private fun onStatus(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                Toast.makeText(ctx, "Update didn't install: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val ACTION_STATUS = "com.jono.audiobookwidget.INSTALL_STATUS"
    }
}
