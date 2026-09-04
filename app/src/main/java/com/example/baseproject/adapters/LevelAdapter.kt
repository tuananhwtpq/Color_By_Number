package com.example.baseproject.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.baseproject.R
import com.example.baseproject.data.LevelConfig
import com.example.baseproject.data.progressFraction
import com.example.baseproject.data.repository.PaintingProgressRepository
import com.example.baseproject.data.repository.ThumbnailRepository
import com.example.baseproject.utils.AssetImageResolver
import kotlin.math.ceil

class LevelAdapter(
    private val paintingProgressRepository: PaintingProgressRepository,
    private val thumbnailRepository: ThumbnailRepository,
    private val onClick: (LevelConfig) -> Unit
) : ListAdapter<LevelConfig, LevelAdapter.ViewHolder>(DIFF_CALLBACK) {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
        val tvCurrentPercent: TextView = view.findViewById(R.id.tvCurrentPercent)

        init {
            view.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onClick(getItem(position))
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_level, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val level = getItem(position)
        val context = holder.itemView.context
        val completedMaskColors =
            paintingProgressRepository.loadProgress(level.category, level.id)
        val progress = level.progressFraction(completedMaskColors)
        val progressPercent = when {
            progress <= 0f -> 0
            progress >= 1f -> 100
            else -> ceil(progress * 100f).toInt().coerceIn(1, 99)
        }

        if (progressPercent in 1..99) {
            holder.tvCurrentPercent.visibility = View.VISIBLE
            holder.tvCurrentPercent.text = "$progressPercent%"
        } else {
            holder.tvCurrentPercent.visibility = View.GONE
        }
        
        // Kiểm tra xem đã có file Thumbnail (tiến trình đang tô dở) chưa
        val thumbFile = thumbnailRepository.getThumbnailFile(level.category, level.id)

        if (thumbFile.exists()) {
            // Load file Thumbnail WEBP (bỏ qua Cache để luôn update ảnh mới nhất khi người dùng tô thêm)
            Glide.with(context)
                .load(thumbFile)
                .skipMemoryCache(true)
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                .into(holder.ivThumbnail)
        } else {
            val remoteThumbnail = level.thumbnailUrl
                ?: level.assets?.preview?.takeIf(::isRemoteUrl)
                ?: level.assets?.sourceLine?.takeIf(::isRemoteUrl)
                ?: level.assets?.displayLine?.takeIf(::isRemoteUrl)
                ?: level.assets?.line?.takeIf(::isRemoteUrl)
            if (remoteThumbnail != null) {
                Glide.with(context)
                    .load(remoteThumbnail)
                    .into(holder.ivThumbnail)
                return
            }

            // Chưa tô gì cả, ưu tiên line gốc để thumbnail sắc nét hơn.
            val levelPath = "${level.category}/${level.id}"
            val configuredLine = level.assets?.sourceLine ?: level.assets?.debugSourceLine ?: level.assets?.line
            val path = if (!configuredLine.isNullOrBlank()) {
                "file:///android_asset/$levelPath/$configuredLine"
            } else {
                runCatching {
                    AssetImageResolver.toAndroidAssetUri(context.assets, "$levelPath/debug_source_line")
                }.getOrElse {
                    AssetImageResolver.toAndroidAssetUri(context.assets, "$levelPath/line")
                }
            }
            Glide.with(context)
                .load(path)
                .into(holder.ivThumbnail)
        }
    }

    private fun isRemoteUrl(value: String): Boolean =
        value.startsWith("http://") || value.startsWith("https://")

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<LevelConfig>() {
            override fun areItemsTheSame(oldItem: LevelConfig, newItem: LevelConfig): Boolean =
                oldItem.category == newItem.category && oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: LevelConfig, newItem: LevelConfig): Boolean =
                oldItem == newItem
        }
    }
}
