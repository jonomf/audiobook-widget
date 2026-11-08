package com.jono.audiobookwidget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import kotlin.math.min

/**
 * The expand panel: tap a small widget and this floats over the home screen showing that
 * widget's full (XL) view, so it looks and works exactly like the widget at its biggest.
 * It redraws whenever the widget does. Tap outside to close.
 */
class PanelActivity : Activity() {
    private lateinit var card: FrameLayout
    private var profile = Profile.BOOKS
    private val redraw: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readProfile(intent)
        val dm = resources.displayMetrics
        val dp = dm.density
        val width = min(dm.widthPixels - (32 * dp).toInt(), (420 * dp).toInt())
        val height = min(dm.heightPixels - (160 * dp).toInt(), (520 * dp).toInt())

        val root = FrameLayout(this).apply { setOnClickListener { finish() } }
        card = FrameLayout(this).apply { isClickable = true } // taps inside don't close it
        root.addView(card, FrameLayout.LayoutParams(width, height, Gravity.CENTER))
        setContentView(root)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readProfile(intent)
        render()
    }

    /** Which widget opened the panel (Audiobooks or Music); each shows its own full view. */
    private fun readProfile(intent: Intent?) {
        profile = intent?.getStringExtra(EXTRA_PROFILE)
            ?.let { name -> Profile.values().firstOrNull { it.name == name } } ?: Profile.BOOKS
    }

    override fun onResume() {
        super.onResume()
        WidgetRenderer.listeners.add(redraw)
        render()
    }

    override fun onPause() {
        WidgetRenderer.listeners.remove(redraw)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        finish() // opening an app from the panel closes it
    }

    private fun render() {
        if (isFinishing) return
        card.removeAllViews()
        card.addView(WidgetRenderer.buildPanel(this, profile).apply(this, card))
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
    }
}
