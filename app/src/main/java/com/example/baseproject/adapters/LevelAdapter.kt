package com.example.baseproject.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.baseproject.R
import com.example.baseproject.data.LevelConfig
import com.example.baseproject.data.repository.PaintingProgressRepository
import com.example.baseproject.utils.AssetImageResolver
import java.io.File
import kotlin.math.roundToInt

class LevelAdapter(
    private val levels: List<LevelConfig>,
    private val paintingProgressRepository: PaintingProgressRepository,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val onClick: (LevelConfig) -> Unit
) : RecyclerView.Adapter<LevelAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
        val tvCurrentPercent: TextView = view.findViewById(R.id.tvCurrentPercent)

        init {
            view.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onClick(levels[position])
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_level, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val level = levels[position]
        val context = holder.itemView.context
        val completedMaskColors =
            paintingProgressRepository.loadProgress(level.category, level.id)
        val totalRegions = level.regions?.size ?: level.totalRegions ?: level.palette.size
        val progressFraction = if (totalRegions > 0) {
            completedMaskColors.size.toFloat() / totalRegions.toFloat()
        } else {
            0f
        }
        val progressPercent = (progressFraction * 100f).roundToInt()

        if (progressPercent in 1..99) {
            holder.tvCurrentPercent.visibility = View.VISIBLE
            holder.tvCurrentPercent.text = "$progressPercent%"
        } else {
            holder.tvCurrentPercent.visibility = View.GONE
        }
        
        // Kiểm tra xem đã có file Thumbnail (tiến trình đang tô dở) chưa
        val dir = File(context.filesDir, "thumbnails")
        val thumbFile = File(dir, "${level.category}_${level.id}.webp")
        
        if (thumbFile.exists()) {
            // Load file Thumbnail WEBP (bỏ qua Cache để luôn update ảnh mới nhất khi người dùng tô thêm)
            Glide.with(context)
                .load(thumbFile)
                .skipMemoryCache(true)
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                .into(holder.ivThumbnail)
        } else {
            // Chưa tô gì cả, load line.webp trước rồi fallback sang định dạng cũ
            val path = AssetImageResolver.toAndroidAssetUri(
                context.assets,
                "${level.category}/${level.id}/line"
            )
            Glide.with(context)
                .load(path)
                .into(holder.ivThumbnail)
        }
    }

    override fun getItemCount() = levels.size
}
