package com.example.baseproject.activities

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.baseproject.R
import com.example.baseproject.adapters.PaletteAdapter
import com.example.baseproject.data.LevelConfig
import com.example.baseproject.data.PaletteItem
import com.example.baseproject.data.RegionData
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

class PaintActivity : AppCompatActivity() {

    private lateinit var paintCanvas: PaintCanvasView
    private lateinit var rvPalette: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvTitle: TextView
    private lateinit var btnBack: ImageButton

    private lateinit var levelConfig: LevelConfig
    private lateinit var allRegions: List<PaletteItem>
    private lateinit var uniqueColors: List<PaletteItem>
    private var regionMetadata: List<RegionData> = emptyList()
    private lateinit var adapter: PaletteAdapter

    private val completedMaskColors = mutableSetOf<Int>()
    private val completedMaskColorsStr = mutableSetOf<String>()
    private lateinit var prefs: SharedPreferences
    private lateinit var prefKey: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_paint)

        val category = intent.getStringExtra("CATEGORY") ?: return finish()
        val levelId = intent.getStringExtra("LEVEL_ID") ?: return finish()

        prefKey = "PROGRESS_${category}_${levelId}"
        prefs = getSharedPreferences("PaintingProgress", Context.MODE_PRIVATE)

        initViews()

        btnBack.setOnClickListener { finish() }

        loadLevelData(category, levelId)
    }

    private fun initViews() {
        paintCanvas = findViewById(R.id.paintCanvas)
        rvPalette = findViewById(R.id.rvPalette)
        progressBar = findViewById(R.id.progressBar)
        tvTitle = findViewById(R.id.tvTitle)
        btnBack = findViewById(R.id.btnBack)

        rvPalette.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        // Lắng nghe sự kiện đổ màu hoàn thành thay vì click
        setupRegionFilledListener()

        // Cài đặt nút Hint
        findViewById<View>(R.id.btnHint).setOnClickListener {
            handleHintClick()
        }

        // Cài đặt nút Reset
        findViewById<View>(R.id.btnReset).setOnClickListener {
            resetProgress()
        }
    }

    private fun resetProgress() {
        val category = intent.getStringExtra("CATEGORY") ?: return
        val levelId = intent.getStringExtra("LEVEL_ID") ?: return

        AlertDialog.Builder(this)
            .setTitle("Reset")
            .setMessage("Bạn có chắc chắn muốn xóa toàn bộ tiến trình của bức tranh này và tô lại từ đầu?")
            .setPositiveButton("Có") { _, _ ->
                prefs.edit().remove(prefKey).apply()
                completedMaskColors.clear()
                completedMaskColorsStr.clear()

                // Xóa file Thumbnail
                val dir = java.io.File(filesDir, "thumbnails")
                val thumbFile = java.io.File(dir, "${category}_${levelId}.webp")
                if (thumbFile.exists()) {
                    thumbFile.delete()
                }

                paintCanvas.setCompletedRegions(completedMaskColors)
                paintCanvas.resetProgress()

                // Reset UI Palette
                if (this::adapter.isInitialized) {
                    adapter.completedIndexes.clear()
                    adapter.notifyDataSetChanged()

                    // Select màu đầu tiên
                    if (uniqueColors.isNotEmpty()) {
                        adapter.setSelection(0)
                        rvPalette.scrollToPosition(0)
                        updateHighlight(uniqueColors[0])
                    }
                }
            }
            .setNegativeButton("Không", null)
            .show()
    }

    private fun handleHintClick() {
        if (!this::adapter.isInitialized) return
        if (uniqueColors.isEmpty()) return

        val selectedIndex = adapter.selectedIndex
        val selectedUniqueColor = uniqueColors[selectedIndex]
        val validRegions = allRegions.filter { it.number == selectedUniqueColor.number }

        val preferredMaskColor = regionMetadata
            .filter {
                it.number == selectedUniqueColor.number &&
                        !completedMaskColors.contains(it.maskColorInt)
            }
            .sortedWith(
                compareByDescending<RegionData> { !it.hideNumber }
                    .thenByDescending { it.area }
            )
            .firstOrNull()
            ?.maskColorInt
            ?: validRegions.find { !completedMaskColors.contains(it.getMaskColorInt()) }
                ?.getMaskColorInt()

        if (preferredMaskColor != null) {
            paintCanvas.focusOnRegionByMaskColor(preferredMaskColor)
        } else {
            Toast.makeText(this, "Bạn đã tô xong màu này rồi!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadLevelData(category: String, levelId: String) {
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                // Read config.json
                val configStr = withContext(Dispatchers.IO) {
                    val stream = assets.open("$category/$levelId/config.json")
                    val reader = InputStreamReader(stream)
                    val json = reader.readText()
                    reader.close()
                    json
                }
                levelConfig = Gson().fromJson(configStr, LevelConfig::class.java)
                allRegions = levelConfig.palette
                regionMetadata = levelConfig.toRegionDataList()

                // Group to unique colors for palette UI
                uniqueColors = allRegions.groupBy { it.number }
                    .map { it.value.first() }
                    .sortedBy { it.number }

                tvTitle.text = levelConfig.name

                // Load Bitmaps
                val lineBitmap = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeStream(assets.open("$category/$levelId/line.png"))
                }
                val maskBitmap = withContext(Dispatchers.IO) {
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeStream(
                        assets.open("$category/$levelId/mask.png"),
                        null,
                        opts
                    )
                }

                // Ưu tiên metadata vùng đã sinh sẵn từ config.json, fallback về level cũ.
                val regionsData = if (regionMetadata.isNotEmpty()) {
                    regionMetadata
                } else {
                    withContext(Dispatchers.Default) {
                        com.example.baseproject.data.CentroidCalculator.calculateCentroids(
                            maskBitmap!!,
                            allRegions
                        )
                    }
                }
                regionMetadata = regionsData

                // Khởi tạo adapter
                adapter = PaletteAdapter(uniqueColors) { selectedColor ->
                    updateHighlight(selectedColor)
                }
                rvPalette.adapter = adapter

                paintCanvas.setBitmapsSuspend(lineBitmap!!, maskBitmap!!, regionsData)
                loadProgressSuspend()

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@PaintActivity, "Failed to load level", Toast.LENGTH_SHORT)
                    .show()
                finish()
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    private suspend fun loadProgressSuspend() {
        val savedStrings = prefs.getStringSet(prefKey, emptySet()) ?: emptySet()
        if (savedStrings.isNotEmpty()) {
            val savedColors = savedStrings.mapNotNull { it.toIntOrNull() }.toSet()
            completedMaskColors.addAll(savedColors)
            completedMaskColorsStr.addAll(savedStrings)
            paintCanvas.setCompletedRegions(completedMaskColors)

            // Xây dựng map phục hồi cho Canvas
            val completedMap = mutableMapOf<Int, Int>()
            for (color in savedColors) {
                val item = allRegions.find { it.getMaskColorInt() == color }
                if (item != null) {
                    completedMap[color] = item.getTargetColorInt()
                }
            }
            paintCanvas.restoreProgressSuspend(completedMap)

            // Cập nhật Palette UI
            for ((index, uniqueColor) in uniqueColors.withIndex()) {
                val validRegions = allRegions.filter { it.number == uniqueColor.number }
                checkColorCompletion(uniqueColor, index, validRegions)
            }
        }

        // Highlight màu đầu tiên chưa hoàn thành (hoặc màu 0)
        if (uniqueColors.isNotEmpty()) {
            val firstIncompleteIndex =
                uniqueColors.indices.firstOrNull { !adapter.completedIndexes.contains(it) } ?: 0
            adapter.setSelection(firstIncompleteIndex)
            rvPalette.scrollToPosition(firstIncompleteIndex)
            updateHighlight(uniqueColors[firstIncompleteIndex])
        }
    }

    private fun updateHighlight(selectedColor: PaletteItem) {
        val validRegions = allRegions.filter { it.number == selectedColor.number }
        val targetMaskColors = validRegions.map { it.getMaskColorInt() }

        paintCanvas.highlightNumber(targetMaskColors)

        // Cập nhật Active Color Map cho PaintCanvasView
        if (validRegions.isNotEmpty()) {
            val colorMap = validRegions.associate { it.getMaskColorInt() to it.getTargetColorInt() }
            paintCanvas.setActiveColors(colorMap)
        }
    }

    private fun setupRegionFilledListener() {
        paintCanvas.onRegionFilledListener = { maskInt ->
            completedMaskColors.add(maskInt)
            completedMaskColorsStr.add(maskInt.toString())
            paintCanvas.setCompletedRegions(completedMaskColors)

            // Lưu tiến trình (Sử dụng Set trực tiếp để tối ưu Memory Allocations)
            prefs.edit().putStringSet(prefKey, completedMaskColorsStr).apply()

            if (this::adapter.isInitialized) {
                val selectedIndex = adapter.selectedIndex
                val selectedUniqueColor = uniqueColors[selectedIndex]
                val validRegions = allRegions.filter { it.number == selectedUniqueColor.number }

                // Cập nhật lại lớp highlight để xóa vùng vừa tô khỏi highlight
                updateHighlight(selectedUniqueColor)

                checkColorCompletion(selectedUniqueColor, selectedIndex, validRegions)
            }
        }
    }

    private fun checkColorCompletion(
        color: PaletteItem,
        index: Int,
        validRegions: List<PaletteItem>
    ) {
        val allCompleted = validRegions.all { completedMaskColors.contains(it.getMaskColorInt()) }
        if (allCompleted) {
            adapter.markCompleted(index)

            // Auto scroll tới màu chưa hoàn thành tiếp theo
            val nextIndex =
                uniqueColors.indices.firstOrNull { !adapter.completedIndexes.contains(it) }
            if (nextIndex != null) {
                adapter.setSelection(nextIndex)
                rvPalette.smoothScrollToPosition(nextIndex)
                updateHighlight(uniqueColors[nextIndex])
            } else {
                // Hoàn thành tất cả!
                Toast.makeText(this, "Level Completed! 🎉", Toast.LENGTH_LONG).show()
                paintCanvas.highlightNumber(emptyList()) // Xóa highlight
            }
        }
    }

    override fun onPause() {
        super.onPause()
        saveThumbnail()
    }

    private fun saveThumbnail() {
        // Nếu chưa tô gì thì không cần lưu thumbnail (sẽ load mặc định line.png)
        if (completedMaskColors.isEmpty()) return

        val bitmap = paintCanvas.generateThumbnail(400) ?: return
        val category = intent.getStringExtra("CATEGORY") ?: return
        val levelId = intent.getStringExtra("LEVEL_ID") ?: return

        // Lưu ĐỒNG BỘ (Synchronous) để đảm bảo file được ghi xong 100% 
        // TRƯỚC KHI MainActivity gọi onResume() và dùng Glide đọc file này lên.
        // Quá trình này chỉ tốn ~10-15ms, rất an toàn để chạy trên Main Thread khi chuyển màn hình.
        try {
            val dir = java.io.File(filesDir, "thumbnails")
            if (!dir.exists()) dir.mkdirs()

            val file = java.io.File(dir, "${category}_${levelId}.webp")
            java.io.FileOutputStream(file).use { out ->
                // Nén ảnh dưới định dạng WEBP chất lượng 80 (cực nhẹ và nhanh)
                bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP, 80, out)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            bitmap.recycle()
        }
    }
}
