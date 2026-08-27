package com.example.baseproject.fragments

import android.content.Intent
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.airbnb.lottie.LottieDrawable
import com.bumptech.glide.Glide
import com.example.baseproject.MyApplication
import com.example.baseproject.activities.RealmFullScreenActivity
import com.example.baseproject.activities.RealmGuideActivity
import com.example.baseproject.activities.RealmRoadActivity
import com.example.baseproject.bases.BaseFragment
import com.example.baseproject.data.Realm
import com.example.baseproject.data.RealmCatalog
import com.example.baseproject.databinding.FragmentRealmBinding
import com.example.baseproject.utils.RealmAnimationCache
import com.example.baseproject.utils.SharedPrefManager
import com.example.baseproject.utils.setOnUnDoubleClick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


class RealmFragment : BaseFragment<FragmentRealmBinding>(FragmentRealmBinding::inflate) {

    private var realm: Realm = RealmCatalog.default
    private var loadRealmAnimationJob: Job? = null
    private var loadRemoteRealmJob: Job? = null
    private var lastRemoteRealmRequestId: String? = null
    private var loadedRemoteRealmRequestId: String? = null
    private val appContainer by lazy {
        (requireActivity().application as MyApplication).appContainer
    }

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
        val requestedRealmId = SharedPrefManager.selectedRealmId
        val localRealm = RealmCatalog.findById(requestedRealmId) ?: RealmCatalog.default
        val remoteRequestId = requestedRealmId ?: localRealm.id
        if (remoteRequestId == lastRemoteRealmRequestId &&
            (remoteRequestId == loadedRemoteRealmRequestId || loadRemoteRealmJob?.isActive == true)
        ) {
            return
        }

        lastRemoteRealmRequestId = remoteRequestId
        renderRealm(localRealm)

        loadRemoteRealmJob?.cancel()
        loadRemoteRealmJob = viewLifecycleOwner.lifecycleScope.launch {
            val remoteRealm = try {
                appContainer.realmRepository.loadRealm(remoteRequestId)
                    ?: appContainer.realmRepository.loadRealm(RealmCatalog.default.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            remoteRealm?.let {
                loadedRemoteRealmRequestId = remoteRequestId
                renderRealm(it)
            }
        }
    }

    private fun renderRealm(realmToRender: Realm) {
        if (realmToRender == realm && binding.tvRealmName.text == realmToRender.name) return

        realm = realmToRender
        binding.tvRealmName.text = realmToRender.name
        if (!realmToRender.previewImageUrl.isNullOrBlank()) {
            Glide.with(binding.ivRealmPlaceholder)
                .load(realmToRender.previewImageUrl)
                .into(binding.ivRealmPlaceholder)
        } else {
            binding.ivRealmPlaceholder.setImageResource(realmToRender.thumbnailRes)
        }
        binding.ivRealmPlaceholder.visibility = View.VISIBLE
        binding.progressBar.visibility = View.VISIBLE
        binding.lavRealmBackground.visibility = View.GONE
        binding.lavRealmBackground.cancelAnimation()

        loadRealmAnimationJob?.cancel()
        loadRealmAnimationJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (!realmToRender.animationUrl.isNullOrBlank()) {
                    binding.lavRealmBackground.apply {
                        setAnimationFromUrl(realmToRender.animationUrl)
                        repeatCount = LottieDrawable.INFINITE
                        visibility = View.VISIBLE
                        playAnimation()
                    }
                } else {
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
                }
                binding.progressBar.visibility = View.GONE
                binding.ivRealmPlaceholder.visibility = View.GONE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (realmToRender.animationRes != 0) {
                    binding.lavRealmBackground.apply {
                        setAnimation(realmToRender.animationRes)
                        repeatCount = LottieDrawable.INFINITE
                        visibility = View.VISIBLE
                        playAnimation()
                    }
                }
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        loadRealmAnimationJob?.cancel()
        loadRemoteRealmJob?.cancel()
        loadRealmAnimationJob = null
        loadRemoteRealmJob = null
        super.onDestroyView()
    }

}
