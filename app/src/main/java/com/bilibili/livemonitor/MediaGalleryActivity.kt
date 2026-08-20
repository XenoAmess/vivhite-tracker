package com.bilibili.livemonitor

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bilibili.livemonitor.db.AppDatabase
import com.bilibili.livemonitor.util.ShareImageFactory
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A local, deduplicated gallery of captured anchor avatars and live-room covers. */
class MediaGalleryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var stateView: TextView
    private lateinit var selectionBar: View
    private lateinit var selectionCount: TextView
    private lateinit var adapter: MediaAdapter
    private var allItems: List<GalleryMedia> = emptyList()
    private var activeKind: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_gallery)

        val root = findViewById<View>(R.id.galleryRoot)
        val initialPadding = intArrayOf(
            root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                initialPadding[0] + bars.left,
                initialPadding[1] + bars.top,
                initialPadding[2] + bars.right,
                initialPadding[3] + bars.bottom
            )
            insets
        }

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.galleryToolbar)
            .setNavigationOnClickListener { finish() }

        recyclerView = findViewById(R.id.rvMediaGallery)
        stateView = findViewById(R.id.tvGalleryState)
        selectionBar = findViewById(R.id.gallerySelectionBar)
        selectionCount = findViewById(R.id.tvGallerySelectionCount)
        adapter = MediaAdapter(
            scopeOwner = this,
            onClick = ::onMediaClick,
            onLongClick = ::onMediaLongClick
        )
        recyclerView.layoutManager = GridLayoutManager(this, gallerySpanCount())
        recyclerView.adapter = adapter

        findViewById<ChipGroup>(R.id.galleryFilters).setOnCheckedStateChangeListener { _, checked ->
            activeKind = when (checked.firstOrNull()) {
                R.id.chipGalleryAvatar -> KIND_AVATAR
                R.id.chipGalleryCover -> KIND_COVER
                else -> null
            }
            leaveSelectionMode()
            showFilteredItems()
        }
        findViewById<View>(R.id.btnGalleryCancelSelection).setOnClickListener {
            leaveSelectionMode()
        }
        findViewById<View>(R.id.btnGalleryShareSelected).setOnClickListener {
            shareMedia(adapter.selectedItems())
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (adapter.selectionMode) leaveSelectionMode() else finish()
            }
        })

        lifecycleScope.launch { loadGallery() }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        bitmapCache.evictAll()
    }

    private suspend fun loadGallery() {
        stateView.visibility = View.VISIBLE
        stateView.text = "正在整理影集…"
        val result = runCatching {
            withContext(Dispatchers.IO) {
                MediaGalleryRepository(this@MediaGalleryActivity, AppDatabase.get(this@MediaGalleryActivity))
                    .load()
            }
        }
        result.onSuccess {
            allItems = it
            showFilteredItems()
        }.onFailure {
            stateView.visibility = View.VISIBLE
            stateView.text = "影集读取失败，请稍后重试"
        }
    }

    private fun showFilteredItems() {
        val visible = activeKind?.let { kind -> allItems.filter { it.kind == kind } } ?: allItems
        adapter.submit(visible)
        stateView.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        if (visible.isEmpty()) {
            stateView.text = when (activeKind) {
                KIND_AVATAR -> "还没有收录头像"
                KIND_COVER -> "还没有收录直播封面"
                else -> "还没有收录图片"
            }
        }
    }

    private fun onMediaClick(item: GalleryMedia) {
        if (adapter.selectionMode) {
            adapter.toggleSelection(item)
            updateSelectionBar()
        } else {
            showPreview(item)
        }
    }

    private fun onMediaLongClick(item: GalleryMedia): Boolean {
        adapter.toggleSelection(item)
        updateSelectionBar()
        return true
    }

    private fun updateSelectionBar() {
        val count = adapter.selectedItems().size
        selectionBar.visibility = if (adapter.selectionMode) View.VISIBLE else View.GONE
        selectionCount.text = "已选 $count 张"
        findViewById<View>(R.id.btnGalleryShareSelected).isEnabled = count > 0
    }

    private fun leaveSelectionMode() {
        if (::adapter.isInitialized) adapter.clearSelection()
        if (::selectionBar.isInitialized) selectionBar.visibility = View.GONE
    }

    private fun showPreview(item: GalleryMedia) {
        val content = layoutInflater.inflate(R.layout.dialog_media_preview, null)
        val image = content.findViewById<ImageView>(R.id.ivGalleryPreview)
        content.findViewById<TextView>(R.id.tvGalleryPreviewDetails).text = item.fullDetails()
        val dialog = AlertDialog.Builder(this)
            .setTitle(item.kindLabel)
            .setView(content)
            .create()
        content.findViewById<View>(R.id.btnGalleryPreviewClose).setOnClickListener { dialog.dismiss() }
        content.findViewById<View>(R.id.btnGalleryPreviewShare).setOnClickListener {
            shareMedia(listOf(item))
        }
        val decodeJob = lifecycleScope.launch {
            val display = resources.displayMetrics
            val target = maxOf(display.widthPixels, display.heightPixels).coerceAtMost(2048)
            val bitmap = decodeCached(item.file, target)
            if (bitmap != null) image.setImageBitmap(bitmap)
        }
        dialog.setOnDismissListener { decodeJob.cancel() }
        dialog.show()
    }

    private fun shareMedia(items: List<GalleryMedia>) {
        val files = items.map { it.file }.filter(::isShareableMediaFile).distinctBy { it.absolutePath }
        if (files.isEmpty()) {
            Toast.makeText(this, "没有可分享的原图", Toast.LENGTH_SHORT).show()
            return
        }
        if (files.size > MAX_BATCH_SHARE) {
            Toast.makeText(this, "一次最多分享 $MAX_BATCH_SHARE 张，请减少选择", Toast.LENGTH_LONG).show()
            return
        }
        val uris = runCatching {
            files.map {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", it)
            }
        }.getOrElse {
            Toast.makeText(this, "原图授权失败", Toast.LENGTH_SHORT).show()
            return
        }
        val share = if (uris.size == 1) {
            ShareImageFactory.buildImageShareIntent(
                uri = uris.single(),
                contentResolver = contentResolver,
                clipLabel = "绮迹影集原图"
            )
        } else {
            ShareImageFactory.buildMultipleImageShareIntent(
                uris = uris,
                contentResolver = contentResolver,
                clipLabel = "绮迹影集原图"
            )
        }
        runCatching {
            startActivity(Intent.createChooser(share, "分享绮迹影集"))
        }.onFailure {
            Toast.makeText(this, "分享应用无法接收这些图片", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isShareableMediaFile(file: File): Boolean {
        if (!file.isFile) return false
        val parent = runCatching { file.canonicalFile.parentFile }.getOrNull() ?: return false
        return mediaRoots(this).any { root -> runCatching { root.canonicalFile == parent }.getOrDefault(false) }
    }

    private fun gallerySpanCount(): Int {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return (widthDp / 180f).toInt().coerceIn(2, 5)
    }

    private class MediaAdapter(
        private val scopeOwner: MediaGalleryActivity,
        private val onClick: (GalleryMedia) -> Unit,
        private val onLongClick: (GalleryMedia) -> Boolean
    ) : RecyclerView.Adapter<MediaAdapter.Holder>() {

        private var items: List<GalleryMedia> = emptyList()
        private val selectedIds = linkedSetOf<String>()
        val selectionMode: Boolean get() = selectedIds.isNotEmpty()

        fun submit(newItems: List<GalleryMedia>) {
            val oldItems = items
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldItems.size
                override fun getNewListSize(): Int = newItems.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    oldItems[oldItemPosition].stableId == newItems[newItemPosition].stableId

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    oldItems[oldItemPosition] == newItems[newItemPosition]
            })
            items = newItems
            selectedIds.retainAll(newItems.mapTo(hashSetOf()) { it.stableId })
            diff.dispatchUpdatesTo(this)
        }

        fun toggleSelection(item: GalleryMedia) {
            val wasSelectionMode = selectionMode
            if (!selectedIds.add(item.stableId)) selectedIds.remove(item.stableId)
            if (wasSelectionMode != selectionMode) {
                notifyItemRangeChanged(0, itemCount)
            } else {
                val position = items.indexOfFirst { it.stableId == item.stableId }
                if (position >= 0) notifyItemChanged(position)
            }
        }

        fun clearSelection() {
            if (selectedIds.isEmpty()) return
            selectedIds.clear()
            notifyItemRangeChanged(0, itemCount)
        }

        fun selectedItems(): List<GalleryMedia> = items.filter { it.stableId in selectedIds }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_media_gallery, parent, false)
        )

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.bind(item, item.stableId in selectedIds)
            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener { onLongClick(item) }
        }

        override fun onViewRecycled(holder: Holder) {
            holder.decodeJob?.cancel()
            holder.decodeJob = null
            super.onViewRecycled(holder)
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val image = view.findViewById<ImageView>(R.id.ivGalleryImage)
            private val kind = view.findViewById<TextView>(R.id.tvGalleryKind)
            private val date = view.findViewById<TextView>(R.id.tvGalleryDate)
            private val usage = view.findViewById<TextView>(R.id.tvGalleryUsage)
            private val selected = view.findViewById<TextView>(R.id.tvGallerySelected)
            private val card = view.findViewById<MaterialCardView>(R.id.galleryCard)
            var decodeJob: Job? = null

            fun bind(item: GalleryMedia, isSelected: Boolean) {
                decodeJob?.cancel()
                image.setImageDrawable(null)
                kind.text = item.kindLabel
                date.text = item.dateSummary
                usage.text = item.usageSummary
                selected.visibility = if (isSelected) View.VISIBLE else View.GONE
                card.strokeWidth = if (isSelected) dp(3) else dp(1)
                card.alpha = if (selectionMode && !isSelected) 0.72f else 1f
                itemView.contentDescription = "${item.kindLabel}，${item.dateSummary}，${item.usageSummary}"
                decodeJob = scopeOwner.lifecycleScope.launch {
                    decodeCached(item.file, 512)?.let { bitmap ->
                        if (bindingAdapterPosition != RecyclerView.NO_POSITION &&
                            items[bindingAdapterPosition].stableId == item.stableId
                        ) {
                            image.setImageBitmap(bitmap)
                        }
                    }
                }
            }

            private fun dp(value: Int): Int =
                (value * itemView.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        }
    }

    companion object {
        internal const val KIND_AVATAR = "avatar"
        internal const val KIND_COVER = "room_cover"
        private const val MAX_BATCH_SHARE = 50
        private val decodeDispatcher = Dispatchers.IO.limitedParallelism(2)

        private val bitmapCache = object : LruCache<String, Bitmap>(bitmapCacheSizeKb()) {
            override fun sizeOf(key: String, value: Bitmap): Int =
                (value.allocationByteCount / 1024).coerceAtLeast(1)
        }

        private fun bitmapCacheSizeKb(): Int =
            (Runtime.getRuntime().maxMemory() / 1024L / 16L).toInt().coerceIn(4 * 1024, 24 * 1024)

        private suspend fun decodeCached(file: File, targetSize: Int): Bitmap? {
            val key = "${file.absolutePath}:${file.lastModified()}:${file.length()}:$targetSize"
            bitmapCache.get(key)?.let { return it }
            return withContext(decodeDispatcher) {
                bitmapCache.get(key) ?: decodeSampled(file, targetSize)?.also { bitmapCache.put(key, it) }
            }
        }

        internal fun decodeSampled(file: File, targetSize: Int): Bitmap? {
            if (!file.isFile || file.length() <= 0L) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > targetSize * 5 / 4) {
                sample *= 2
            }
            return runCatching {
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }.getOrNull()
        }
    }
}

