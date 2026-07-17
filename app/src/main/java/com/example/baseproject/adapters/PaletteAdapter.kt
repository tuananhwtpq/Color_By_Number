package com.example.baseproject.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.example.baseproject.R
import com.example.baseproject.data.PaletteItem

class PaletteAdapter(
    private val items: List<PaletteItem>,
    private val onColorSelected: (position: Int, item: PaletteItem) -> Unit
) : RecyclerView.Adapter<PaletteAdapter.ViewHolder>() {

    var selectedIndex = 0
        private set

    val completedIndexes = mutableSetOf<Int>()

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val colorCircle: CardView = view.findViewById(R.id.colorCircle)
        val tvNumber: TextView = view.findViewById(R.id.tvNumber)
        val ivCheck: ImageView = view.findViewById(R.id.ivCheck)
        val selectionIndicator: View = view.findViewById(R.id.selectionIndicator)

        init {
            view.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION && !completedIndexes.contains(position)) {
                    setSelection(position)
                    onColorSelected(position, items[position])
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_palette, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.colorCircle.setCardBackgroundColor(item.getTargetColorInt())
        holder.tvNumber.text = item.number.toString()

        // Tính màu chữ (trắng hoặc đen) dựa vào độ sáng của màu nền
        val colorInt = item.getTargetColorInt()
        val r = Color.red(colorInt)
        val g = Color.green(colorInt)
        val b = Color.blue(colorInt)
        val brightness = 0.299 * r + 0.587 * g + 0.114 * b
        holder.tvNumber.setTextColor(if (brightness > 186) Color.BLACK else Color.WHITE)

        val isCompleted = completedIndexes.contains(position)
        val isSelected = position == selectedIndex

        holder.selectionIndicator.visibility =
            if (isSelected && !isCompleted) View.VISIBLE else View.GONE

        if (isCompleted) {
            holder.tvNumber.visibility = View.GONE
            holder.ivCheck.visibility = View.VISIBLE
        } else {
            holder.tvNumber.visibility = View.VISIBLE
            holder.ivCheck.visibility = View.GONE
        }
    }

    override fun getItemCount() = items.size

    fun setSelection(position: Int) {
        val prev = selectedIndex
        selectedIndex = position
        notifyItemChanged(prev)
        notifyItemChanged(selectedIndex)
    }

    fun markCompleted(position: Int) {
        completedIndexes.add(position)
        notifyItemChanged(position)
    }

    fun setCompletedIndexes(indexes: Set<Int>) {
        completedIndexes.clear()
        completedIndexes.addAll(indexes)
        notifyDataSetChanged()
    }
}
