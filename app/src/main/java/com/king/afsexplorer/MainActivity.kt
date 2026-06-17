package com.king.afsexplorer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.king.afsexplorer.format.AfsCoherenceException
import com.king.afsexplorer.format.AfsFile
import java.io.File

class MainActivity : AppCompatActivity() {

    private var currentAfs: AfsFile? = null
    private var workingCopyPath: String? = null
    private lateinit var adapter: EntryAdapter
    private lateinit var tvFileInfo: TextView

    private val pickAfsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { loadAfsFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvFileInfo = findViewById(R.id.tvFileInfo)
        val recycler = findViewById<RecyclerView>(R.id.recyclerEntries)
        val btnOpen = findViewById<MaterialButton>(R.id.btnOpen)
        val btnExtractAll = findViewById<MaterialButton>(R.id.btnExtractAll)
        val btnRebuild = findViewById<MaterialButton>(R.id.btnRebuild)

        adapter = EntryAdapter(
            mutableListOf(),
            onClick = { idx, entry -> extractSingle(idx, entry) },
            onLongClick = { idx, entry -> showEntryOptions(idx, entry) }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        btnOpen.setOnClickListener {
            pickAfsLauncher.launch(arrayOf("*/*"))
        }

        btnExtractAll.setOnClickListener {
            extractAll()
        }

        btnRebuild.setOnClickListener {
            startActivity(Intent(this, RebuildActivity::class.java).apply {
                putExtra("afsPath", workingCopyPath)
            })
        }

        findViewById<MaterialButton>(R.id.btnExtractAll).isEnabled = false
        findViewById<MaterialButton>(R.id.btnRebuild).isEnabled = false
    }

    private fun loadAfsFromUri(uri: Uri) {
        try {
            // Copy to app-private storage first -- AFS rebuild/replace needs
            // RandomAccessFile which doesn't work directly on content:// URIs.
            val tempFile = File(cacheDir, "working.afs")
            contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            workingCopyPath = tempFile.absolutePath

            val afs = AfsFile.open(tempFile.absolutePath)
            currentAfs = afs
            adapter.update(afs.entries)

            tvFileInfo.text = "ملفات: ${afs.entries.size}  •  حجم الحاوية: ${tempFile.length() / 1024} KB"
            findViewById<MaterialButton>(R.id.btnExtractAll).isEnabled = true
            findViewById<MaterialButton>(R.id.btnRebuild).isEnabled = true

        } catch (e: AfsCoherenceException) {
            // Mirrors AFSExplorer's "auto-regenerate?" prompt when descriptors
            // are missing or counts mismatch.
            Toast.makeText(
                this,
                "خطأ تماسك (Coherence): ${e.message}\nقد يحتاج الملف لإعادة بناء.",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "فشل فتح الملف: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun extractSingle(index: Int, entry: com.king.afsexplorer.format.AfsEntry) {
        val afs = currentAfs ?: return
        try {
            val outDir = File(getExternalFilesDir(null), "extracted")
            outDir.mkdirs()
            val data = afs.extractEntry(entry)
            val outFile = File(outDir, entry.name.ifBlank { "file_$index.bin" })
            outFile.writeBytes(data)
            Toast.makeText(this, "تم الاستخراج: ${outFile.absolutePath}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "فشل الاستخراج: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractAll() {
        val afs = currentAfs ?: return
        try {
            val outDir = File(getExternalFilesDir(null), "extracted_all")
            afs.extractAll(outDir.absolutePath) { done, total ->
                // could update a progress bar here
            }
            Toast.makeText(
                this,
                "تم استخراج ${afs.entries.size} ملف إلى:\n${outDir.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "فشل الاستخراج الكامل: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEntryOptions(index: Int, entry: com.king.afsexplorer.format.AfsEntry) {
        // Long-press hook: rename / replace / view hex.
        // Wired here so it's trivial to extend with a dialog later.
        Toast.makeText(this, "خيارات: ${entry.name} (استخدم زر إعادة البناء للاستبدال)", Toast.LENGTH_SHORT).show()
    }
}