internal data class GalleryObservation(
    val observedAt: Long,
    val sessionStartTs: Long?,
    val title: String?,
    val sourceUrl: String?
)

internal data class GalleryMedia(
    val kind: String,
    val contentKey: String,
    val file: File,
    val observations: List<GalleryObservation>,
    val legacyOrphan: Boolean
) {
    val stableId: String get() = "$kind\u0000$contentKey"
    val kindLabel: String get() = if (kind == MediaGalleryActivity.KIND_AVATAR) "头像" else "直播封面"
    val firstObserved: Long get() = observations.minOfOrNull { it.observedAt } ?: file.lastModified()
    val lastObserved: Long get() = observations.maxOfOrNull { it.observedAt } ?: file.lastModified()
    private val sessions: List<GalleryObservation>
        get() = observations.filter { it.sessionStartTs != null }
            .distinctBy { it.sessionStartTs }
            .sortedByDescending { it.sessionStartTs }

    val dateSummary: String
        get() = if (firstObserved == lastObserved) day(firstObserved) else "${day(firstObserved)} - ${day(lastObserved)}"

    val usageSummary: String
        get() = when {
            legacyOrphan -> "旧文件 · 尚无索引记录"
            sessions.isNotEmpty() -> "关联 ${sessions.size} 场 · 收录 ${observations.size} 次"
            else -> "收录 ${observations.size} 次"
        }

    fun fullDetails(): String = buildString {
        append("收录日期：").append(dateSummary).append('\n')
        append("使用情况：").append(usageSummary)
        if (sessions.isNotEmpty()) {
            append("\n\n关联场次")
            sessions.take(20).forEach { observation ->
                append("\n").append(dateTime(observation.sessionStartTs!!))
                observation.title?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            }
            if (sessions.size > 20) append("\n另有 ${sessions.size - 20} 场")
        }
        if (legacyOrphan) append("\n\n此图片来自旧版物理文件，暂无场次元数据。")
    }

    companion object {
        private fun day(timestamp: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))

        private fun dateTime(timestamp: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}

/** Combines the indexed snapshot history with valid legacy files that predate the index. */
internal class MediaGalleryRepository(
    private val context: android.content.Context,
    private val database: AppDatabase
) {
    suspend fun load(): List<GalleryMedia> = withContext(Dispatchers.IO) {
        com.bilibili.livemonitor.util.MediaHistoryImporter.ensureImported(context)
        val rows = database.mediaSnapshotDao().allSnapshots()
        val indexedPaths = hashSetOf<String>()
        val indexed = rows
            .filter { it.kind == MediaGalleryActivity.KIND_AVATAR || it.kind == MediaGalleryActivity.KIND_COVER }
            .groupBy { it.kind to it.contentKey }
            .mapNotNull { (identity, grouped) ->
                val candidates = grouped.sortedByDescending { it.observedAt }
                candidates.forEach { row -> validFile(row.kind, row.fileName)?.let { indexedPaths += it.canonicalPath } }
                val file = candidates.firstNotNullOfOrNull { validFile(it.kind, it.fileName) }
                    ?: return@mapNotNull null
                GalleryMedia(
                    kind = identity.first,
                    contentKey = identity.second,
                    file = file,
                    observations = grouped.map {
                        GalleryObservation(it.observedAt, it.sessionStartTs, it.title, it.sourceUrl)
                    },
                    legacyOrphan = false
                )
            }

        val orphans = mediaRoots(context).flatMap { root ->
            val kind = if (root.name == "avatars") {
                MediaGalleryActivity.KIND_AVATAR
            } else {
                MediaGalleryActivity.KIND_COVER
            }
            root.listFiles().orEmpty()
                .filter { it.isFile && !it.name.startsWith('.') && !it.name.endsWith(".part") }
                .mapNotNull { file ->
                    val valid = validPhysicalFile(root, file) ?: return@mapNotNull null
                    if (valid.canonicalPath in indexedPaths) return@mapNotNull null
                    GalleryMedia(
                        kind = kind,
                        contentKey = "legacy:${valid.name}",
                        file = valid,
                        observations = listOf(
                            GalleryObservation(valid.lastModified().coerceAtLeast(0L), null, null, null)
                        ),
                        legacyOrphan = true
                    )
                }
        }
        (indexed + orphans).sortedByDescending { it.lastObserved }
    }

    private fun validFile(kind: String, fileName: String): File? {
        if (fileName.isBlank() || File(fileName).name != fileName) return null
        val rootName = if (kind == MediaGalleryActivity.KIND_AVATAR) "avatars" else "covers"
        val root = File(context.filesDir, rootName)
        return validPhysicalFile(root, File(root, fileName))
    }

    private fun validPhysicalFile(root: File, file: File): File? {
        if (!file.isFile || file.length() <= 0L) return null
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (canonical.parentFile != runCatching { root.canonicalFile }.getOrNull()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(canonical.absolutePath, bounds)
        return canonical.takeIf { bounds.outWidth > 0 && bounds.outHeight > 0 }
    }

}

private fun mediaRoots(context: android.content.Context): List<File> = listOf(
    File(context.filesDir, "covers"),
    File(context.filesDir, "avatars")
)
