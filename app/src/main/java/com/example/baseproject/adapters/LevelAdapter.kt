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
import java.io.File

class LevelAdapter(
    private val levels: List<LevelConfig>,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val onClick: (LevelConfig) -> Unit
) : RecyclerView.Adapter<LevelAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
        val tvLevelName: TextView = view.findViewById(R.id.tvLevelName)

        init {
            view.setOnClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onClick(levels[adapterPosition])
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
        holder.tvLevelName.text = level.name
        
        val context = holder.itemView.context
        
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
            // Chưa tô gì cả, Load file line.png mặc định từ Assets
            val path = "file:///android_asset/${level.category}/${level.id}/line.png"
            Glide.with(context)
                .load(path)
                .into(holder.ivThumbnail)
        }
    }

    override fun getItemCount() = levels.size
}
