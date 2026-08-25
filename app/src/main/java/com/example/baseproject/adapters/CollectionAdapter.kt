package com.example.baseproject.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.baseproject.data.AlbumCollection
import com.example.baseproject.databinding.ItemCollectionBinding

class CollectionAdapter(
    private val onClick: (AlbumCollection) -> Unit
) : ListAdapter<AlbumCollection, CollectionAdapter.ViewHolder>(DIFF_CALLBACK) {

    inner class ViewHolder(val binding: ItemCollectionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onClick(getItem(position))
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCollectionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val collection = getItem(position)
        // thumbnailUrl có thể là asset uri hoặc URL server — Glide xử lý được cả hai.
        Glide.with(holder.binding.ivThumbnail)
            .load(collection.thumbnailUrl)
            .into(holder.binding.ivThumbnail)
        holder.binding.tvNumberCount.text = collection.imageCount.toString()
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AlbumCollection>() {
            override fun areItemsTheSame(oldItem: AlbumCollection, newItem: AlbumCollection) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: AlbumCollection, newItem: AlbumCollection) =
                oldItem == newItem
        }
    }
}
