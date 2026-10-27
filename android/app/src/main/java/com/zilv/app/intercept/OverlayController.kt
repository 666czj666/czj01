package com.zilv.app.intercept

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 遮挡层（PRD §4.5 / O.1）。
 *
 * 刻意**不用 Compose**：Compose 在 WindowManager 里需要一个完整的
 * LifecycleOwner + SavedStateRegistryOwner，为一个纯静态的拦截界面引入这套机制
 * 既不划算也更容易出问题。这里用程序化构建的原生 View，行为完全确定。
 *
 * 界面元素（缺一不可）：
 *  - 倒计时
 *  - 自定义文案
 *  - 「立即打卡」（仅需打卡解锁的规则显示）
 *  - **「返回桌面」**：没有它用户会被困在遮挡层里
 *  - 「紧急放行 5 分钟」
 */
class OverlayController(private val context: Context) {

    data class Content(
        val countdownText: String,
        val message: String,
        val showCheckIn: Boolean
    )

    private val windowManager: WindowManager?
        get() = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private val handler = Handler(Looper.getMainLooper())

    private var rootView: View? = null
    private var countdownView: TextView? = null
    private var messageView: TextView? = null
    private var checkInButton: Button? = null

    val isShowing: Boolean get() = rootView != null

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    /**
     * 显示遮挡层。
     *
     * 需要悬浮窗权限；没有权限时静默返回 false（PRD：降级为"仅锁屏"，
     * 权限体检页会提示用户去开启）。
     */
    fun show(
        content: Content,
        onCheckIn: () -> Unit,
        onGoHome: () -> Unit,
        onEmergencyRelease: () -> Unit
    ): Boolean {
        if (!canDrawOverlays()) {
            Log.w(TAG, "没有悬浮窗权限，遮挡层无法显示")
            return false
        }

        if (rootView != null) {
            update(content)
            return true
        }

        val wm = windowManager ?: return false
        val view = buildView(content, onCheckIn, onGoHome, onEmergencyRelease)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // 不可获得焦点：这样系统对话框、来电界面出现时不会被我们抢焦点
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        return runCatching {
            wm.addView(view, params)
            rootView = view
            true
        }.onFailure {
            Log.e(TAG, "添加遮挡层失败", it)
            rootView = null
        }.getOrDefault(false)
    }

    fun update(content: Content) {
        countdownView?.text = content.countdownText
        messageView?.text = content.message
        checkInButton?.visibility = if (content.showCheckIn) View.VISIBLE else View.GONE
    }

    fun hide() {
        val view = rootView ?: return
        rootView = null
        countdownView = null
        messageView = null
        checkInButton = null
        runCatching { windowManager?.removeViewImmediate(view) }
            .onFailure { Log.w(TAG, "移除遮挡层失败", it) }
    }

    // ---------------------------------------------------------------- 视图构建

    private fun buildView(
        content: Content,
        onCheckIn: () -> Unit,
        onGoHome: () -> Unit,
        onEmergencyRelease: () -> Unit
    ): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(PRD_BACKGROUND)
            isClickable = true // 吃掉触摸事件，避免穿透到被拦应用
        }

        container.addView(text("🔒", 48f, Color.WHITE))

        countdownView = text(content.countdownText, 40f, Color.WHITE, bold = true)
        container.addView(countdownView)

        container.addView(spacer(24))

        messageView = text(content.message, 16f, Color.parseColor("#D1D5DB"))
        container.addView(messageView)

        container.addView(spacer(40))

        checkInButton = button("立即打卡", onCheckIn).also {
            it.visibility = if (content.showCheckIn) View.VISIBLE else View.GONE
            container.addView(it)
        }

        container.addView(spacer(12))
        container.addView(button("返回桌面", onGoHome))

        container.addView(spacer(28))
        container.addView(
            text("紧急放行 5 分钟", 14f, Color.parseColor("#9CA3AF")).apply {
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setOnClickListener { onEmergencyRelease() }
            }
        )

        return container
    }

    private fun text(
        value: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false
    ): TextView = TextView(context).apply {
        text = value
        setTextColor(color)
        textSize = sizeSp
        gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(24), dp(6), dp(24), dp(6))
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            textSize = 17f
            setOnClickListener { onClick() }
        }

    private fun spacer(heightDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics
    ).toInt()

    companion object {
        private const val TAG = "OverlayController"

        /** PRD §4.5：深色背景，降低刺激。 */
        private val PRD_BACKGROUND = Color.parseColor("#0A0A0A")
    }
}
