package com.pixlory.color.by.number.adapters

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.Color
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
    private val completionTracker = PaletteCompletionStateTracker()
    private var hasReceivedPaletteState = false
    private var attachedRecyclerView: RecyclerView? = null
    private var paletteProgress: List<Float> = List(items.size) { 0f }
    private var displayItems: List<DisplayPaletteItem> = buildDisplayItems()

    val sourceItemCount: Int
        get() = items.size

    private data class DisplayPaletteItem(
        val originalIndex: Int,
        val item: PaletteItem
    )

    private data class BoundCompletionAnimation(
        val originalIndex: Int,
        val token: Long,
        val listener: AnimatorListenerAdapter
    )

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val colorCircle: CardView = view.findViewById(R.id.colorCircle)
        val tvNumber: TextView = view.findViewById(R.id.tvNumber)
        val ivCheck: LottieAnimationView = view.findViewById(R.id.ivCheck)
        val ringView: PaletteRingView = view.findViewById(R.id.ringView)
        private var boundOriginalIndex = RecyclerView.NO_POSITION
        private var boundCompletionAnimation: BoundCompletionAnimation? = null

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

        fun bindTo(originalIndex: Int) {
            if (boundOriginalIndex == originalIndex) return

            cancelBoundCompletionAnimation()
            boundOriginalIndex = originalIndex
        }

        fun startCompletionAnimation(token: Long) {
            if (boundCompletionAnimation?.let {
                    it.originalIndex == boundOriginalIndex && it.token == token
                } == true) {
                return
            }

            cancelBoundCompletionAnimation()
            val originalIndex = boundOriginalIndex
            if (originalIndex == RecyclerView.NO_POSITION) return

            lateinit var animation: BoundCompletionAnimation
            val listener = object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animator: Animator) = completeBoundAnimation(animation)

                override fun onAnimationCancel(animator: Animator) = completeBoundAnimation(animation)
            }
            animation = BoundCompletionAnimation(originalIndex, token, listener)
            boundCompletionAnimation = animation

            runCatching {
                ivCheck.apply {
                    addAnimatorListener(listener)
                    progress = 0f
                    speed = duration
                        .takeIf { it > 0L }
                        ?.toFloat()
                        ?.div(COMPLETION_ANIMATION_DURATION_MS)
                        ?: 1f
                    playAnimation()
                }
            }.onFailure {
                completeBoundAnimation(animation)
            }
        }

        fun cancelBoundCompletionAnimation() {
            val animation = boundCompletionAnimation ?: return
            boundCompletionAnimation = null
            ivCheck.removeAnimatorListener(animation.listener)
            ivCheck.cancelAnimation()
            scheduleCompletion(animation.originalIndex, animation.token)
        }

        private fun completeBoundAnimation(animation: BoundCompletionAnimation) {
            if (boundCompletionAnimation != animation) return

            boundCompletionAnimation = null
            ivCheck.removeAnimatorListener(animation.listener)
            scheduleCompletion(animation.originalIndex, animation.token)
        }
    }

    init {
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_palette, parent, false)
        return ViewHolder(view)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        if (attachedRecyclerView === recyclerView) {
            attachedRecyclerView = null
        }
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val displayItem = displayItems[position]
        val item = displayItem.item
        val originalIndex = displayItem.originalIndex
        holder.bindTo(originalIndex)
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

        when {
            isCompleted -> {
                holder.ringView.setRingState(PaletteRingView.MODE_NONE)
                holder.tvNumber.visibility = View.GONE
                holder.ivCheck.visibility = View.VISIBLE
                completionTracker.claimAnimation(originalIndex)?.let(holder::startCompletionAnimation)
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

    override fun getItemId(position: Int): Long = displayItems[position].originalIndex.toLong()

    override fun onViewRecycled(holder: ViewHolder) {
        holder.cancelBoundCompletionAnimation()
        super.onViewRecycled(holder)
    }

    fun setSelection(originalIndex: Int) {
        val prev = selectedIndex
        selectedIndex = originalIndex
        notifyOriginalIndexChanged(prev)
        notifyOriginalIndexChanged(selectedIndex)
    }

    fun markCompleted(originalIndex: Int) {
        if (!completedIndexes.contains(originalIndex)) {
            completionTracker.queue(originalIndex)
            playColorCompletedSound()
        }
        completedIndexes.add(originalIndex)
        refreshDisplayItems()
    }

    fun setCompletedIndexes(indexes: Set<Int>) {
        val newlyCompleted = indexes - completedIndexes
        val noLongerCompleted = completedIndexes - indexes
        completedIndexes.clear()
        completedIndexes.addAll(indexes)
        completionTracker.retainOnly(indexes)
        noLongerCompleted.forEach(completionTracker::clear)
        newlyCompleted.forEach(completionTracker::queue)
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

        completionTracker.retainOnly(completedIndexes)
        noLongerCompleted.forEach(completionTracker::clear)
        if (hasReceivedPaletteState) {
            newlyCompleted.forEach(completionTracker::queue)
            if (newlyCompleted.isNotEmpty()) {
                playColorCompletedSound()
            }
        } else {
            // Colours restored from saved progress should not replay their animation.
            completedIndexes.forEach(completionTracker::restoreAsRemoved)
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
            if (removeCompletedColors && completedIndexes.contains(index) &&
                !completionTracker.isVisible(index)
            ) {
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

    private fun scheduleCompletion(originalIndex: Int, token: Long) {
        // Animator callbacks can happen while RecyclerView is laying out. Post the removal so
        // the callback for one item cannot interrupt the ViewHolder of another animation.
        if (!displayItems.any { it.originalIndex == originalIndex }) return
        // The main-loop queue also serialises simultaneous A/B completion callbacks.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            finishCompletionAnimation(originalIndex, token)
        }
    }

    private fun finishCompletionAnimation(originalIndex: Int, token: Long) {
        if (!completionTracker.complete(originalIndex, token) || !removeCompletedColors) return

        val displayIndex = displayItems.indexOfFirst { it.originalIndex == originalIndex }
        if (displayIndex == -1) return

        displayItems = buildDisplayItems()
        notifyItemRemoved(displayIndex)
    }

    private fun notifyOriginalIndexChanged(originalIndex: Int) {
        val displayIndex = displayItems.indexOfFirst { it.originalIndex == originalIndex }
        if (displayIndex != -1) {
            notifyItemChanged(displayIndex)
        }
    }

    private fun playColorCompletedSound() {
        attachedRecyclerView?.context
            ?.soundManagerOrNull()
            ?.play(SoundEffect.COLOR_COMPLETED)
    }
}
