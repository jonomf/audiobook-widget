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

/** Setup screen: grant notification access, then add the widget. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var accessButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                "It switches to whichever app starts playing; tap the app name or chips to switch by hand."
            textSize = 15f
            setPadding(0, dp(12), 0, dp(20))
        })

        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, dp(12))
        }
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
            setPadding(0, dp(24), 0, dp(8))
        })
        col.addView(Button(this).apply {
            text = "Open app info"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        val granted = Media.hasAccess(this)
        status.text = if (granted) "✓ Notification access is on" else "Notification access is off"
        accessButton.text = if (granted) "Notification access settings" else "Allow notification access"
        WidgetRenderer.renderAll(this)
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
}
