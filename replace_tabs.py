from pathlib import Path
import shutil

p = Path("app/src/main/java/com/example/webedge/MainActivity.kt")
s = p.read_text()

start_marker = "    private fun openTabSwitcher() {"
start = s.find(start_marker)
if start < 0:
    raise SystemExit("LOI: Khong tim thay openTabSwitcher(); dung, khong sua file.")

end_marker = "\n    }\n\n\n}"
end = s.find(end_marker, start)
if end < 0:
    raise SystemExit("LOI: Khong tim thay moc ket thuc; dung, khong sua file.")

backup = Path(str(p) + ".before-grid")
if not backup.exists():
    shutil.copy2(p, backup)

new_func = r'''    private fun openTabSwitcher() {
        val popup = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        popup.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val container = FrameLayout(this)
        container.setBackgroundColor(Color.argb(210, 0, 0, 0))

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(24), dp(12), dp(12))
        root.setBackgroundColor(if (isNight) Color.rgb(24, 24, 28) else Color.rgb(245, 245, 250))
        root.isClickable = true

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(8), dp(8), dp(8), dp(16))

        val heading = LinearLayout(this)
        heading.orientation = LinearLayout.VERTICAL

        val title = TextView(this)
        title.text = "Tab đang mở"
        title.textSize = 25f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(fg)
        heading.addView(title)

        val count = TextView(this)
        count.text = "${tabManager.count()} tab"
        count.textSize = 13f
        count.setTextColor(sub)
        heading.addView(count)

        header.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val done = TextView(this)
        done.text = "Xong"
        done.textSize = 16f
        done.setTypeface(null, Typeface.BOLD)
        done.setTextColor(accent)
        done.gravity = Gravity.CENTER
        done.setPadding(dp(12), dp(10), dp(8), dp(10))
        done.setOnClickListener { popup.dismiss() }
        header.addView(done)
        root.addView(header)

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL

        val tabs = tabManager.getAll()
        for (chunk in tabs.chunked(2)) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.TOP

            for (tab in chunk) {
                val card = LinearLayout(this)
                card.orientation = LinearLayout.VERTICAL
                card.setPadding(dp(7), dp(7), dp(7), dp(9))
                card.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(18).toFloat()
                    setColor(if (isNight) Color.rgb(42, 42, 48) else Color.WHITE)
                    setStroke(
                        dp(if (tab.isActive) 2 else 1),
                        if (tab.isActive) accent else
                            if (isNight) Color.rgb(75, 75, 82) else Color.rgb(225, 225, 232)
                    )
                }

                val preview = FrameLayout(this)
                preview.background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    if (isNight)
                        intArrayOf(Color.rgb(63, 67, 82), Color.rgb(35, 39, 52))
                    else
                        intArrayOf(Color.rgb(225, 232, 246), Color.rgb(190, 204, 231))
                ).apply {
                    cornerRadius = dp(13).toFloat()
                }

                val mark = TextView(this)
                mark.text = "S"
                mark.textSize = 46f
                mark.setTypeface(null, Typeface.BOLD)
                mark.setTextColor(if (isNight) Color.rgb(210, 218, 245) else Color.rgb(75, 91, 130))
                mark.gravity = Gravity.CENTER
                preview.addView(mark, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))

                if (!tab.isActive) {
                    val close = TextView(this)
                    close.text = "×"
                    close.textSize = 23f
                    close.gravity = Gravity.CENTER
                    close.setTextColor(Color.WHITE)
                    close.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.argb(190, 35, 35, 40))
                    }
                    preview.addView(close, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.END).apply {
                        setMargins(0, dp(5), dp(5), 0)
                    })
                    close.setOnClickListener {
                        tabManager.close(tab.id)
                        popup.dismiss()
                        val active = tabManager.getActive()
                        if (active != null) load(active.url.ifBlank { store.homeUrl })
                        openTabSwitcher()
                    }
                } else {
                    val activeMark = TextView(this)
                    activeMark.text = "✓"
                    activeMark.textSize = 15f
                    activeMark.gravity = Gravity.CENTER
                    activeMark.setTextColor(Color.WHITE)
                    activeMark.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(accent)
                    }
                    preview.addView(activeMark, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END).apply {
                        setMargins(0, dp(5), dp(5), 0)
                    })
                }

                card.addView(preview, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(122)
                ))

                val tabTitle = TextView(this)
                tabTitle.text = tab.title.ifBlank { tab.url.ifBlank { "Tab mới" } }
                tabTitle.textSize = 14f
                tabTitle.setTypeface(null, Typeface.BOLD)
                tabTitle.setTextColor(fg)
                tabTitle.maxLines = 1
                tabTitle.setSingleLine(true)
                tabTitle.ellipsize = android.text.TextUtils.TruncateAt.END
                tabTitle.setPadding(dp(3), dp(9), dp(3), 0)
                card.addView(tabTitle)

                val url = TextView(this)
                url.text = shortUrl(tab.url).ifBlank { store.homeUrl }
                url.textSize = 11f
                url.setTextColor(sub)
                url.maxLines = 1
                url.setSingleLine(true)
                url.ellipsize = android.text.TextUtils.TruncateAt.END
                url.setPadding(dp(3), dp(3), dp(3), 0)
                card.addView(url)

                card.setOnClickListener {
                    if (!tab.isActive) {
                        tabManager.switchTo(tab.id)
                        load(tab.url.ifBlank { store.homeUrl })
                    }
                    popup.dismiss()
                }

                row.addView(card, LinearLayout.LayoutParams(
                    0, dp(205), 1f
                ).apply {
                    setMargins(dp(5), dp(5), dp(5), dp(7))
                })
            }

            if (chunk.size == 1) {
                row.addView(View(this), LinearLayout.LayoutParams(0, dp(205), 1f).apply {
                    setMargins(dp(5), dp(5), dp(5), dp(7))
                })
            }
            grid.addView(row)
        }

        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val add = TextView(this)
        add.text = "+  Tab mới"
        add.textSize = 16f
        add.setTypeface(null, Typeface.BOLD)
        add.setTextColor(accent)
        add.gravity = Gravity.CENTER
        add.setPadding(dp(12), dp(17), dp(12), dp(12))
        add.setOnClickListener {
            tabManager.addNew(url = store.homeUrl, title = "Tab mới")
            load(store.homeUrl)
            popup.dismiss()
        }
        root.addView(add)

        root.setOnClickListener { }
        container.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        popup.setContentView(container)
        popup.show()
        popup.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        )
        popup.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }'''

s = s[:start] + new_func + s[end + len("\n    }"):]
p.write_text(s)
print("OK: Da thay openTabSwitcher()")
print("Backup:", backup)
