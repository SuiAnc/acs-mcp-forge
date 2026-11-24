/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.services.keepalive

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.tom.rv2ide.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.slf4j.LoggerFactory

/**
 * Floating overlay button with drag support and automatic edge snapping.
 *
 * After the user lifts the finger the button waits for [KeepAlivePreferences.edgeDelayMs]
 * (3 seconds by default) and then slides to the closest screen edge, where it shrinks into a
 * translucent handle. Touching the button again cancels the pending snap.
 *
 * @author AndroidCodeStudio
 */
class FloatingWindowController(private val context: Context) {

  private val log = LoggerFactory.getLogger(FloatingWindowController::class.java)

  private val windowManager =
      context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  private val handler = Handler(Looper.getMainLooper())

  private var rootView: View? = null
  private var layoutParams: WindowManager.LayoutParams? = null
  private var snapAnimator: ValueAnimator? = null
  private var expanded = false

  private val sizePx: Int
    get() = (SIZE_DP * context.resources.displayMetrics.density).toInt()

  private val handleWidthPx: Int
    get() = (HANDLE_WIDTH_DP * context.resources.displayMetrics.density).toInt()

  private val marginPx: Int
    get() = (EDGE_MARGIN_DP * context.resources.displayMetrics.density).toInt()

  private val touchSlopPx: Int
    get() = (TOUCH_SLOP_DP * context.resources.displayMetrics.density).toInt()

  val isAttached: Boolean
    get() = rootView != null

  private val snapRunnable = Runnable { snapToClosestEdge() }

  /** Adds the overlay view when the user granted the permission. */
  fun attach(): Boolean {
    if (rootView != null) return true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
      log.warn("Overlay permission is not granted, floating window disabled")
      return false
    }

    val view = createView()
    val params = createLayoutParams()

    val storedX = KeepAlivePreferences.floatingX
    val storedY = KeepAlivePreferences.floatingY
    val screenWidth = context.resources.displayMetrics.widthPixels
    val screenHeight = context.resources.displayMetrics.heightPixels

    params.x = if (storedX >= 0) storedX.coerceIn(0, max(0, screenWidth - sizePx)) else screenWidth - sizePx - marginPx
    params.y = if (storedY >= 0) storedY.coerceIn(0, max(0, screenHeight - sizePx)) else screenHeight / 3

    attachTouchHandler(view, params)

