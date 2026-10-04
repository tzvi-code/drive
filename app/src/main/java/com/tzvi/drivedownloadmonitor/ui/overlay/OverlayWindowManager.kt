package com.tzvi.drivedownloadmonitor.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.animation.doOnEnd
import androidx.core.view.doOnLayout
import com.tzvi.drivedownloadmonitor.domain.model.DownloadItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class OverlayWindowManager(context: Context) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _visible = MutableStateFlow(false)
    val visible = _visible.asStateFlow()

    private var composeView: ComposeView? = null
    private var params: LayoutParams? = null
    private var dragging = false
    private var startRawX = 0f
    private var startRawY = 0f
    private var startX = 0
    private var startY = 0
    private var snapAnimator: ValueAnimator? = null
    private var hideRunnable: Runnable? = null

    private val windowWidthPx: Int
        get() = appContext.resources.displayMetrics.widthPixels

    fun show(items: List<DownloadItem>) {
        mainHandler.post {
            if (!canDrawOverlays()) {
                _visible.value = false
                return@post
            }

            val existing = composeView
            if (existing == null) {
                createAndAddView(items)
            } else {
                setContent(existing, items)
                existing.animate().alpha(1f).setDuration(180).start()
            }
            _visible.value = true
            scheduleSnap()
        }
    }

    fun hide() {
        mainHandler.post {
            hideRunnable?.let(mainHandler::removeCallbacks)
            snapAnimator?.cancel()
            val view = composeView ?: run {
                _visible.value = false
                return@post
            }

            view.animate()
                .alpha(0f)
                .setDuration(180)
                .withEndAction {
                    removeView()
                }
                .start()
        }
    }

    fun destroy() {
        mainHandler.post {
            hideRunnable?.let(mainHandler::removeCallbacks)
            snapAnimator?.cancel()
            removeView()
        }
    }

    private fun createAndAddView(items: List<DownloadItem>) {
        val view = ComposeView(appContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            alpha = 0f
            doOnLayout {
                scheduleSnap()
            }
        }

        params = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16.dpToPx()
            y = 80.dpToPx()
        }

        setContent(view, items)

        try {
            windowManager.addView(view, params)
            composeView = view
            view.animate()
                .alpha(1f)
                .setDuration(220)
                .start()
        } catch (_: WindowManager.BadTokenException) {
            composeView = null
            _visible.value = false
        } catch (_: SecurityException) {
            composeView = null
            _visible.value = false
        }
    }

    private fun setContent(view: ComposeView, items: List<DownloadItem>) {
        view.setContent {
            MaterialTheme {
                OverlayView(
                    items = items,
                    onClose = { hide() },
                    onDragStart = ::startDrag,
                    onDragMove = ::moveDrag,
                    onDragEnd = ::endDrag
                )
            }
        }
    }

    private fun removeView() {
        val view = composeView ?: return
        runCatching { windowManager.removeView(view) }
        composeView = null
        _visible.value = false
    }

    private fun startDrag(rawX: Float, rawY: Float) {
        dragging = true
        snapAnimator?.cancel()
        val p = params ?: return
        startRawX = rawX
        startRawY = rawY
        startX = p.x
        startY = p.y
        hideRunnable?.let(mainHandler::removeCallbacks)
    }

    private fun moveDrag(rawX: Float, rawY: Float) {
        if (!dragging) return
        val p = params ?: return
        p.x = startX + (rawX - startRawX).toInt()
        p.y = (startY + (rawY - startRawY).toInt()).coerceAtLeast(0)
        updateLayout()
    }

    private fun endDrag() {
        if (!dragging) return
        dragging = false
        animateToEdge()
        scheduleSnap()
    }

    private fun animateToEdge() {
        val p = params ?: return
        val view = composeView ?: return
        val margin = 12.dpToPx()
        val targetX = if (p.x + view.width / 2 < windowWidthPx / 2) {
            margin
        } else {
            (windowWidthPx - view.width - margin).coerceAtLeast(margin)
        }

        val start = p.x
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(start, targetX).apply {
            duration = 240
            addUpdateListener { animator ->
                params?.x = animator.animatedValue as Int
                updateLayout()
            }
            doOnEnd {
                scheduleSnap()
            }
            start()
        }
    }

    private fun scheduleSnap() {
        hideRunnable?.let(mainHandler::removeCallbacks)
        hideRunnable = Runnable { animateToEdge() }.also {
            mainHandler.postDelayed(it, 4_000L)
        }
    }

    private fun updateLayout() {
        val view = composeView ?: return
        val p = params ?: return
        runCatching { windowManager.updateViewLayout(view, p) }
    }

    private fun canDrawOverlays(): Boolean =
        android.provider.Settings.canDrawOverlays(appContext)

    private fun Int.dpToPx(): Int =
        (this * appContext.resources.displayMetrics.density).toInt()
}
