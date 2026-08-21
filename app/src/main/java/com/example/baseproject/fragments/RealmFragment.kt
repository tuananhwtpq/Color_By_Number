package com.example.baseproject.fragments

import android.content.Intent
import com.example.baseproject.activities.MainActivity
import com.example.baseproject.activities.RealmFullScreenActivity
import com.example.baseproject.activities.RealmRoadActivity
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
        renderRealm()
    }

    override fun onResume() {
        super.onResume()
        renderRealm()
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

        binding.btnNewRealm.setOnUnDoubleClick {
            startActivity(Intent(requireContext(), RealmRoadActivity::class.java))
        }
    }

    private fun renderRealm() {
        val requestedRealmId = activity?.intent?.getStringExtra(MainActivity.EXTRA_REALM_ID)
        val nextRealm = RealmCatalog.findById(requestedRealmId) ?: RealmCatalog.default
        if (nextRealm == realm && binding.tvRealmName.text == nextRealm.name) return

        realm = nextRealm
        binding.tvRealmName.text = realm.name
        binding.lavRealmBackground.setAnimation(realm.animationRes)
        binding.lavRealmBackground.playAnimation()
    }

}
