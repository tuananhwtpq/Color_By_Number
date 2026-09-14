package com.pixlory.color.by.number.adapters

import android.graphics.Color
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.pixlory.color.by.number.R
import com.pixlory.color.by.number.data.PaletteItem
import com.pixlory.color.by.number.utils.Constants
import com.pixlory.color.by.number.utils.SoundEffect
import com.pixlory.color.by.number.utils.soundManagerOrNull
import com.pixlory.color.by.number.views.PaletteRingView

class PaletteAdapter(
    private val items: List<PaletteItem>,
    private val removeCompletedColors: Boolean = Constants.REMOVE_COMPLETED_COLORS_FROM_PALETTE,
    private val onColorSelected: (originalIndex: Int, item: PaletteItem) -> Unit
) : RecyclerView.Adapter<PaletteAdapter.ViewHolder>() {

    private companion object {
        const val COMPLETION_ANIMATION_DURATION_MS = 1_000L
    }

    var selectedIndex = -1
        private set

    val completedIndexes = mutableSetOf<Int>()
    /** Completed colours that stay visible until their completion animation has finished. */
    private val pendingCompletionAnimations = mutableSetOf<Int>()
    private val hiddenCompletedIndexes = mutableSetOf<Int>()
    private val runningCompletionAnimations = mutableSetOf<Int>()
    private var hasReceivedPaletteState = false
    private var paletteProgress: List<Float> = List(items.size) { 0f }
    private var displayItems: List<DisplayPaletteItem> = buildDisplayItems()

    val sourceItemCount: Int
        get() = items.size

    private data class DisplayPaletteItem(
        val originalIndex: Int,
        val item: PaletteItem
    )

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val colorCircle: CardView = view.findViewById(R.id.colorCircle)
        val tvNumber: TextView = view.findViewById(R.id.tvNumber)
        val ivCheck: LottieAnimationView = view.findViewById(R.id.ivCheck)
        val ringView: PaletteRingView = view.findViewById(R.id.ringView)

        init {
            view.setOnClickListener {
                val position = bindingAdapterPosition
                val displayItem = displayItems.getOrNull(position)
                if (displayItem != null && !completedIndexes.contains(displayItem.originalIndex)) {
                    itemView.context.soundManagerOrNull()?.play(SoundEffect.PALETTE)
                    setSelection(displayItem.originalIndex)
                    onColorSelected(displayItem.originalIndex, displayItem.item)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_palette, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val displayItem = displayItems[position]
        val item = displayItem.item
        val originalIndex = displayItem.originalIndex
        val colorInt = item.getTargetColorInt()
        holder.colorCircle.setCardBackgroundColor(colorInt)
        holder.tvNumber.text = item.number.toString()

        val r = Color.red(colorInt)
        val g = Color.green(colorInt)
        val b = Color.blue(colorInt)
        val brightness = 0.299 * r + 0.587 * g + 0.114 * b
        holder.tvNumber.setTextColor(if (brightness > 186) Color.BLACK else Color.WHITE)

        val isCompleted = completedIndexes.contains(originalIndex)
        val isSelected = originalIndex == selectedIndex
        val progressFraction = paletteProgress.getOrElse(originalIndex) { 0f }
        val isSelectedInProgress = isSelected && progressFraction > 0f && progressFraction < 1f
        val hasOuterRing = !isCompleted && isSelected
        holder.setColorCircleDiameter(if (hasOuterRing) 38 else 50)

        if (!isCompleted) holder.ivCheck.cancelAnimation()

        when {
            isCompleted -> {
                holder.ringView.setRingState(PaletteRingView.MODE_NONE)
                holder.tvNumber.visibility = View.GONE
                holder.ivCheck.visibility = View.VISIBLE
                if (pendingCompletionAnimations.contains(originalIndex) &&
                    runningCompletionAnimations.add(originalIndex)
                ) {
                    playCompletionAnimation(holder.ivCheck, originalIndex)
                }
            }

            isSelectedInProgress -> {
                holder.ringView.setRingState(PaletteRingView.MODE_PROGRESS, progressFraction)
                holder.tvNumber.visibility = View.VISIBLE
                holder.ivCheck.visibility = View.GONE
            }

            isSelected -> {
                holder.ringView.setRingState(PaletteRingView.MODE_SELECTED)
                holder.tvNumber.visibility = View.VISIBLE
                holder.ivCheck.visibility = View.GONE
            }

            else -> {
                holder.ringView.setRingState(PaletteRingView.MODE_NONE)
                holder.tvNumber.visibility = View.VISIBLE
                holder.ivCheck.visibility = View.GONE
            }
        }
    }

    private fun ViewHolder.setColorCircleDiameter(sizeDp: Int) {
        val sizePx = (sizeDp * itemView.resources.displayMetrics.density).toInt()
        val params = colorCircle.layoutParams
        if (params.width == sizePx && params.height == sizePx) return

        params.width = sizePx
        params.height = sizePx
        colorCircle.layoutParams = params
        colorCircle.radius = sizePx / 2f
    }

    override fun getItemCount() = displayItems.size

    fun setSelection(originalIndex: Int) {
        val prev = selectedIndex
        selectedIndex = originalIndex
        notifyOriginalIndexChanged(prev)
        notifyOriginalIndexChanged(selectedIndex)
    }

    fun markCompleted(originalIndex: Int) {
        if (!completedIndexes.contains(originalIndex)) {
            pendingCompletionAnimations.add(originalIndex)
        }
        completedIndexes.add(originalIndex)
        refreshDisplayItems()
    }

    fun setCompletedIndexes(indexes: Set<Int>) {
        val newlyCompleted = indexes - completedIndexes
        completedIndexes.clear()
        completedIndexes.addAll(indexes)
        pendingCompletionAnimations.retainAll(indexes)
        hiddenCompletedIndexes.retainAll(indexes)
        pendingCompletionAnimations.addAll(newlyCompleted)
        refreshDisplayItems()
    }

    fun setPaletteState(
        selectedIndex: Int,
        completedIndexes: Set<Int>,
        paletteProgress: List<Float>
    ) {
        val newlyCompleted = completedIndexes - this.completedIndexes
        val noLongerCompleted = this.completedIndexes - completedIndexes
        this.selectedIndex = selectedIndex
        this.completedIndexes.clear()
        this.completedIndexes.addAll(completedIndexes)
        this.paletteProgress = paletteProgress

        pendingCompletionAnimations.retainAll(completedIndexes)
        runningCompletionAnimations.retainAll(completedIndexes)
        hiddenCompletedIndexes.removeAll(noLongerCompleted)
        if (hasReceivedPaletteState) {
            pendingCompletionAnimations.addAll(newlyCompleted)
        } else {
            // Colours restored from saved progress should not replay their animation.
            hiddenCompletedIndexes.addAll(completedIndexes)
            hasReceivedPaletteState = true
        }

        this.displayItems = buildDisplayItems()
        notifyDataSetChanged()
    }

    fun displayPositionForOriginalIndex(originalIndex: Int): Int {
        return displayItems.indexOfFirst { it.originalIndex == originalIndex }
    }

    private fun buildDisplayItems(): List<DisplayPaletteItem> {
        return items.mapIndexedNotNull { index, item ->
            if (removeCompletedColors && hiddenCompletedIndexes.contains(index)) {
                null
            } else {
                DisplayPaletteItem(index, item)
            }
        }
    }

    private fun refreshDisplayItems() {
        displayItems = buildDisplayItems()
        notifyDataSetChanged()
    }

    private fun playCompletionAnimation(animationView: LottieAnimationView, originalIndex: Int) {
        val listener = object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) = finishCompletionAnimation(originalIndex)

            override fun onAnimationCancel(animation: Animator) = finishCompletionAnimation(originalIndex)
        }

        animationView.apply {
            removeAllAnimatorListeners()
            addAnimatorListener(listener)
            progress = 0f
            // Normalise the source animation to one second, regardless of its JSON duration.
            speed = duration
                .takeIf { it > 0L }
                ?.toFloat()
                ?.div(COMPLETION_ANIMATION_DURATION_MS)
                ?: 1f
            playAnimation()
        }
    }

    private fun finishCompletionAnimation(originalIndex: Int) {
        if (!pendingCompletionAnimations.remove(originalIndex)) return

        runningCompletionAnimations.remove(originalIndex)
        if (removeCompletedColors) {
            hiddenCompletedIndexes.add(originalIndex)
            refreshDisplayItems()
        }
    }

    private fun notifyOriginalIndexChanged(originalIndex: Int) {
        val displayIndex = displayItems.indexOfFirst { it.originalIndex == originalIndex }
        if (displayIndex != -1) {
            notifyItemChanged(displayIndex)
        }
    }
}
