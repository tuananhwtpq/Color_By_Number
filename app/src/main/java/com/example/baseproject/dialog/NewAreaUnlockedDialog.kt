package com.example.baseproject.dialog

import com.example.baseproject.bases.BaseDialog
import com.example.baseproject.data.Realm
import com.example.baseproject.data.RealmCatalog
import com.example.baseproject.databinding.FragmentNewAreaUnlockedDialogBinding
import com.example.baseproject.utils.setOnUnDoubleClick

class NewAreaUnlockedDialog : BaseDialog<FragmentNewAreaUnlockedDialogBinding>(
    FragmentNewAreaUnlockedDialogBinding::inflate,
) {

    companion object {
        const val TAG = "NewAreaUnlockedDialog"
    }

    var realm: Realm = RealmCatalog.default
    var onGoToColorRealm: (() -> Unit)? = null

    override fun initView() = with(binding) {
        tvRealmName.text = realm.name
        ivRealmThumbnail.setImageResource(realm.thumbnailRes)
    }

    override fun initActionView() {
        binding.btnClose.setOnUnDoubleClick { dismiss() }
        binding.btnGoToColorRealm.setOnUnDoubleClick {
            dismiss()
            onGoToColorRealm?.invoke()
        }
    }
}
