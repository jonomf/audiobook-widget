package com.jono.audiobookwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Setup screen, and the "settings" entry in the widget's long-press menu.
 * Sections: playback access, updates, your widgets.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var accessButton: Button
    private lateinit var sizes: TextView
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button
    private lateinit var tokenField: EditText
    private lateinit var tokenRow: LinearLayout
    private lateinit var tokenHelp: TextView
    private var updating = false
    private var pendingUpdateAfterPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Opened from the long-press menu's widget settings: nothing to configure, just say OK.
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_CONFIGURE) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        }

        val pad = dp(24)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        col.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "One home-screen widget for whatever Libby or Audible is playing. " +
                "It switches to whichever app starts playing; tap the app icon to switch by hand."
            textSize = 15f
            setPadding(0, dp(12), 0, dp(8))
        })

        // --- Updates ---
        col.addView(heading("Updates"))
        updateStatus = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, dp(8)) }
        col.addView(updateStatus)
        updateButton = Button(this).apply {
            text = "Update now"
            setOnClickListener { startUpdate() }
        }
        col.addView(updateButton)
        col.addView(Button(this).apply {
            text = "Check for updates"
            setOnClickListener { checkNow() }
        })

        tokenHelp = TextView(this).apply {
            text = "To update in the app, it needs a read-only GitHub token (the repo is private):\n" +
                "1. Tap Create token below, sign in if asked.\n" +
                "2. Repository access → Only select repositories → audiobook-widget " +
                "(add future widget repos here later).\n" +
                "3. Permissions → Repository → Contents: Read-only. Longest expiry you're OK with.\n" +
                "4. Generate, copy, paste here, Save.\n\n" +
                "The token stays on this phone and is only sent to GitHub."
            textSize = 13f
            alpha = 0.8f
            setPadding(0, dp(12), 0, dp(4))
        }
        col.addView(tokenHelp)
        tokenRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tokenRow.addView(Button(this).apply {
            text = "Create token on GitHub"
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.TOKEN_PAGE))) }
        })
        tokenField = EditText(this).apply {
            hint = "Paste token (github_pat_…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        tokenRow.addView(tokenField)
        tokenRow.addView(Button(this).apply {
            text = "Save token"
            setOnClickListener {
                val t = tokenField.text.toString().trim()
                if (t.isEmpty()) return@setOnClickListener
                Updater.setToken(this@MainActivity, t)
                tokenField.setText("")
                checkNow()
            }
        })
        col.addView(tokenRow)
        col.addView(Button(this).apply {
            text = "Change token"
            setOnClickListener {
                tokenRow.visibility = View.VISIBLE
                tokenHelp.visibility = View.VISIBLE
            }
        }.also { changeTokenButton = it })

        // --- Playback access ---
        col.addView(heading("Playback access"))
        status = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, dp(8)) }
        col.addView(status)
        accessButton = Button(this).apply {
            text = "Allow notification access"
            setOnClickListener { openAccessSettings() }
        }
        col.addView(accessButton)
        col.addView(Button(this).apply {
            text = "Add widget to home screen"
            setOnClickListener { pinWidget() }
        })
        col.addView(TextView(this).apply {
            text = "Notification access is how Android lets an app see and control other apps' playback. " +
                "This app only looks at Libby and Audible, and never reads your notifications.\n\n" +
                "If the switch is greyed out (\"Restricted setting\"), that's because this app was " +
                "installed from a browser. Tap below, then ⋮ (top right) → Allow restricted settings, " +
                "and come back."
            textSize = 13f
            alpha = 0.7f
            setPadding(0, dp(12), 0, dp(8))
        })
        col.addView(Button(this).apply {
            text = "Open app info"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        })

        // --- Your widgets ---
        col.addView(heading("Your widgets"))
        sizes = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
        }
        col.addView(sizes)

        setContentView(ScrollView(this).apply { addView(col) })

        if (intent.getBooleanExtra(EXTRA_UPDATE, false)) startUpdate()
    }

    private var changeTokenButton: Button? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_UPDATE, false)) startUpdate()
    }

    override fun onResume() {
        super.onResume()
        val granted = Media.hasAccess(this)
        status.text = if (granted) "✓ Notification access is on" else "Notification access is off"
        accessButton.text = if (granted) "Notification access settings" else "Allow notification access"
        sizes.text = WidgetRenderer.describeSizes(this)
        WidgetRenderer.renderAll(this)
        refreshUpdateUi()
        if (pendingUpdateAfterPermission && packageManager.canRequestPackageInstalls()) {
            pendingUpdateAfterPermission = false
            startUpdate()
        } else if (!updating) {
            Updater.checkInBackground(this) { runOnUiThread { refreshUpdateUi() } }
        }
    }

    private fun refreshUpdateUi() {
        if (updating) return
        val hasToken = Updater.token(this) != null
        tokenRow.visibility = if (hasToken) View.GONE else View.VISIBLE
        tokenHelp.visibility = if (hasToken) View.GONE else View.VISIBLE
        changeTokenButton?.visibility = if (hasToken) View.VISIBLE else View.GONE
        val current = Updater.currentBuild(this)
        val latest = Updater.latestKnown(this)
        val error = Updater.lastError(this)
        updateStatus.text = when {
            !hasToken -> "You're on build $current. Add a GitHub token to update from here."
            error != null -> "You're on build $current. Couldn't check: $error"
            latest > current -> "Build $latest is available (you're on $current)."
            latest > 0 -> "✓ Up to date (build $current)"
            else -> "You're on build $current."
        }
        updateButton.visibility = if (hasToken && latest > current) View.VISIBLE else View.GONE
    }

    private fun checkNow() {
        updateStatus.text = "Checking…"
        Updater.checkInBackground(this) { runOnUiThread { refreshUpdateUi() } }
    }

    /** Download the latest build and hand it to Android's installer. */
    private fun startUpdate() {
        if (updating) return
        if (Updater.token(this) == null) {
            refreshUpdateUi()
            Toast.makeText(this, "Add a GitHub token first", Toast.LENGTH_LONG).show()
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            // One-time: Android asks you to let this app install updates to itself.
            pendingUpdateAfterPermission = true
            Toast.makeText(this, "Allow this app to install updates, then come back", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        updating = true
        updateButton.visibility = View.GONE
        updateStatus.text = "Looking up the latest build…"
        Thread {
            try {
                val release = Updater.fetchLatest(this)
                if (release.build <= Updater.currentBuild(this)) {
                    runOnUiThread { updating = false; refreshUpdateUi() }
                    return@Thread
                }
                val apk = Updater.download(this, release) { p ->
                    runOnUiThread {
                        updateStatus.text = if (p >= 0) "Downloading build ${release.build}… ${(p * 100).toInt()}%"
                        else "Downloading build ${release.build}…"
                    }
                }
                runOnUiThread { updateStatus.text = "Installing build ${release.build}…" }
                Updater.install(this, apk)
            } catch (e: Exception) {
                runOnUiThread {
                    updating = false
                    refreshUpdateUi()
                    updateStatus.text = "Update failed: ${e.message ?: e}"
                }
            }
        }.start()
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(6))
    }

    private fun openAccessSettings() {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            Media.listenerComponent(this).flattenToString(),
        )
        try {
            startActivity(detail)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun pinWidget() {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, AudiobookWidget::class.java), null, null)
        } else {
            Toast.makeText(this, "Long-press your home screen → Widgets → Audiobook", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Intent extra: start updating as soon as the screen opens (used by the widget's update dot). */
        const val EXTRA_UPDATE = "update"
    }
}
