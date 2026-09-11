package com.pixlory.color.by.number.dialog

import com.bumptech.glide.Glide
import com.pixlory.color.by.number.bases.BaseDialog
import com.pixlory.color.by.number.data.Realm
import com.pixlory.color.by.number.data.RealmCatalog
import com.pixlory.color.by.number.databinding.FragmentNewAreaUnlockedDialogBinding
import com.pixlory.color.by.number.utils.setOnUnDoubleClick

class NewAreaUnlockedDialog : BaseDialog<FragmentNewAreaUnlockedDialogBinding>(
    FragmentNewAreaUnlockedDialogBinding::inflate,
) {

    companion object {
        const val TAG = "NewAreaUnlockedDialog"
    }

    var realm: Realm = RealmCatalog.default
    var onGoToColorRealm: (() -> Unit)? = null

    override fun initView() {
        with(binding) {
            tvRealmName.text = realm.displayName(requireContext())
            if (!realm.previewImageUrl.isNullOrBlank()) {
                Glide.with(ivRealmThumbnail)
                    .load(realm.previewImageUrl)
                    .into(ivRealmThumbnail)
            } else {
                ivRealmThumbnail.setImageResource(realm.thumbnailRes)
            }
        }
    }

    override fun initActionView() {
        binding.btnClose.setOnUnDoubleClick { dismiss() }
        binding.btnGoToColorRealm.setOnUnDoubleClick {
            dismiss()
            onGoToColorRealm?.invoke()
        }
    }
}
