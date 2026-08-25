package com.example.baseproject.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.baseproject.R
import com.example.baseproject.data.Realm
import com.example.baseproject.databinding.ItemRealmBinding
import com.example.baseproject.utils.setOnUnDoubleClick

data class RealmRoadItem(
    val realm: Realm,
    val collectedPaintDrops: Int,
    val isUnlocked: Boolean,
    val isReadyToUnlock: Boolean,
    val isNextLockedRealm: Boolean,
    val isSelected: Boolean,
    val showDownArrow: Boolean,
) {
    val remainingPaintDrops: Int
        get() = (realm.unlockCost - collectedPaintDrops).coerceAtLeast(0)
}

class RealmRoadAdapter(
    private val onRealmClick: (Realm) -> Unit,
    private val onRealmViewClick: (Realm) -> Unit,
    private val onUnlockClick: (Realm) -> Unit,
    private val onNeedMorePaintClick: (Realm) -> Unit,
    private val onLockedClick: (Realm) -> Unit,
) : ListAdapter<RealmRoadItem, RealmRoadAdapter.ViewHolder>(DIFF_CALLBACK) {

    inner class ViewHolder(
        private val binding: ItemRealmBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.tvViewUnlock.setOnUnDoubleClick {
                handleUnlockedViewClick()
            }
            binding.tvViewLock.setOnUnDoubleClick {
                handlePrimaryClick()
            }
            binding.cardRealm.setOnUnDoubleClick {
                handlePrimaryClick()
            }
        }

        fun bind(item: RealmRoadItem) = with(binding) {
            tvRealmName.text = item.realm.name
            ivRealmThumbnail.setImageResource(item.realm.thumbnailRes)

            val isLocked = !item.isUnlocked
            val lockedVisibility = if (isLocked) View.VISIBLE else View.GONE
            lockedScrim.visibility = lockedVisibility
            ivLockBadge.visibility = lockedVisibility
            ivSelector.visibility = if (item.isUnlocked) View.VISIBLE else View.GONE
            ivSelector.isSelected = item.isSelected
            lockProgressContainer.visibility =
                if (isLocked && !item.isReadyToUnlock) View.VISIBLE else View.GONE
            tvViewUnlock.visibility =
                if (item.isUnlocked) View.VISIBLE else View.GONE
            tvViewLock.visibility =
                if (item.isReadyToUnlock) View.VISIBLE else View.GONE
            ivDownArrow.visibility = if (item.showDownArrow) View.VISIBLE else View.INVISIBLE

            tvCollectedDrops.text = root.context.getString(
                R.string.realm_paint_drops_collected_format,
                item.collectedPaintDrops.coerceAtMost(item.realm.unlockCost),
                item.realm.unlockCost,
            )
            tvMoreToUnlock.text = root.context.getString(
                R.string.realm_more_to_unlock_format,
                item.remainingPaintDrops,
            )
        }

        private fun handlePrimaryClick() {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return

            val item = getItem(position)
            when {
                item.isUnlocked -> onRealmClick(item.realm)
                item.isReadyToUnlock -> onUnlockClick(item.realm)
                item.isNextLockedRealm -> onNeedMorePaintClick(item.realm)
                else -> onLockedClick(item.realm)
            }
        }

        private fun handleUnlockedViewClick() {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return

            val item = getItem(position)
            if (item.isUnlocked) {
                onRealmViewClick(item.realm)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRealmBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<RealmRoadItem>() {
            override fun areItemsTheSame(oldItem: RealmRoadItem, newItem: RealmRoadItem): Boolean =
                oldItem.realm.id == newItem.realm.id

            override fun areContentsTheSame(oldItem: RealmRoadItem, newItem: RealmRoadItem): Boolean =
                oldItem == newItem
        }
    }
}
