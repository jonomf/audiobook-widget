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
    private lateinit var updateStatus: TextView

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
        col.addView(Button(this).apply {
            text = "Get the latest build"
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
    }

    override fun onResume() {
        super.onResume()
        val granted = Media.hasAccess(this)
        status.text = if (granted) "✓ Notification access is on" else "Notification access is off"
        accessButton.text = if (granted) "Notification access settings" else "Allow notification access"
        sizes.text = WidgetRenderer.describeSizes(this)
        WidgetRenderer.renderAll(this)
        if (granted) RecentFetcher.fillGaps(this) { WidgetRenderer.renderAll(this) }
        val build = packageManager.getPackageInfo(packageName, 0).longVersionCode
        updateStatus.text = "You're on build $build. Newer builds are on GitHub; " +
            "download one and open it to install over this version."
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
        const val RELEASES_URL = "https://github.com/jonomf/audiobook-widget/releases/latest"
    }
}
