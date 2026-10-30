package com.nova.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView

/**
 * NOVA launcher: wallpaper home, glass clock + system widgets, all apps
 * rendered as iOS-style black icons, and a floating glass dock.
 */
class MainActivity : Activity() {

    private val iconCache = HashMap<String, Drawable>()
    private lateinit var scroll: ScrollView
    private lateinit var column: LinearLayout
    private lateinit var grid: GridLayout
    private lateinit var dock: LinearLayout
    private lateinit var dockWrap: FrameLayout
    private lateinit var batteryValue: TextView
    private lateinit var batteryFill: View
    private lateinit var batteryRest: View
    private lateinit var storageValue: TextView
    private lateinit var storageFill: View
    private lateinit var storageRest: View

    private var insetTop = 0
    private var insetBottom = 0

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            setBar(batteryValue, batteryFill, batteryRest, if (scale > 0) level * 100 / scale else 0)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        setContentView(buildUi())
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        updateStorage()
        populateApps()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(batteryReceiver)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home button pressed while already on home: spring back to the top.
        scroll.smoothScrollTo(0, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // A launcher never closes on back.
    }

    // ------------------------------------------------------------------- UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun glass(radiusDp: Int, strong: Boolean = false): GradientDrawable {
        val top = if (strong) Color.argb(70, 255, 255, 255) else Color.argb(56, 255, 255, 255)
        val bottom = if (strong) Color.argb(26, 255, 255, 255) else Color.argb(18, 255, 255, 255)
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(top, bottom)).apply {
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), Color.argb(51, 255, 255, 255))
        }
    }

    private fun label(text: String, sizeSp: Float, color: Int, tf: String = "sans-serif"): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            typeface = Typeface.create(tf, Typeface.NORMAL)
        }

    private fun buildUi(): View {
        val root = FrameLayout(this)

        scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(130))
        }
        scroll.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        column.addView(buildClockCard())
        column.addView(buildWidgetRow(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        grid = GridLayout(this).apply { columnCount = 4 }
        column.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(20) })

        // Floating dock
        dockWrap = FrameLayout(this)
        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass(36, strong = true)
            setPadding(dp(10), 0, dp(10), 0)
        }
        dockWrap.addView(dock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(86)))
        root.addView(dockWrap, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ).apply { leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(16) })

        @Suppress("DEPRECATION")
        root.setOnApplyWindowInsetsListener { _, insets ->
            insetTop = insets.systemWindowInsetTop
            insetBottom = insets.systemWindowInsetBottom
            column.setPadding(dp(16), insetTop + dp(12), dp(16), insetBottom + dp(130))
            (dockWrap.layoutParams as FrameLayout.LayoutParams).bottomMargin = insetBottom + dp(12)
            dockWrap.requestLayout()
            insets
        }
        return root
    }

    private fun buildClockCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(32)
            setPadding(dp(22), dp(18), dp(22), dp(18))
        }
        val date = TextClock(this).apply {
            format12Hour = "EEEE · MMM d"
            format24Hour = "EEEE · MMM d"
            textSize = 11f
            isAllCaps = true
            letterSpacing = 0.14f
            setTextColor(Color.argb(190, 255, 255, 255))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val time = TextClock(this).apply {
            format12Hour = "HH:mm"
            format24Hour = "HH:mm"
            textSize = 76f
            letterSpacing = -0.04f
            includeFontPadding = false
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        }
        card.addView(date)
        card.addView(time)
        return card
    }

    private fun statCard(title: String): Triple<View, TextView, Pair<View, View>> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(26)
            setPadding(dp(18), dp(14), dp(18), dp(16))
        }
        card.addView(label(title, 12f, Color.argb(190, 255, 255, 255), "sans-serif-medium"))
        val value = label("--", 30f, Color.WHITE, "sans-serif-light")
        card.addView(value)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val fill = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(60, 224, 255), Color.rgb(165, 139, 255))
            ).apply { cornerRadius = dp(4).toFloat() }
        }
        val rest = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(Color.argb(46, 255, 255, 255))
            }
        }
        bar.addView(fill, LinearLayout.LayoutParams(0, dp(8), 50f))
        bar.addView(rest, LinearLayout.LayoutParams(0, dp(8), 50f))
        card.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        return Triple(card, value, Pair(fill, rest))
    }

    private fun buildWidgetRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val (batCard, batValue, batBar) = statCard("BATTERY")
        val (stoCard, stoValue, stoBar) = statCard("STORAGE")
        batteryValue = batValue; batteryFill = batBar.first; batteryRest = batBar.second
        storageValue = stoValue; storageFill = stoBar.first; storageRest = stoBar.second
        row.addView(batCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        row.addView(stoCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        return row
    }

    private fun setBar(value: TextView, fill: View, rest: View, percent: Int) {
        val p = percent.coerceIn(0, 100)
        value.text = "$p %"
        (fill.layoutParams as LinearLayout.LayoutParams).weight = p.coerceAtLeast(1).toFloat()
        (rest.layoutParams as LinearLayout.LayoutParams).weight = (100 - p).coerceAtLeast(1).toFloat()
        fill.requestLayout()
        rest.requestLayout()
    }

    private fun updateStorage() {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.totalBytes
        val used = if (total > 0) ((total - stat.availableBytes) * 100 / total).toInt() else 0
        setBar(storageValue, storageFill, storageRest, used)
    }

    // ------------------------------------------------------------------ apps

    private fun populateApps() {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        grid.removeAllViews()
        val cellWidth = (resources.displayMetrics.widthPixels - dp(32)) / 4
        apps.forEachIndexed { index, ri ->
            val cell = appCell(ri, cellWidth, showLabel = true)
            val lp = GridLayout.LayoutParams().apply {
                width = cellWidth
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            grid.addView(cell, lp)
            cell.alpha = 0f
            cell.translationY = dp(16).toFloat()
            cell.animate().alpha(1f).translationY(0f)
                .setStartDelay(minOf(index * 12L, 300L)).setDuration(600)
                .setInterpolator(OvershootInterpolator(1.4f)).start()
        }

        dock.removeAllViews()
        val dockIntents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://"))
        )
        val picked = LinkedHashSet<String>()
        dockIntents.forEach { i ->
            val pkg = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
            if (pkg != null && pkg != "android") picked.add(pkg)
        }
        picked.forEach { pkg ->
            val ri = apps.firstOrNull { it.activityInfo.packageName == pkg } ?: return@forEach
            val cell = appCell(ri, 0, showLabel = false)
            dock.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun appCell(ri: ResolveInfo, width: Int, showLabel: Boolean): LinearLayout {
        val pm = packageManager
        val pkg = ri.activityInfo.packageName
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(6))
        }
        val icon = ImageView(this).apply { setImageDrawable(darkIcon(ri)) }
        cell.addView(icon, LinearLayout.LayoutParams(dp(62), dp(62)))
        if (showLabel) {
            val tv = label(ri.loadLabel(pm).toString(), 11f, Color.WHITE, "sans-serif-medium").apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setShadowLayer(6f, 0f, 1f, Color.argb(110, 0, 0, 0))
                setPadding(dp(4), dp(6), dp(4), 0)
            }
            cell.addView(tv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        cell.setOnClickListener { launch(icon, pkg) }
        cell.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.9f).scaleY(0.9f).setStartDelay(0).setDuration(120)
                        .setInterpolator(null).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(450)
                        .setInterpolator(OvershootInterpolator(3f)).start()
            }
            false
        }
        return cell
    }

    private fun launch(from: View, pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        val options = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height)
        startActivity(intent, options.toBundle())
    }

    // ------------------------------------------------------------- dark icons

    /** Renders any app icon onto a black iOS-style squircle with a thin rim and top sheen. */
    private fun darkIcon(ri: ResolveInfo): Drawable {
        val key = ri.activityInfo.packageName
        iconCache[key]?.let { return it }

        val s = 192
        val sf = s.toFloat()
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val radius = sf * 0.32f
        val path = Path().apply { addRoundRect(RectF(0f, 0f, sf, sf), radius, radius, Path.Direction.CW) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.shader = LinearGradient(0f, 0f, 0f, sf, Color.rgb(36, 36, 40), Color.rgb(5, 5, 7), Shader.TileMode.CLAMP)
        c.drawPath(path, paint)
        paint.shader = null

        val src = ri.loadIcon(packageManager)
        c.save()
        c.clipPath(path)
        val fg: Drawable? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && src is AdaptiveIconDrawable) src.foreground else null
        when {
            fg != null && luminance(fg) > 0.25f -> {
                // Light glyph: sits nicely on black.
                val e = (sf * 0.04f).toInt()
                fg.setBounds(-e, -e, s + e, s + e)
                fg.draw(c)
            }
            fg != null -> {
                // Dark glyph would vanish on black: keep the app's own coloured tile.
                src.setBounds(0, 0, s, s)
                src.draw(c)
            }
            else -> {
                val m = (sf * 0.2f).toInt()
                src.setBounds(m, m, s - m, s - m)
                src.draw(c)
            }
        }
        c.restore()

        // top sheen
        paint.shader = LinearGradient(
            0f, 0f, 0f, sf * 0.5f, Color.argb(46, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP
        )
        c.save()
        c.clipPath(path)
        c.drawRect(0f, 0f, sf, sf * 0.5f, paint)
        c.restore()
        paint.shader = null

        // rim
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.argb(46, 255, 255, 255)
        c.drawPath(path, paint)

        val d = BitmapDrawable(resources, bmp)
        iconCache[key] = d
        return d
    }

    private fun luminance(d: Drawable): Float {
        val size = 32
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        d.setBounds(0, 0, size, size)
        d.draw(cv)
        var sum = 0f
        var n = 0
        for (y in 0 until size) for (x in 0 until size) {
            val px = b.getPixel(x, y)
            if (Color.alpha(px) > 128) {
                sum += (0.299f * Color.red(px) + 0.587f * Color.green(px) + 0.114f * Color.blue(px)) / 255f
                n++
            }
        }
        return if (n == 0) 1f else sum / n
    }
}
