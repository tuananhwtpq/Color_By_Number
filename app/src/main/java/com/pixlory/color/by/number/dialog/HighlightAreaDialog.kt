package com.pixlory.color.by.number.dialog

import android.os.Bundle
import androidx.core.content.ContextCompat
import com.pixlory.color.by.number.R
import com.pixlory.color.by.number.bases.BaseDialog
import com.pixlory.color.by.number.databinding.FragmentHighlightAreaDialogBinding
import com.pixlory.color.by.number.highlight.HighlightThemes
import com.pixlory.color.by.number.utils.setOnUnDoubleClick
import com.pixlory.color.by.number.views.HighlightOptionView


class HighlightAreaDialog : BaseDialog<FragmentHighlightAreaDialogBinding>(
    FragmentHighlightAreaDialogBinding::inflate
) {
    companion object {
        const val TAG = "HighlightAreaDialog"
        private const val ARG_SELECTED_THEME_ID = "SELECTED_THEME_ID"

        fun newInstance(selectedThemeId: String): HighlightAreaDialog =
            HighlightAreaDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_SELECTED_THEME_ID, selectedThemeId)
                }
            }
    }

    var onThemeSelected: ((String) -> Unit)? = null
    private var selectedThemeId: String = HighlightThemes.ID_GRAY_CHECKER
    private lateinit var optionViews: List<Pair<HighlightOptionView, String>>

    override fun initView() {
        selectedThemeId = arguments?.getString(
            ARG_SELECTED_THEME_ID,
            HighlightThemes.ID_GRAY_CHECKER
        ) ?: HighlightThemes.ID_GRAY_CHECKER
        optionViews = listOf(
            binding.btnGrayChecker to HighlightThemes.ID_GRAY_CHECKER,
            binding.btnOrangeChecker to HighlightThemes.ID_ORANGE_CHECKER,
            binding.btnBlueChecker to HighlightThemes.ID_BLUE_CHECKER,
            binding.btnSolidGray to HighlightThemes.ID_SOLID_GRAY,
        )
        renderOptionPreviews()
        renderSelection()
    }

    override fun initActionView() {
        binding.btnClose.setOnUnDoubleClick { dismiss() }
        optionViews.forEach { (view, themeId) ->
            view.setOnUnDoubleClick {
                selectedThemeId = themeId
                renderSelection()
            }
        }
        binding.btnConfirm.setOnUnDoubleClick {
            onThemeSelected?.invoke(selectedThemeId)
            dismiss()
        }
    }

    private fun renderSelection() {
        optionViews.forEach { (view, themeId) ->
            view.isSelected = themeId == selectedThemeId
        }
    }

    private fun renderOptionPreviews() {
        binding.btnGrayChecker.setCheckerPreview(
            color(R.color.grey_100),
            color(R.color.grey_600)
        )
        binding.btnOrangeChecker.setCheckerPreview(
            color(R.color.grey_200),
            color(R.color.orange450)
        )
        binding.btnBlueChecker.setCheckerPreview(
            color(R.color.grey_200),
            color(R.color.baby_blue_500)
        )
        binding.btnSolidGray.setSolidPreview(color(R.color.grey_400))
    }

    private fun color(colorRes: Int): Int = ContextCompat.getColor(requireContext(), colorRes)

}
