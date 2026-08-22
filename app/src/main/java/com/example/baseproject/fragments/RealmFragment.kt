package com.example.baseproject.fragments

import android.content.Intent
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.airbnb.lottie.LottieDrawable
import com.example.baseproject.activities.MainActivity
import com.example.baseproject.activities.RealmFullScreenActivity
import com.example.baseproject.activities.RealmGuideActivity
import com.example.baseproject.activities.RealmRoadActivity
import com.example.baseproject.bases.BaseFragment
import com.example.baseproject.data.Realm
import com.example.baseproject.data.RealmCatalog
import com.example.baseproject.databinding.FragmentRealmBinding
import com.example.baseproject.utils.RealmAnimationCache
import com.example.baseproject.utils.setOnUnDoubleClick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch


class RealmFragment : BaseFragment<FragmentRealmBinding>(FragmentRealmBinding::inflate) {

    private var realm: Realm = RealmCatalog.default
    private var loadRealmJob: Job? = null

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

        binding.btnGuide.setOnUnDoubleClick {
            startActivity(Intent(requireActivity(), RealmGuideActivity::class.java))
        }
    }

    private fun renderRealm() {
        val requestedRealmId = activity?.intent?.getStringExtra(MainActivity.EXTRA_REALM_ID)
        val nextRealm = RealmCatalog.findById(requestedRealmId) ?: RealmCatalog.default
        if (nextRealm == realm && binding.tvRealmName.text == nextRealm.name) return

        val realmToRender = nextRealm
        realm = realmToRender
        binding.tvRealmName.text = realmToRender.name
        binding.ivRealmPlaceholder.setImageResource(realmToRender.thumbnailRes)
        binding.ivRealmPlaceholder.visibility = View.VISIBLE
        binding.progressBar.visibility = View.VISIBLE
        binding.lavRealmBackground.visibility = View.GONE
        binding.lavRealmBackground.cancelAnimation()

        loadRealmJob?.cancel()
        loadRealmJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val composition = RealmAnimationCache.loadComposition(
                    requireContext(),
                    realmToRender.animationRes
                )
                binding.lavRealmBackground.apply {
                    setComposition(composition)
                    repeatCount = LottieDrawable.INFINITE
                    visibility = View.VISIBLE
                    playAnimation()
                }
                binding.progressBar.visibility = View.GONE
                binding.ivRealmPlaceholder.visibility = View.GONE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.lavRealmBackground.apply {
                    setAnimation(realmToRender.animationRes)
                    repeatCount = LottieDrawable.INFINITE
                    visibility = View.VISIBLE
                    playAnimation()
                }
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        loadRealmJob?.cancel()
        loadRealmJob = null
        super.onDestroyView()
    }

}
