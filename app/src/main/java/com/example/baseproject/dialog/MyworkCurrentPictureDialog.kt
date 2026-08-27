package com.example.baseproject.dialog

import android.view.View
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.baseproject.R
import com.example.baseproject.bases.BaseDialog
import com.example.baseproject.databinding.FragmentMyworkCurrentPictureDialogBinding
import com.example.baseproject.utils.setOnUnDoubleClick
import java.io.File


class MyworkCurrentPictureDialog : BaseDialog<FragmentMyworkCurrentPictureDialogBinding>(
    FragmentMyworkCurrentPictureDialogBinding::inflate
) {
    companion object {
        const val TAG = "MyworkCurrentPictureDialog"
    }

    var previewFile: File? = null
    var isCompleted: Boolean = false
    var onColor: (() -> Unit)? = null
    var onReset: (() -> Unit)? = null
    var onDelete: (() -> Unit)? = null
    var onSave: (() -> Unit)? = null
    var onShare: (() -> Unit)? = null

    override fun initView() {
        Glide.with(this)
            .load(previewFile)
            .placeholder(R.color.white)
            .error(R.color.white)
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .into(binding.ivImage)

        binding.btnColor.visibility = if (isCompleted) View.GONE else View.VISIBLE
        val completedActionVisibility = if (isCompleted) View.VISIBLE else View.GONE
        binding.btnSave.visibility = completedActionVisibility
        binding.tvSave.visibility = completedActionVisibility
        binding.btnShare.visibility = completedActionVisibility
        binding.tvShare.visibility = completedActionVisibility
    }

    override fun initActionView() {
        binding.btnClose.setOnUnDoubleClick { dismiss() }
        binding.btnColor.setOnUnDoubleClick {
            onColor?.invoke()
            dismiss()
        }
        binding.btnReset.setOnUnDoubleClick {
            onReset?.invoke()
            dismiss()
        }
        binding.btnDelete.setOnUnDoubleClick {
            onDelete?.invoke()
            dismiss()
        }
        binding.btnSave.setOnUnDoubleClick {
            onSave?.invoke()
            dismiss()
        }
        binding.btnShare.setOnUnDoubleClick {
            onShare?.invoke()
            dismiss()
        }
    }

}
