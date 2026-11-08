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
import android.widget.Button
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
    private lateinit var appActions: TextView
    private lateinit var updateStatus: TextView
    private lateinit var updaterButton: Button

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
            text = "Two home-screen widgets that work the same way: Audiobooks (Libby and Audible) and " +
                "Music (YouTube Music). Their full panels have the controls for that kind of media. " +
                "Audiobooks switches to whichever app starts playing; tap the app icon to switch by hand."
            textSize = 15f
            setPadding(0, dp(12), 0, dp(8))
        })

        // --- Updates ---
        col.addView(heading("Updates"))
        updateStatus = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, dp(8)) }
        col.addView(updateStatus)
        updaterButton = Button(this).apply { setOnClickListener { openUpdater() } }
        col.addView(updaterButton)
        col.addView(Button(this).apply {
            text = "Get the latest build in the browser"
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))) }
        })

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
            text = "Add Audiobooks widget"
            setOnClickListener { pinWidget(AudiobookWidget::class.java, "Audiobooks") }
        })
        col.addView(Button(this).apply {
            text = "Add Music widget"
            setOnClickListener { pinWidget(MusicWidget::class.java, "Music") }
        })
        col.addView(TextView(this).apply {
            text = "Notification access is how Android lets an app see and control other apps' playback. " +
                "This app only looks at Libby, Audible and YouTube Music, and never reads your notifications.\n\n" +
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

        // --- What each app allows (e.g. whether bookmarks can go into the app itself) ---
        col.addView(heading("App actions"))
        appActions = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
        }
        col.addView(appActions)

        // --- Your widgets ---
        col.addView(heading("Your widgets"))
        sizes = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
        }
        col.addView(sizes)

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        val granted = Media.hasAccess(this)
        status.text = if (granted) "✓ Notification access is on" else "Notification access is off"
        accessButton.text = if (granted) "Notification access settings" else "Allow notification access"
        sizes.text = WidgetRenderer.describeSizes(this)
        appActions.text = describeAppActions()
        WidgetRenderer.renderAll(this)
        if (granted) RecentFetcher.fillGaps(this) { WidgetRenderer.renderAll(this) }
        val build = UpdateStatus.currentBuild(this)
        val hasUpdater = UpdateStatus.updaterIntent(this) != null
        val latest = UpdateStatus.latest(this)
        updateStatus.text = when {
            !hasUpdater -> "You're on build $build. Install Widget Updater to get an update symbol on " +
                "the widget and one-tap updates for all your widgets."
            latest > build -> "Build $latest is available (you're on $build)."
            else -> "You're on build $build. Widget Updater keeps this up to date; the widget shows " +
                "a small ↑ symbol when there's a new build."
        }
        updaterButton.text = when {
            !hasUpdater -> "Get Widget Updater"
            latest > build -> "Update now"
            else -> "Open Widget Updater"
        }
    }

    /** Extra actions each playing app offers through Android's media controls, and what that means for bookmarks. */
    private fun describeAppActions(): String {
        if (!Media.hasAccess(this)) return "Turn on notification access to see this."
        val controllers = Media.controllers(this)
        if (controllers.isEmpty()) return "Play something in Libby, Audible or YouTube Music, then come back here."
        return controllers.joinToString("\n\n") { c ->
            val label = Sources.byPkg(c.packageName)?.label ?: c.packageName
            val names = Media.customActions(c).map { it.name.toString() }
            val list = if (names.isEmpty()) "no extra actions" else names.joinToString(", ")
            val queue = c.queue?.size ?: 0
            val detail = if (Profile.of(c.packageName) == Profile.BOOKS) {
                if (Media.bookmarkAction(c) != null) "Bookmarks go into $label too."
                else "No bookmark action, so bookmarks are saved in this widget only."
            } else {
                val names2 = MusicStore.queueLog(this).joinToString("\n    ") { it }.ifEmpty { "none yet" }
                "Queue items shared: $queue\n  Recent queue names (album / mix detection):\n    $names2"
            }
            "• $label: $list\n  $detail"
        }
    }

    private fun openUpdater() {
        val intent = UpdateStatus.updaterIntent(this)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse(UpdateStatus.UPDATER_RELEASES))
        startActivity(intent)
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

    private fun pinWidget(provider: Class<*>, name: String) {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, provider), null, null)
        } else {
            Toast.makeText(this, "Long-press your home screen → Widgets → Media → $name", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val RELEASES_URL = "https://github.com/jonomf/audiobook-widget/releases/latest"
    }
}
