package com.king.afsexplorer

import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.king.afsexplorer.format.AfsFile
import java.io.File

/**
 * Handles the "regeneration" workflow: pick replacement files for one or
 * more entries, then rebuild the AFS with new block-aligned slots.
 *
 * This mirrors AFSExplorer's rebuild window, minus the manual +/- slot
 * adjustment UI (entries are auto-sized to fit on rebuild, which is the
 * safe default -- no risk of overlapping next file's data).
 */
class RebuildActivity : AppCompatActivity() {

    private var afs: AfsFile? = null
    private var afsPath: String? = null
    private val pendingReplacements = HashMap<Int, ByteArray>()
    private var selectedIndex: Int = -1

    private lateinit var adapter: EntryAdapter
    private lateinit var tvStatus: TextView

    private val pickReplacementLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { applyReplacement(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rebuild)

        tvStatus = findViewById(R.id.tvStatus)
        afsPath = intent.getStringExtra("afsPath")

        if (afsPath == null) {
            Toast.makeText(this, "لم يتم تحميل ملف AFS", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        afs = AfsFile.open(afsPath!!)

        val recycler = findViewById<RecyclerView>(R.id.recyclerReplace)
        adapter = EntryAdapter(
            afs!!.entries,
            onClick = { idx, _ ->
                selectedIndex = idx
                tvStatus.text = "محدد: ${afs!!.entries[idx].name} — اضغط 'اختر ملف بديل'"
            },
            onLongClick = { _, _ -> }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<MaterialButton>(R.id.btnPickReplacement).setOnClickListener {
            if (selectedIndex < 0) {
                Toast.makeText(this, "اختر عنصر من القائمة أولاً", Toast.LENGTH_SHORT).show()
            } else {
                pickReplacementLauncher.launch(arrayOf("*/*"))
            }
        }

        findViewById<MaterialButton>(R.id.btnRebuildNow).setOnClickListener {
            performRebuild()
        }
    }

    private fun applyReplacement(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return
            pendingReplacements[selectedIndex] = bytes
            val entry = afs!!.entries[selectedIndex]

            val fitsInPlace = bytes.size.toLong() <= entry.reservedSize
            tvStatus.text = if (fitsInPlace) {
                "✓ ${entry.name}: الملف الجديد يدخل ضمن المساحة المحجوزة (${bytes.size}B / ${entry.reservedSize}B) — لا حاجة لإعادة بناء كاملة"
            } else {
                "⚠ ${entry.name}: الملف الجديد أكبر من المساحة المحجوزة (${bytes.size}B > ${entry.reservedSize}B) — يتطلب إعادة بناء كاملة"
            }
        } catch (e: Exception) {
            Toast.makeText(this, "فشل قراءة الملف البديل: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performRebuild() {
        if (pendingReplacements.isEmpty()) {
            Toast.makeText(this, "لا توجد استبدالات لتطبيقها", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Fast path: every replacement fits in its existing slot ->
            // write in place, no regeneration needed (matches AFSExplorer's
            // "partial regeneration" optimization).
            val allFitInPlace = pendingReplacements.all { (idx, data) ->
                data.size.toLong() <= afs!!.entries[idx].reservedSize
            }

            if (allFitInPlace) {
                pendingReplacements.forEach { (idx, data) ->
                    afs!!.replaceInPlace(idx, data)
                }
                tvStatus.text = "✓ تم الاستبدال داخل الملف الأصلي بدون إعادة بناء كاملة."
                Toast.makeText(this, "تم التحديث في نفس الملف", Toast.LENGTH_LONG).show()
            } else {
                val outDir = File(getExternalFilesDir(null), "rebuilt")
                outDir.mkdirs()
                val outPath = File(outDir, "rebuilt_${System.currentTimeMillis()}.afs").absolutePath

                afs!!.rebuildWithReplacement(outPath, pendingReplacements)

                tvStatus.text = "✓ تم إعادة البناء الكاملة:\n$outPath"
                Toast.makeText(this, "تم إنشاء ملف AFS جديد", Toast.LENGTH_LONG).show()
            }

            pendingReplacements.clear()
            adapter.update(afs!!.entries)

        } catch (e: Exception) {
            Toast.makeText(this, "فشلت إعادة البناء: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
