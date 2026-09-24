package com.voicemusic.app

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView

class SongRowAdapter(private val ctx: Context, private var items: List<Song>, private val playingId: () -> String?) : BaseAdapter() {
    fun update(newItems: List<Song>) { items = newItems; notifyDataSetChanged() }
    override fun getCount() = items.size
    override fun getItem(i: Int) = items[i]
    override fun getItemId(i: Int) = i.toLong()

    class Holder(val title: TextView, val sub: TextView, val trail: TextView, val root: LinearLayout)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val ctxLocal = ctx
        val v: View; val h: Holder
        if (convertView == null) {
            val pad = Ui.dp(ctxLocal, 14)
            val title = Ui.textView(ctxLocal, "", 16f, true)
            val sub = Ui.textView(ctxLocal, "", 13f, false, Color.argb(255, 175, 180, 190))
            val textCol = Ui.col(ctxLocal, title, sub).apply { layoutParams = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            val trail = Ui.textView(ctxLocal, "", 13f, false, Color.argb(255, 200, 190, 140)).apply { gravity = Gravity.END }
            val root = LinearLayout(ctxLocal).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(textCol); addView(trail, Ui.lp())
            }
            h = Holder(title, sub, trail, root); root.tag = h; v = root
        } else { v = convertView; h = v.tag as Holder }
        val s = items[position]
        val playing = s.id == playingId()
        h.title.text = (if (playing) "\u25B6 " else "") + s.title
        h.title.setTextColor(if (playing) Color.rgb(255, 205, 100) else Color.WHITE)
        val bits = ArrayList<String>()
        bits.add(s.artist)
        if (s.album.isNotEmpty()) bits.add(s.album)
        h.sub.text = bits.joinToString(" \u2022 ")
        val trailBits = ArrayList<String>()
        if (s.favorite) trailBits.add("\u2665")
        if (s.rating > 0) trailBits.add(Ui.stars(s.rating))
        if (s.hasTrim) trailBits.add("\u2702")
        trailBits.add(fmtTime(s.duration))
        h.trail.text = trailBits.joinToString("  ")
        return v
    }
}

class GroupRowAdapter(private val ctx: Context, private var items: List<Group>) : BaseAdapter() {
    fun update(n: List<Group>) { items = n; notifyDataSetChanged() }
    override fun getCount() = items.size
    override fun getItem(i: Int) = items[i]
    override fun getItemId(i: Int) = i.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = convertView as? LinearLayout ?: run {
            val pad = Ui.dp(ctx, 14)
            val name = Ui.textView(ctx, "", 16f, true)
            val count = Ui.textView(ctx, "", 13f, false, Color.argb(255, 175, 180, 190)).apply { gravity = Gravity.END }
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(pad, pad, pad, pad)
                addView(name, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); addView(count)
                tag = Pair(name, count)
            }
        }
        @Suppress("UNCHECKED_CAST") val pair = v.tag as Pair<TextView, TextView>
        val name = pair.first; val count = pair.second
        val g = items[position]; name.text = g.name.ifEmpty { "(Unknown)" }; count.text = "${g.count}"
        return v
    }
}

class PlaylistRowAdapter(private val ctx: Context, private var items: List<Playlist>) : BaseAdapter() {
    fun update(n: List<Playlist>) { items = n; notifyDataSetChanged() }
    override fun getCount() = items.size
    override fun getItem(i: Int) = items[i]
    override fun getItemId(i: Int) = i.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = convertView as? LinearLayout ?: run {
            val pad = Ui.dp(ctx, 14)
            val name = Ui.textView(ctx, "", 16f, true)
            val count = Ui.textView(ctx, "", 13f, false, Color.argb(255, 175, 180, 190)).apply { gravity = Gravity.END }
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(pad, pad, pad, pad)
                addView(name, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); addView(count)
                tag = Pair(name, count)
            }
        }
        @Suppress("UNCHECKED_CAST") val pair = v.tag as Pair<TextView, TextView>
        val name = pair.first; val count = pair.second
        val p = items[position]; name.text = p.name; count.text = "${p.songIds.size}"
        return v
    }
}
