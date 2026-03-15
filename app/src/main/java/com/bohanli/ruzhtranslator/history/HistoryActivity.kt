package com.bohanli.ruzhtranslator.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.bohanli.ruzhtranslator.R
import com.bohanli.ruzhtranslator.databinding.ActivityHistoryBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var dao: TranslationDao
    private lateinit var adapter: HistoryAdapter
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 0 = history tab, 1 = favorites tab
    private var currentTab = 0
    private var currentTimeRange = 0  // 0=all, 1=week, 2=month
    private var currentSortOrder = 0  // 0=placeholder(newest), 1=newest, 2=oldest

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dao = AppDatabase.getInstance(this).translationDao()
        currentTab = intent.getIntExtra("tab", 0)

        setupRecyclerView()
        setupToolbar()
        setupFilters()
        setupCleanup()
        setupMultiSelect()
        setupTabs()

        loadData()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun setupRecyclerView() {
        adapter = HistoryAdapter(
            onClick = { record -> showDetail(record) },
            onLongClick = { record -> showRenameDialog(record) },
            onFavoriteToggle = { record -> toggleFavorite(record) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun setupTabs() {
        updateTabAppearance()

        binding.tabHistory.setOnClickListener {
            if (currentTab != 0) {
                currentTab = 0
                updateTabAppearance()
                exitMultiSelect()
                loadData()
            }
        }
        binding.tabFavorites.setOnClickListener {
            if (currentTab != 1) {
                currentTab = 1
                updateTabAppearance()
                exitMultiSelect()
                loadData()
            }
        }
    }

    private fun updateTabAppearance() {
        if (currentTab == 0) {
            binding.tabHistory.setTextColor(0xFFFFFFFF.toInt())
            binding.tabFavorites.setTextColor(0xFF888888.toInt())
            binding.tvTitle.text = getString(R.string.history_title)
        } else {
            binding.tabHistory.setTextColor(0xFF888888.toInt())
            binding.tabFavorites.setTextColor(0xFFFFCC00.toInt())
            binding.tvTitle.text = getString(R.string.favorites_title)
        }
    }

    private fun setupFilters() {
        val timeAdapter = ArrayAdapter.createFromResource(
            this, R.array.time_range_options, R.layout.spinner_item
        ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        binding.spinnerTimeRange.adapter = timeAdapter

        val sortAdapter = ArrayAdapter.createFromResource(
            this, R.array.sort_options, R.layout.spinner_item
        ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        binding.spinnerSort.adapter = sortAdapter

        binding.spinnerTimeRange.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                currentTimeRange = pos
                loadData()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.spinnerSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == 0) return  // "排序" placeholder
                currentSortOrder = pos
                loadData()
                // Reset to show "排序" label
                binding.spinnerSort.setSelection(0)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupCleanup() {
        val cleanupAdapter = ArrayAdapter.createFromResource(
            this, R.array.cleanup_options, R.layout.spinner_item
        ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        binding.spinnerCleanup.adapter = cleanupAdapter

        binding.spinnerCleanup.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == 0) return
                val label = resources.getStringArray(R.array.cleanup_options)[pos]
                AlertDialog.Builder(this@HistoryActivity)
                    .setMessage(getString(R.string.cleanup_confirm, label))
                    .setPositiveButton("确认") { _, _ ->
                        // Second confirmation
                        AlertDialog.Builder(this@HistoryActivity)
                            .setMessage("再次确认：清理${label}的记录？此操作不可撤销。")
                            .setPositiveButton("确认清理") { _, _ -> performCleanup(pos) }
                            .setNegativeButton("取消") { _, _ ->
                                binding.spinnerCleanup.setSelection(0)
                            }
                            .setOnCancelListener { binding.spinnerCleanup.setSelection(0) }
                            .show()
                    }
                    .setNegativeButton("取消") { _, _ ->
                        binding.spinnerCleanup.setSelection(0)
                    }
                    .setOnCancelListener { binding.spinnerCleanup.setSelection(0) }
                    .show()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupMultiSelect() {
        binding.btnMultiSelect.setOnClickListener {
            if (adapter.isMultiSelectMode) exitMultiSelect()
            else enterMultiSelect()
        }

        binding.cbSelectAll.setOnCheckedChangeListener { _, checked ->
            if (checked) adapter.selectAll() else adapter.deselectAll()
            updateSelectedCount()
        }

        binding.btnActionDelete.setOnClickListener { deleteSelected() }
        binding.btnActionExport.setOnClickListener { exportSelected() }
        binding.btnActionCopy.setOnClickListener { copySelected() }
        binding.btnActionFavorite.setOnClickListener { favoriteSelected() }
    }

    private fun enterMultiSelect() {
        adapter.isMultiSelectMode = true
        binding.barActions.visibility = View.VISIBLE
        binding.cbSelectAll.isChecked = false
        updateSelectedCount()
    }

    private fun exitMultiSelect() {
        adapter.isMultiSelectMode = false
        binding.barActions.visibility = View.GONE
        binding.cbSelectAll.isChecked = false
    }

    private fun updateSelectedCount() {
        binding.tvSelectedCount.text = "${adapter.selectedIds.size}/${adapter.records.size}"
    }

    // ---- Data loading ----

    private fun loadData() {
        scope.launch {
            val sinceMs = getTimeSinceMs(currentTimeRange)
            val oldestFirst = currentSortOrder == 2
            val records = withContext(Dispatchers.IO) {
                if (currentTab == 0) {
                    when {
                        sinceMs == 0L && !oldestFirst -> dao.getAllHistory()
                        sinceMs == 0L && oldestFirst -> dao.getAllHistoryOldestFirst()
                        sinceMs > 0L && !oldestFirst -> dao.getHistorySince(sinceMs)
                        else -> dao.getHistorySinceOldestFirst(sinceMs)
                    }
                } else {
                    when {
                        sinceMs == 0L && !oldestFirst -> dao.getAllFavorites()
                        sinceMs == 0L && oldestFirst -> dao.getAllFavoritesOldestFirst()
                        sinceMs > 0L && !oldestFirst -> dao.getFavoritesSince(sinceMs)
                        else -> dao.getFavoritesSinceOldestFirst(sinceMs)
                    }
                }
            }
            adapter.records = records
            binding.tvEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerView.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE

            // Update tab counts
            val historyCount = withContext(Dispatchers.IO) { dao.getHistoryCount() }
            val favCount = withContext(Dispatchers.IO) { dao.getFavoritesCount() }
            binding.tabHistory.text = "${getString(R.string.tab_history)} ($historyCount)"
            binding.tabFavorites.text = "${getString(R.string.tab_favorites)} ($favCount)"
        }
    }

    private fun getTimeSinceMs(range: Int): Long {
        val now = System.currentTimeMillis()
        return when (range) {
            1 -> now - 7L * 24 * 60 * 60 * 1000
            2 -> now - 30L * 24 * 60 * 60 * 1000
            else -> 0L
        }
    }

    // ---- Item actions ----

    private fun toggleFavorite(record: TranslationRecord) {
        scope.launch {
            withContext(Dispatchers.IO) { dao.toggleFavorite(record.id) }
            loadData()
        }
    }

    private fun showDetail(record: TranslationRecord) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        // Build a custom dialog with copy/export in top area
        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        // Action row: export + copy buttons
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding(0, 0, 0, 16)
        }

        val btnExport = TextView(this).apply {
            text = "导出"
            setTextColor(0xFF66BBFF.toInt())
            textSize = 14f
            setPadding(24, 8, 24, 8)
            setOnClickListener {
                exportRecords(listOf(record))
            }
        }
        val btnCopy = TextView(this).apply {
            text = "复制"
            setTextColor(0xFF66BBFF.toInt())
            textSize = 14f
            setPadding(24, 8, 24, 8)
            setOnClickListener {
                copyToClipboard(formatRecordText(record))
                Toast.makeText(this@HistoryActivity, "已复制", Toast.LENGTH_SHORT).show()
            }
        }
        actionRow.addView(btnExport)
        actionRow.addView(btnCopy)
        contentLayout.addView(actionRow)

        // Scrollable content
        val scrollView = ScrollView(this)
        val tvContent = TextView(this).apply {
            text = "${dateFormat.format(Date(record.createTime))}\n\n" +
                "[俄语]\n${record.ruText}\n\n[中文]\n${record.zhText}"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 14f
        }
        scrollView.addView(tvContent)
        contentLayout.addView(scrollView)

        AlertDialog.Builder(this)
            .setTitle(record.customName ?: "翻译详情")
            .setView(contentLayout)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun showRenameDialog(record: TranslationRecord) {
        val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val zhShort = record.zhText.replace("\n", "").take(2)
        val currentName = record.customName ?: "${dateFormat.format(Date(record.createTime))} $zhShort"

        val input = EditText(this).apply {
            setText(currentName)
            setTextColor(0xFFDDDDDD.toInt())
            setHintTextColor(0xFF666666.toInt())
            hint = "输入自定义名称"
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("修改名称")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { null }
                scope.launch {
                    record.customName = name
                    withContext(Dispatchers.IO) { dao.update(record) }
                    loadData()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- Multi-select actions ----

    private fun deleteSelected() {
        val ids = adapter.selectedIds.toList()
        if (ids.isEmpty()) {
            Toast.makeText(this, "请先选择条目", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, ids.size))
            .setPositiveButton("确认") { _, _ ->
                // Second confirmation for multiple items
                if (ids.size > 1) {
                    AlertDialog.Builder(this)
                        .setMessage("再次确认：删除这 ${ids.size} 条记录？此操作不可撤销。")
                        .setPositiveButton("确认删除") { _, _ -> performDelete(ids) }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    performDelete(ids)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun performDelete(ids: List<Int>) {
        scope.launch {
            withContext(Dispatchers.IO) {
                if (currentTab == 0) {
                    // From history: clear isHistory flag
                    dao.clearHistoryFlag(ids)
                } else {
                    // From favorites: clear isFavorite flag
                    dao.clearFavoriteFlag(ids)
                }
                // Physical delete orphans (both flags false)
                dao.deleteOrphans(ids)
            }
            Toast.makeText(this@HistoryActivity,
                getString(R.string.deleted_count, ids.size), Toast.LENGTH_SHORT).show()
            exitMultiSelect()
            loadData()
        }
    }

    private fun copySelected() {
        val ids = adapter.selectedIds.toList()
        if (ids.isEmpty()) {
            Toast.makeText(this, "请先选择条目", Toast.LENGTH_SHORT).show()
            return
        }
        val records = adapter.records.filter { it.id in ids }
        if (records.isEmpty()) return

        val text = records.joinToString("\n\n") { formatRecordText(it) }
        copyToClipboard(text)
        Toast.makeText(this, "已复制 ${records.size} 条记录", Toast.LENGTH_SHORT).show()
    }

    private fun favoriteSelected() {
        val ids = adapter.selectedIds.toList()
        if (ids.isEmpty()) return
        scope.launch {
            withContext(Dispatchers.IO) { dao.setFavorite(ids) }
            Toast.makeText(this@HistoryActivity, "已收藏 ${ids.size} 条", Toast.LENGTH_SHORT).show()
            loadData()
        }
    }

    private fun exportSelected() {
        val ids = adapter.selectedIds.toList()
        if (ids.isEmpty()) {
            Toast.makeText(this, "请先选择条目", Toast.LENGTH_SHORT).show()
            return
        }
        val records = adapter.records.filter { it.id in ids }
        exportRecords(records)
    }

    // ---- Cleanup ----

    private fun performCleanup(option: Int) {
        val now = System.currentTimeMillis()
        val beforeMs = when (option) {
            1 -> now - 7L * 24 * 60 * 60 * 1000
            2 -> now - 30L * 24 * 60 * 60 * 1000
            3 -> now - 365L * 24 * 60 * 60 * 1000
            else -> return
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                if (currentTab == 0) {
                    dao.clearHistoryBefore(beforeMs)
                } else {
                    dao.clearFavoritesBefore(beforeMs)
                }
                dao.deleteOrphansBefore(beforeMs)
            }
            Toast.makeText(this@HistoryActivity,
                getString(R.string.cleaned_count), Toast.LENGTH_SHORT).show()
            binding.spinnerCleanup.setSelection(0)
            loadData()
        }
    }

    // ---- Utility ----

    private fun formatRecordText(record: TranslationRecord): String {
        return "[RU] ${record.ruText}\n[ZH] ${record.zhText}"
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("translation", text))
    }

    private fun exportRecords(records: List<TranslationRecord>) {
        if (records.isEmpty()) {
            Toast.makeText(this, getString(R.string.export_empty), Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val sb = StringBuilder("\uFEFF")
            for (r in records) {
                sb.append("=== ${dateFormat.format(Date(r.createTime))} ===\n")
                sb.append("[RU] ${r.ruText}\n")
                sb.append("[ZH] ${r.zhText}\n\n")
            }

            val fileName = "translation_${
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            }.txt"

            val success = withContext(Dispatchers.IO) {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                    ) ?: return@withContext false
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(sb.toString().toByteArray(Charsets.UTF_8))
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            }

            Toast.makeText(this@HistoryActivity,
                if (success) getString(R.string.export_success) else "导出失败",
                Toast.LENGTH_SHORT).show()
        }
    }
}
