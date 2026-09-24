package com.voicemusic.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/**
 * Small helpers for building the whole UI in code (no XML layouts). Kept deliberately plain -
 * standard platform widgets render predictably even on odd/low-end head-unit screens and skins,
 * where a custom-styled UI is more likely to come out wrong in ways nobody can preview beforehand.
 */
object Ui {
    fun dp(ctx: Context, v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
    fun sp(ctx: Context, v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics)

    fun col(a: Int, r: Int, g: Int, b: Int) = Color.argb(a, r, g, b)

    fun textView(ctx: Context, text: String = "", sizeSp: Float = 15f, bold: Boolean = false, color: Int = Color.WHITE): TextView =
        TextView(ctx).apply { this.text = text; textSize = sizeSp; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD) }

    fun button(ctx: Context, text: String, onClick: () -> Unit): Button =
        Button(ctx).apply { this.text = text; setOnClickListener { onClick() } }

    fun iconButton(ctx: Context, glyph: String, sizeSp: Float = 22f, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = glyph; textSize = sizeSp; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            val pad = dp(ctx, 12); setPadding(pad, pad, pad, pad)
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }

    fun row(ctx: Context, vararg views: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        for (v in views) addView(v)
    }

    fun col(ctx: Context, vararg views: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        for (v in views) addView(v)
    }

    fun lp(w: Int = ViewGroup.LayoutParams.WRAP_CONTENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    fun scroll(ctx: Context, inner: View): ScrollView = ScrollView(ctx).apply { addView(inner); isFillViewport = true }

    fun sectionLabel(ctx: Context, text: String): TextView = textView(ctx, text.uppercase(), 12.5f, true, Color.argb(255, 150, 160, 175)).apply {
        val p = dp(ctx, 16); setPadding(p, dp(ctx, 18), p, dp(ctx, 6))
    }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(Color.argb(60, 255, 255, 255))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1))
    }

    fun toast(ctx: Context, msg: String) { Bg.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() } }

    fun confirm(ctx: Context, title: String, message: String, okText: String = "OK", onOk: () -> Unit) {
        android.app.AlertDialog.Builder(ctx).setTitle(title).setMessage(message)
            .setPositiveButton(okText) { d, _ -> d.dismiss(); onOk() }
            .setNegativeButton("Cancel", null).show()
    }

    fun prompt(ctx: Context, title: String, initial: String, hint: String = "", onOk: (String) -> Unit) {
        val input = EditText(ctx).apply { setText(initial); this.hint = hint; setSelection(initial.length) }
        val pad = dp(ctx, 20)
        val wrap = FrameLayout(ctx).apply { setPadding(pad, dp(ctx, 12), pad, 0); addView(input) }
        android.app.AlertDialog.Builder(ctx).setTitle(title).setView(wrap)
            .setPositiveButton("OK") { d, _ -> onOk(input.text.toString()); d.dismiss() }
            .setNegativeButton("Cancel", null).show()
    }

    fun stars(rating: Int, max: Int = 5): String {
        val r = rating.coerceIn(0, max)
        return "\u2605".repeat(r) + "\u2606".repeat((max - r).coerceAtLeast(0))
    }
}
