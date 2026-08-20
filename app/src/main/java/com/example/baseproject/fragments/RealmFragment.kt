package com.example.baseproject.fragments

import com.example.baseproject.activities.RealmFullScreenActivity
import com.example.baseproject.bases.BaseFragment
import com.example.baseproject.data.Realm
import com.example.baseproject.data.RealmCatalog
import com.example.baseproject.databinding.FragmentRealmBinding
import com.example.baseproject.utils.setOnUnDoubleClick


class RealmFragment : BaseFragment<FragmentRealmBinding>(FragmentRealmBinding::inflate) {

    private var realm: Realm = RealmCatalog.default

    override fun initData() {

    }

    override fun initView() {
        binding.tvRealmName.text = realm.name
        binding.lavRealmBackground.setAnimation(realm.animationRes)
    }

    override fun initActionView() {
        binding.btnFullScreen.setOnUnDoubleClick {
            startActivity(
                RealmFullScreenActivity.newIntent(
                    requireContext(),
                    realm.id,
                    binding.lavRealmBackground.progress
                )
            )
        }
    }

}
