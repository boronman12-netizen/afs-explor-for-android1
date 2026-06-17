package com.king.afsexplorer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.king.afsexplorer.format.AfsEntry
import com.king.afsexplorer.format.AFS_BLOCK_SIZE

class EntryAdapter(
    private val entries: MutableList<AfsEntry>,
    private val onClick: (Int, AfsEntry) -> Unit,
    private val onLongClick: (Int, AfsEntry) -> Unit
) : RecyclerView.Adapter<EntryAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvDetails: TextView = view.findViewById(R.id.tvDetails)
    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_entry, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = entries[position]
        holder.tvName.text = entry.name

        val unused = entry.reservedSize - entry.size
        val wastedNote = if (unused > 0) "  •  مساحة غير مستخدمة: ${unused}B" else ""
        val coherenceNote = if (entry.reservedSize < entry.size) "  ⚠ تضارب" else ""

        holder.tvDetails.text =
            "حجم: ${formatSize(entry.size)}  •  Offset: 0x${entry.offset.toString(16)}$wastedNote$coherenceNote"

        holder.tvDetails.setTextColor(
            if (entry.reservedSize < entry.size) 0xFFFFA000.toInt() // yellow: coherence issue
            else if (unused > 0) 0xFF66BB6A.toInt() // green: unused reserved space
            else 0xFF888888.toInt()
        )

        holder.itemView.setOnClickListener { onClick(position, entry) }
        holder.itemView.setOnLongClickListener { onLongClick(position, entry); true }
    }

    override fun getItemCount() = entries.size

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1fKB".format(kb)
        return "%.2fMB".format(kb / 1024.0)
    }

    fun update(newEntries: List<AfsEntry>) {
        entries.clear()
        entries.addAll(newEntries)
        notifyDataSetChanged()
    }
}