    return try {
      windowManager.addView(view, params)
      rootView = view
      layoutParams = params
      true
    } catch (e: Exception) {
      log.warn("Unable to add the floating window", e)
      false
    }
  }

  /** Removes the overlay and cancels any pending work. */
  fun detach() {
    handler.removeCallbacks(snapRunnable)
    snapAnimator?.cancel()
    snapAnimator = null
    rootView?.let { view ->
      runCatching { windowManager.removeView(view) }
    }
    rootView = null
    layoutParams = null
  }

  /** Re-applies the opacity/size preferences. */
  fun refreshAppearance() {
    val view = rootView ?: return
    view.alpha = KeepAlivePreferences.alphaPercent / 100f
  }

  // -----------------------------------------------------------------------------------------------
  // View and layout
  // -----------------------------------------------------------------------------------------------

  private fun createView(): View {
    val textView = TextView(context)
    textView.text = "ACS"
    textView.gravity = Gravity.CENTER
    textView.setTextColor(Color.WHITE)
    textView.textSize = 12f
    textView.includeFontPadding = false
    textView.background = circularBackground()
    textView.alpha = KeepAlivePreferences.alphaPercent / 100f
    textView.contentDescription = context.getString(R.string.keep_alive_floating_description)
    textView.setOnClickListener { openApp() }
    return textView
  }

  private fun circularBackground(): GradientDrawable {
    return GradientDrawable().apply {
      shape = GradientDrawable.OVAL
      setColor(ACCENT_COLOR)
      setStroke(2, Color.argb(120, 255, 255, 255))
    }
  }

  private fun createLayoutParams(): WindowManager.LayoutParams {
    val type =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
          @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

    return WindowManager.LayoutParams(
            sizePx,
            sizePx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        .apply { gravity = Gravity.TOP or Gravity.START }
  }

  // -----------------------------------------------------------------------------------------------
  // Drag and edge snapping
  // -----------------------------------------------------------------------------------------------

  private fun attachTouchHandler(view: View, params: WindowManager.LayoutParams) {
    var initialX = 0
    var initialY = 0
    var downX = 0f
    var downY = 0f
    var dragging = false

    view.setOnTouchListener { _, event ->
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          handler.removeCallbacks(snapRunnable)
          snapAnimator?.cancel()
          snapAnimator = null
          expanded = false
          params.width = sizePx
          params.height = sizePx
          clampToScreen(params)
          runCatching { windowManager.updateViewLayout(view, params) }
          view.alpha = KeepAlivePreferences.alphaPercent / 100f
          initialX = params.x
          initialY = params.y
          downX = event.rawX
          downY = event.rawY
          dragging = false
          true
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = (event.rawX - downX).toInt()
          val dy = (event.rawY - downY).toInt()
          if (!dragging && (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx)) {
            dragging = true
          }
          if (dragging) {
            params.x = initialX + dx
            params.y = initialY + dy
            clampToScreen(params)
            runCatching { windowManager.updateViewLayout(view, params) }
          }
          true
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          if (dragging) {
            scheduleEdgeSnap()
          }
          true
        }
        else -> false
      }
    }
  }

  private fun clampToScreen(params: WindowManager.LayoutParams) {
    val metrics = context.resources.displayMetrics
    params.x = params.x.coerceIn(0, max(0, metrics.widthPixels - params.width))
    params.y = params.y.coerceIn(0, max(0, metrics.heightPixels - params.height))
  }

  private fun scheduleEdgeSnap() {
    handler.removeCallbacks(snapRunnable)
    handler.postDelayed(snapRunnable, KeepAlivePreferences.edgeDelayMs.toLong())
  }

  private fun snapToClosestEdge() {
    val view = rootView ?: return
    val params = layoutParams ?: return
    val metrics = context.resources.displayMetrics
    val center = params.x + params.width / 2
    val snapToLeft = center < metrics.widthPixels / 2

    val targetX =
        if (snapToLeft) {
          marginPx
        } else {
          metrics.widthPixels - params.width - marginPx
        }

    KeepAlivePreferences.floatingX = targetX
    KeepAlivePreferences.floatingY = params.y
    KeepAlivePreferences.floatingEdge = if (snapToLeft) 0 else 1

    snapAnimator?.cancel()
    snapAnimator =
        ValueAnimator.ofInt(params.x, targetX).apply {
          duration = SNAP_ANIMATION_MS.toLong()
          addUpdateListener { animator ->
            params.x = animator.animatedValue as Int
            runCatching { windowManager.updateViewLayout(view, params) }
          }
          start()
        }

    // Collapse into a translucent handle once snapped.
    view.postDelayed(
        {
          val current = layoutParams ?: return@postDelayed
          val collapsedWidth = min(handleWidthPx, sizePx)
          current.width = collapsedWidth
          current.x =
              if (snapToLeft) marginPx else metrics.widthPixels - collapsedWidth - marginPx
          runCatching { windowManager.updateViewLayout(view, current) }
          view.alpha = (KeepAlivePreferences.alphaPercent / 100f) * COLLAPSED_ALPHA_FACTOR
          expanded = false
        },
        SNAP_ANIMATION_MS.toLong(),
    )
  }

  private fun openApp() {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?: return
    launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(launch) }
    expanded = !expanded
    // Tapping also re-expands the handle so the user can drag it again.
    val view = rootView ?: return
    layoutParams?.let { params ->
      params.width = sizePx
      params.height = sizePx
      clampToScreen(params)
      runCatching { windowManager.updateViewLayout(view, params) }
    }
    view.alpha = KeepAlivePreferences.alphaPercent / 100f
  }

  companion object {

    private const val SIZE_DP = 56
    private const val HANDLE_WIDTH_DP = 40
    private const val EDGE_MARGIN_DP = 8
    private const val TOUCH_SLOP_DP = 8
    private const val SNAP_ANIMATION_MS = 180
    private const val COLLAPSED_ALPHA_FACTOR = 0.7f

    private val ACCENT_COLOR = Color.parseColor("#1F6FEB")
  }
}
