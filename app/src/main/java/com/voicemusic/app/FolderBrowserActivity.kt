package com.voicemusic.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.widget.ListView
import java.io.File

/**
 * A self-contained folder/file picker (own file-walking code, not the system SAF picker). Several
 * cheap head-unit ROMs ship a broken or missing document picker, which is one likely reason a
 * previous build worked on a tablet but not in the car - this avoids that dependency entirely.
 * Pass EXTRA_FILE_MODE=true to pick a single audio file instead of a folder (used for the custom
 * feedback sound picker).
 */
class FolderBrowserActivity : Activity() {
    companion object { const val EXTRA_PATH = "path"; const val EXTRA_FILE_MODE = "file_mode" }
    private lateinit var current: File
    private lateinit var pathLabel: android.widget.TextView
    private lateinit var list: ListView
    private var fileMode = false
    private val audioExts = setOf("mp3", "wav", "ogg", "oga", "flac", "m4a", "aac")

    private fun roots(): List<File> {
        val out = LinkedHashSet<File>()
        try { out.add(Environment.getExternalStorageDirectory()) } catch (e: Exception) { }
        try { for (f in getExternalFilesDirs(null)) { var d = f; while (d?.parentFile != null && d.parentFile!!.canRead()) d = d.parentFile; if (d != null) out.add(d) } } catch (e: Exception) { }
        out.add(File("/storage"))
        return out.filter { it.isDirectory }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fileMode = intent.getBooleanExtra(EXTRA_FILE_MODE, false)
        val root = Ui.col(this).apply { setBackgroundColor(Color.rgb(20, 20, 26)) }
        pathLabel = Ui.textView(this, "", 13f, false, Color.argb(255, 180, 185, 195)).apply { setPadding(Ui.dp(this@FolderBrowserActivity, 16), Ui.dp(this@FolderBrowserActivity, 14), Ui.dp(this@FolderBrowserActivity, 16), Ui.dp(this@FolderBrowserActivity, 8)) }
        list = ListView(this)
        val bar = Ui.row(this,
            Ui.button(this, "Storage roots") { showRoots() },
            Ui.button(this, "Up") { current.parentFile?.let { open(it) } })
        root.addView(bar); root.addView(pathLabel); root.addView(list, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (!fileMode) {
            root.addView(Ui.button(this, "Use this folder") {
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PATH, current.absolutePath)); finish()
            })
        } else {
            root.addView(Ui.textView(this, "Tap an audio file below to use it.", 12.5f, false, Color.GRAY).apply { setPadding(Ui.dp(this@FolderBrowserActivity,16), 0, Ui.dp(this@FolderBrowserActivity,16), Ui.dp(this@FolderBrowserActivity,10)) })
        }
        setContentView(root)
        val start = roots().firstOrNull() ?: Environment.getExternalStorageDirectory()
        open(start)
    }

    private fun showRoots() {
        val rs = roots()
        android.app.AlertDialog.Builder(this).setTitle("Storage").setItems(rs.map { it.absolutePath }.toTypedArray()) { _, i -> open(rs[i]) }.show()
    }

    private fun open(dir: File) {
        current = dir
        pathLabel.text = dir.absolutePath
        val folders = try { dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.sortedBy { it.name.lowercase() } ?: emptyList() } catch (e: Exception) { emptyList() }
        val files = if (!fileMode) emptyList() else try {
            dir.listFiles { f -> f.isFile && audioExts.contains(f.extension.lowercase()) }?.sortedBy { it.name.lowercase() } ?: emptyList()
        } catch (e: Exception) { emptyList() }
        val rows: List<Pair<Boolean, File>> = folders.map { true to it } + files.map { false to it }   // (isFolder, file)
        list.adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(i: Int) = rows[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup?): android.view.View {
                val tv = (convertView as? android.widget.TextView) ?: Ui.textView(this@FolderBrowserActivity, "", 16f).apply {
                    val p = Ui.dp(this@FolderBrowserActivity, 16); setPadding(p, p, p, p)
                }
                val (isFolder, f) = rows[position]
                tv.text = (if (isFolder) "[DIR]  " else "\u266A  ") + f.name          // plain text marker, not an emoji glyph
                return tv
            }
        }
        list.setOnItemClickListener { _, _, i, _ ->
            val (isFolder, f) = rows[i]
            if (isFolder) open(f) else { setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PATH, f.absolutePath)); finish() }
        }
    }
}
