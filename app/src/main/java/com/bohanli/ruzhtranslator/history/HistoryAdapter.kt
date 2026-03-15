package com.bohanli.ruzhtranslator.history

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bohanli.ruzhtranslator.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val onClick: (TranslationRecord) -> Unit,
    private val onLongClick: (TranslationRecord) -> Unit,
    private val onFavoriteToggle: (TranslationRecord) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    var records: List<TranslationRecord> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    var isMultiSelectMode = false
        set(value) {
            field = value
            if (!value) selectedIds.clear()
            notifyDataSetChanged()
        }

    val selectedIds = mutableSetOf<Int>()

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(records[position])
    }

    override fun getItemCount() = records.size

    fun selectAll() {
        selectedIds.clear()
        selectedIds.addAll(records.map { it.id })
        notifyDataSetChanged()
    }

    fun deselectAll() {
        selectedIds.clear()
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_title)
        private val tvPreviewZh: TextView = itemView.findViewById(R.id.tv_preview_zh)
        private val tvPreviewRu: TextView = itemView.findViewById(R.id.tv_preview_ru)
        private val cbSelect: CheckBox = itemView.findViewById(R.id.cb_select)
        private val btnFavorite: ImageButton = itemView.findViewById(R.id.btn_favorite)

        fun bind(record: TranslationRecord) {
            // Title: custom name or "MM-dd HH:mm XX" (time + max 2 Chinese chars)
            val timeStr = dateFormat.format(Date(record.createTime))
            val zhShort = record.zhText.replace("\n", "").take(2)
            val displayName = record.customName ?: "$timeStr $zhShort"
            tvTitle.text = displayName

            tvPreviewZh.text = record.zhText.take(30).replace("\n", " ")
            tvPreviewRu.text = record.ruText.take(50).replace("\n", " ")

            // Favorite star
            btnFavorite.setImageResource(
                if (record.isFavorite) R.drawable.ic_star_filled
                else R.drawable.ic_star_outline
            )
            btnFavorite.setOnClickListener { onFavoriteToggle(record) }

            // Multi-select checkbox (right side)
            cbSelect.visibility = if (isMultiSelectMode) View.VISIBLE else View.GONE
            cbSelect.setOnCheckedChangeListener(null)
            cbSelect.isChecked = record.id in selectedIds
            cbSelect.setOnCheckedChangeListener { _, checked ->
                if (checked) selectedIds.add(record.id) else selectedIds.remove(record.id)
            }

            itemView.setOnClickListener {
                if (isMultiSelectMode) {
                    cbSelect.toggle()
                } else {
                    onClick(record)
                }
            }

            itemView.setOnLongClickListener {
                onLongClick(record)
                true
            }
        }
    }
}
