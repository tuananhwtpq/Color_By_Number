package com.pixlory.color.by.number.fragments

import android.content.Intent
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.airbnb.lottie.LottieDrawable
import com.bumptech.glide.Glide
import com.pixlory.color.by.number.MyApplication
import com.pixlory.color.by.number.activities.RealmFullScreenActivity
import com.pixlory.color.by.number.activities.RealmGuideActivity
import com.pixlory.color.by.number.activities.RealmRoadActivity
import com.pixlory.color.by.number.bases.BaseFragment
import com.pixlory.color.by.number.data.Realm
import com.pixlory.color.by.number.data.RealmCatalog
import com.pixlory.color.by.number.databinding.FragmentRealmBinding
import com.pixlory.color.by.number.utils.RealmAnimationCache
import com.pixlory.color.by.number.utils.SharedPrefManager
import com.pixlory.color.by.number.utils.setOnUnDoubleClick
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
    private var isRealmVisualReady = false
    private val appContainer by lazy {
        (requireActivity().application as MyApplication).appContainer
    }

    override fun initData() {

    }

    override fun initView() {
        updateFullScreenEnabled(false)
        renderRealm()
    }

    override fun onResume() {
        super.onResume()
        renderRealm()
    }

    override fun initActionView() {
        binding.btnFullScreen.setOnUnDoubleClick {
            if (!isRealmVisualReady) return@setOnUnDoubleClick
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
                appContainer.realmContentPreloader.preload(remoteRequestId).await()
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
        val displayName = realmToRender.displayName(requireContext())
        if (realmToRender == realm && binding.tvRealmName.text == displayName) return

        realm = realmToRender
        binding.tvRealmName.text = displayName
        binding.ivRealmPlaceholder.setImageResource(realmToRender.thumbnailRes)
        if (!realmToRender.previewImageUrl.isNullOrBlank()) {
            Glide.with(binding.ivRealmPlaceholder)
                .load(realmToRender.previewImageUrl)
                .into(binding.ivRealmPlaceholder)
        }
        binding.ivRealmPlaceholder.visibility = View.VISIBLE
        binding.progressBar.visibility = View.VISIBLE
        binding.lavRealmBackground.visibility = View.GONE
        binding.lavRealmBackground.cancelAnimation()
        isRealmVisualReady = false
        updateFullScreenEnabled(false)

        loadRealmAnimationJob?.cancel()
        loadRealmAnimationJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val composition = if (!realmToRender.animationUrl.isNullOrBlank()) {
                    RealmAnimationCache.loadRemoteComposition(
                        requireContext(),
                        realmToRender.animationUrl
                    )
                } else {
                    RealmAnimationCache.loadComposition(
                        requireContext(),
                        realmToRender.animationRes
                    )
                }
                binding.lavRealmBackground.apply {
                    setComposition(composition)
                    repeatCount = LottieDrawable.INFINITE
                    visibility = View.VISIBLE
                    playAnimation()
                }
                binding.progressBar.visibility = View.GONE
                binding.ivRealmPlaceholder.visibility = View.GONE
                isRealmVisualReady = true
                updateFullScreenEnabled(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (realmToRender.animationRes != 0) {
                    val fallbackComposition = RealmAnimationCache.loadComposition(
                        requireContext(),
                        realmToRender.animationRes
                    )
                    binding.lavRealmBackground.apply {
                        setComposition(fallbackComposition)
                        repeatCount = LottieDrawable.INFINITE
                        visibility = View.VISIBLE
                        playAnimation()
                    }
                    binding.ivRealmPlaceholder.visibility = View.GONE
                    isRealmVisualReady = true
                    updateFullScreenEnabled(true)
                }
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun updateFullScreenEnabled(enabled: Boolean) {
        binding.btnFullScreen.isEnabled = enabled
        binding.btnFullScreen.alpha = if (enabled) 1f else 0.5f
    }

    override fun onDestroyView() {
        loadRealmAnimationJob?.cancel()
        loadRemoteRealmJob?.cancel()
        loadRealmAnimationJob = null
        loadRemoteRealmJob = null
        super.onDestroyView()
    }

}
