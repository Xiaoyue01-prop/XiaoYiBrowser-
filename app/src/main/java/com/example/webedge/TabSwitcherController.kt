package com.example.webedge

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import eightbitlab.com.blurview.BlurTarget

/** UI đa tab tách riêng khỏi Activity để MainActivity tập trung điều phối trình duyệt. */
class TabSwitcherController(
    private val activity: ComponentActivity,
    private val tabManager: TabManager,
    private val store: Store,
    private val blurTarget: BlurTarget,
    private val isNight: Boolean,
    private val foregroundColor: Int,
    private val secondaryColor: Int,
    private val accentColor: Int,
    private val dp: (Int) -> Int,
    private val captureActiveTab: () -> Unit,
    private val onActivateTab: (TabManager.Tab) -> Unit,
    private val onCloseTab: (TabManager.Tab) -> Unit,
    private val onCreateTab: () -> Unit,
    private val shortUrl: (String) -> String
) {
    private fun createBlurButton(
        text: String,
        paddingH: Int = Constants.BUTTON_PADDING_H,
        paddingV: Int = Constants.BUTTON_PADDING_V,
        onClick: () -> Unit
    ): FrameLayout {
        val frame = FrameLayout(activity)
        frame.addView(
            GlassSystem.createBlurView(
                activity, blurTarget, GlassSystem.Preset.TABS_BUTTON,
                activity.resources.displayMetrics.density, isNight
            ).apply {
                isClickable = false
                isFocusable = false
            },
            FrameLayout.LayoutParams(-1, -1)
        )

        val label = TextView(activity).apply {
            this.text = text
            textSize = Constants.TEXT_SIZE_XL
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.safariButtonBlue(isNight))
            gravity = Gravity.CENTER
            setPadding(dp(paddingH), dp(paddingV), dp(paddingH), dp(paddingV))
            setOnClickListener { onClick() }
        }
        frame.addView(label, FrameLayout.LayoutParams(-1, -1))
        AnimationSystem.attachGlassPress(label, frame)
        return frame
    }

    private fun createCircleIconButton(
        symbol: String,
        iconColor: Int,
        fillColor: Int,
        strokeColor: Int,
        textSize: Float,
        onClick: () -> Unit
    ): FrameLayout {
        val button = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fillColor)
                if (strokeColor != Color.TRANSPARENT) setStroke(dp(1), strokeColor)
            }
            clipChildren = false
            clipToPadding = false
            isClickable = true
            isFocusable = true
        }
        button.addView(TextView(activity).apply {
            text = symbol
            textSize = textSize
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(iconColor)
            setTypeface(null, android.graphics.Typeface.NORMAL)
        }, FrameLayout.LayoutParams(-1, -1))
        button.setOnClickListener { onClick() }
        AnimationSystem.attachGlassPress(button)
        return button
    }

    private fun createGlassPill(text: String): FrameLayout {
        val frame = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.argb(118, 255, 255, 255))
                setStroke(dp(1), Color.argb(100, 130, 130, 140))
            }
            clipChildren = false
            clipToPadding = false
        }
        frame.addView(GlassSystem.createBlurView(
            activity, blurTarget, GlassSystem.Preset.TABS_BUTTON,
            activity.resources.displayMetrics.density, false
        ).apply {
            isClickable = false
            isFocusable = false
        }, FrameLayout.LayoutParams(-1, -1))
        frame.addView(TextView(activity).apply {
            this.text = text
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
        }, FrameLayout.LayoutParams(-1, -1))
        return frame
    }

    fun show(animate: Boolean = true) {
        // Chụp lại tab hiện tại ngay lúc mở lưới, không để thumbnail của lần trước bị cũ.
        captureActiveTab()
        val popup = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        popup.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val container = FrameLayout(activity).apply {
            clipChildren = false
            clipToPadding = false
        }
        var closing = false
        fun dismissAnimated() {
            if (closing) return
            closing = true
            AnimationSystem.popOut(container, 0.9f) { popup.dismiss() }
        }

        val dialogBlur = GlassSystem.createBlurView(
            activity, blurTarget, GlassSystem.Preset.TABS_DIALOG,
            activity.resources.displayMetrics.density, false
        ).apply {
            isClickable = false
            isFocusable = false
        }
        container.addView(dialogBlur, FrameLayout.LayoutParams(-1, -1))

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(24), dp(12), dp(12))
            // Light, slightly rosy glass like Safari's tab overview; the browser itself keeps its theme.
            setBackgroundColor(Color.argb(178, 249, 243, 248))
            clipChildren = false
            clipToPadding = false
            isClickable = true
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(dp(12), insets.systemWindowInsetTop + dp(8), dp(12), dp(12))
                insets
            }
        }

        // Header gọn hơn để dành nhiều diện tích cho lưới tab kiểu Safari.
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(6))
        }
        val heading = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(TextView(activity).apply {
            text = "Tab đang mở"
            textSize = 19f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.BLACK)
        })
        header.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        // Hai cột độc lập trong cùng vùng cuộn: thẻ có thể cao thấp khác nhau như Safari.
        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(4))
            clipChildren = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val columns = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
        }
        val leftColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val rightColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        columns.addView(leftColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        columns.addView(rightColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        var leftHeight = 0
        var rightHeight = 0
        var cardIndex = 0
        val previewHeights = intArrayOf(174, 214, 188, 156, 226, 180)

        for (tab in tabManager.getAll()) {
            val thisIndex = cardIndex++
            val previewHeight = previewHeights[thisIndex % previewHeights.size]
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(9))
                clipChildren = false
                clipToPadding = false
                elevation = dp(2).toFloat()
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(22).toFloat()
                    setColor(Color.argb(218, 255, 255, 255))
                    setStroke(
                        dp(if (tab.isActive) 2 else 1),
                        if (tab.isActive) accentColor else Color.argb(145, 195, 195, 205)
                    )
                }
            }

            // The rounded thumbnail viewport clips only webpage pixels; Safari-style controls
            // are siblings above it, so their press animation can expand without being cut off.
            val preview = FrameLayout(activity).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.argb(28, 120, 120, 132))
                }
                clipChildren = false
                clipToPadding = false
            }
            val previewContent = FrameLayout(activity).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(250, 247, 250), Color.rgb(231, 227, 235))
                ).apply { cornerRadius = dp(14).toFloat() }
                clipToOutline = true
            }
            preview.addView(previewContent, FrameLayout.LayoutParams(-1, -1))

            val thumbnail = tab.thumbnail
            if (thumbnail != null && !thumbnail.isRecycled) {
                previewContent.addView(ImageView(activity).apply {
                    setImageBitmap(thumbnail)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.TRANSPARENT)
                }, FrameLayout.LayoutParams(-1, -1))
            } else {
                val domain = runCatching { Uri.parse(tab.url).host?.removePrefix("www.") ?: "S" }.getOrDefault("S")
                val hue = (tab.url.hashCode() and 0xFF) % 360
                val saturation = if (isNight) 0.5f else 0.6f
                val luminance = if (isNight) 0.6f else 0.45f
                val markColor = Color.HSVToColor(floatArrayOf(hue.toFloat(), saturation, luminance))
                previewContent.addView(TextView(activity).apply {
                    text = domain.take(1).uppercase()
                    textSize = 42f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(markColor)
                    gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(-1, -1))
            }

            if (!tab.isActive) {
                val close = TextView(activity).apply {
                    text = "×"
                    textSize = 23f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(Color.rgb(80, 80, 86))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.argb(142, 255, 255, 255))
                    }
                }
                preview.addView(close, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.END).apply {
                    setMargins(0, dp(5), dp(5), 0)
                })
                AnimationSystem.attachGlassPress(close)
                close.setOnClickListener {
                    onCloseTab(tab)
                    popup.dismiss()
                    tab.thumbnail?.recycle()
                    tab.thumbnail = null
                    tab.thumbnailUrl = null
                    show(false)
                }
            } else {
                val activeMark = TextView(activity).apply {
                    text = "✓"
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(accentColor)
                    }
                }
                preview.addView(activeMark, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.END).apply {
                    setMargins(0, dp(5), dp(5), 0)
                })
            }

            card.addView(preview, LinearLayout.LayoutParams(-1, dp(previewHeight)))
            card.addView(TextView(activity).apply {
                text = tab.title.ifBlank { tab.url.ifBlank { "Tab mới" } }
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.BLACK)
                maxLines = 1
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(3), dp(9), dp(3), 0)
            })
            card.addView(TextView(activity).apply {
                text = shortUrl(tab.url).ifBlank { store.homeUrl }
                textSize = 12f
                setTextColor(Theme.safariGray(false))
                maxLines = 1
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(3), dp(3), dp(3), 0)
            })

            card.setOnClickListener {
                if (!tab.isActive) onActivateTab(tab)
                dismissAnimated()
            }
            AnimationSystem.attachPress(card, card, 0.96f)
            card.alpha = 0f
            card.scaleX = 0.92f
            card.scaleY = 0.92f
            val delay = 35L + (thisIndex * 28L).coerceAtMost(240L)
            card.postDelayed({ AnimationSystem.popIn(card, 0.92f) }, delay)

            val params = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(4), dp(4), dp(4), dp(7))
            }
            if (leftHeight <= rightHeight) {
                leftColumn.addView(card, params)
                leftHeight += previewHeight + 68
            } else {
                rightColumn.addView(card, params)
                rightHeight += previewHeight + 68
            }
        }

        scroll.addView(columns)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // Thanh đáy cố định: tạo tab, số lượng tab và hoàn tất.
        val bottomBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(4))
            clipChildren = false
            clipToPadding = false
        }
        val addButton = createCircleIconButton(
            symbol = "+",
            iconColor = Theme.safariButtonBlue(false),
            fillColor = Color.argb(105, 255, 255, 255),
            strokeColor = Color.argb(135, 125, 125, 136),
            textSize = 27f
        ) {
            onCreateTab()
            dismissAnimated()
        }
        bottomBar.addView(addButton, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
            setMargins(dp(4), 0, dp(8), 0)
        })

        val countPill = createGlassPill("${tabManager.count()} tab")
        bottomBar.addView(countPill, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            setMargins(dp(8), 0, dp(8), 0)
        })

        val doneButton = createCircleIconButton(
            symbol = "✓",
            iconColor = Color.WHITE,
            fillColor = accentColor,
            strokeColor = Color.TRANSPARENT,
            textSize = 27f
        ) { dismissAnimated() }
        bottomBar.addView(doneButton, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
            setMargins(dp(8), 0, dp(4), 0)
        })
        root.addView(bottomBar)

        container.addView(root, FrameLayout.LayoutParams(-1, -1))
        root.clipChildren = false
        root.clipToPadding = false
        popup.setContentView(container)
        if (animate) container.alpha = 0f
        popup.show()
        if (animate) container.post { AnimationSystem.popIn(container, 0.88f) }
        popup.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.decorView.setOnApplyWindowInsetsListener { view, insets ->
                root.setPadding(dp(12), insets.systemWindowInsetTop + dp(8), dp(12), insets.systemWindowInsetBottom + dp(8))
                view.onApplyWindowInsets(insets)
            }
            window.decorView.requestApplyInsets()
        }
        popup.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

}
