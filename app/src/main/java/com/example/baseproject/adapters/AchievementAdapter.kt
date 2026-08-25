package com.example.baseproject.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.baseproject.R
import com.example.baseproject.data.Achievement
import com.example.baseproject.databinding.ItemAchieveBinding
import com.example.baseproject.utils.runText

class AchievementAdapter(
    private val onClick: (Achievement) -> Unit
) : ListAdapter<Achievement, AchievementAdapter.ViewHolder>(DIFF_CALLBACK) {

    inner class ViewHolder(val binding: ItemAchieveBinding) :
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
        val binding = ItemAchieveBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val achievement = getItem(position)
        val context = holder.itemView.context

        holder.binding.tvAchiveName.text = context.getString(achievement.definition.titleRes)
        holder.binding.tvAchiveName.runText()

        val definition = achievement.definition
        val iconRes = if (achievement.isCompleted) {
            definition.iconCompletedRes ?: definition.iconRes
        } else {
            definition.iconRes
        }
        holder.binding.ivImageAchieve.setImageResource(iconRes ?: R.drawable.ic_mini_achieve)
        // Achievement chưa có art thì làm mờ ảnh tạm cho khỏi nhầm với huy hiệu thật.
        holder.binding.ivImageAchieve.alpha =
            if (iconRes != null || achievement.isCompleted) 1f else 0.4f

        // Đã đạt thì bỏ thanh tiến độ đi cho giống thiết kế tab Completed.
        if (achievement.isCompleted) {
            holder.binding.progressAchieve.visibility = View.GONE
        } else {
            holder.binding.progressAchieve.visibility = View.VISIBLE
            // Không animate lúc bind: ô được tái sử dụng khi cuộn, animate sẽ thành chạy lung tung.
            holder.binding.progressAchieve.setProgress(
                achievement.currentCount,
                achievement.targetCount,
                animate = false
            )
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Achievement>() {
            override fun areItemsTheSame(oldItem: Achievement, newItem: Achievement) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Achievement, newItem: Achievement) =
                oldItem == newItem
        }
    }
}
