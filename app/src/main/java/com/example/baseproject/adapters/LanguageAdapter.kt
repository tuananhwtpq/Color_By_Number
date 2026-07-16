package com.example.baseproject.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.baseproject.R
import com.example.baseproject.databinding.ItemLanguageBinding
import com.example.baseproject.models.LanguageModel

class LanguageAdapter(
    private val languageList: List<LanguageModel>,
    private val onFirstSelect: () -> Unit,
) : RecyclerView.Adapter<LanguageAdapter.LanguageViewHolder>() {

    private var selectedLanguage: LanguageModel? = null
    private var selectedPosition = RecyclerView.NO_POSITION

    inner class LanguageViewHolder(
        private val binding: ItemLanguageBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(language: LanguageModel, isSelected: Boolean) = with(binding) {
            ivLanguage.setImageResource(language.img)
            languageName.setText(language.name)
            languageName.setHorizontallyScrolling(true)
            languageName.isSelected = true

            layoutRoot.setBackgroundResource(
                if (isSelected) R.drawable.bg_language_selected
                else R.drawable.bg_language_unselected
            )
            ivRadio.setImageResource(
                if (isSelected) R.drawable.ic_checked_language
                else R.drawable.ic_unchecked_language
            )

            root.setOnClickListener {
                val currentPosition = bindingAdapterPosition
                if (currentPosition == RecyclerView.NO_POSITION || currentPosition == selectedPosition) {
                    return@setOnClickListener
                }

                updateSelection(currentPosition)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LanguageViewHolder {
        val binding = ItemLanguageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return LanguageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LanguageViewHolder, position: Int) {
        val language = languageList[position]
        holder.bind(language, language == selectedLanguage)
    }

    override fun getItemCount(): Int = languageList.size

    fun getSelectedPositionLanguage(): LanguageModel? = selectedLanguage

    fun setSelectedPositionLanguage(language: LanguageModel) {
        val newPosition = languageList.indexOf(language)
        if (newPosition == -1) return
        updateSelection(newPosition)
    }

    private fun updateSelection(newPosition: Int) {
        val previousPosition = selectedPosition
        val isFirstSelection = selectedLanguage == null

        selectedPosition = newPosition
        selectedLanguage = languageList[newPosition]

        if (isFirstSelection) {
            onFirstSelect()
        }

        if (previousPosition != RecyclerView.NO_POSITION) {
            notifyItemChanged(previousPosition)
        }
        notifyItemChanged(newPosition)
    }
}
