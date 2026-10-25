package com.example.webedge

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

// Dữ liệu đường vẽ (viewport 24x24) cho các biểu tượng trong menu
object Icons {
    const val SHARE =
        "M18,16.08c-0.76,0 -1.44,0.3 -1.96,0.77L8.91,12.7c0.05,-0.23 0.09,-0.46 0.09,-0.7s-0.04,-0.47 -0.09,-0.7l7.05,-4.11c0.54,0.5 1.25,0.81 2.04,0.81 1.66,0 3,-1.34 3,-3s-1.34,-3 -3,-3 -3,1.34 -3,3c0,0.24 0.04,0.47 0.09,0.7L8.04,9.81C7.5,9.31 6.79,9 6,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3c0.79,0 1.5,-0.31 2.04,-0.81l7.12,4.16c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.61 1.31,2.92 2.92,2.92 1.61,0 2.92,-1.31 2.92,-2.92s-1.31,-2.92 -2.92,-2.92z"
    const val BOOKMARK_OUTLINE =
        "M17,3L7,3c-1.1,0 -2,0.9 -2,2v16l7,-3 7,3L19,5c0,-1.1 -0.9,-2 -2,-2zM17,18l-5,-2.18L7,18L7,5h10v13z"
    const val BOOKMARK =
        "M17,3L7,3c-1.1,0 -2,0.9 -2,2v16l7,-3 7,3L19,5c0,-1.1 -0.9,-2 -2,-2z"
    const val REFRESH =
        "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z"
    const val HISTORY =
        "M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08L13.5,8L12,8z"
    const val DOWNLOAD =
        "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z"
    const val SEARCH =
        "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z"
    const val DESKTOP =
        "M21,2L3,2c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h7v2L8,20v2h8v-2h-2v-2h7c1.1,0 2,-0.9 2,-2L23,4c0,-1.1 -0.9,-2 -2,-2zM21,16L3,16L3,4h18v12z"
    const val CHECK =
        "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z"
    const val ARROW_BACK =
        "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"
    const val MORE_HORIZ =
        "M6,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM18,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z"
    const val ARROW_UP =
        "M7.41,15.41L12,10.83l4.59,4.58L18,14l-6,-6 -6,6z"
    const val ARROW_DOWN =
        "M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6 1.41,-1.41z"
    const val TUNE =
        "M3,17v2h6v-2L3,17zM3,5v2h10L13,5L3,5zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2L3,11v2h4v2h2L9,9L7,9zM21,13v-2L11,11v2h10zM15,9h2L17,7h4L21,5h-4L17,3h-2v6z"
}

// Vẽ một biểu tượng từ dữ liệu đường vẽ, tự co giãn theo kích thước view
class PathIcon(pathData: String, color: Int) : Drawable() {
    private val path: Path? = try {
        PathParser.createPathFromPathData(pathData)
    } catch (e: Exception) {
        null
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        paint.color = color
        paint.style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas) {
        val p = path ?: return
        val b = bounds
        val sc = b.width() / 24f
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(sc, sc)
        canvas.drawPath(p, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
