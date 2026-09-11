package com.pixlory.color.by.number.fragments

import android.content.Intent
import android.view.View
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.pixlory.color.by.number.MyApplication
import com.pixlory.color.by.number.activities.AchieveActivity
import com.pixlory.color.by.number.activities.CollectionDetailActivity
import com.pixlory.color.by.number.adapters.CollectionAdapter
import com.pixlory.color.by.number.app.SimpleViewModelFactory
import com.pixlory.color.by.number.bases.BaseFragment
import com.pixlory.color.by.number.data.AlbumCollection
import com.pixlory.color.by.number.databinding.FragmentAlbumBinding
import com.pixlory.color.by.number.ui.album.AlbumViewModel
import com.pixlory.color.by.number.utils.AppThemeManager
import com.pixlory.color.by.number.utils.setOnUnDoubleClick
import kotlinx.coroutines.flow.collectLatest

class AlbumFragment : BaseFragment<FragmentAlbumBinding>(FragmentAlbumBinding::inflate) {

    private val appContainer by lazy {
        (requireActivity().application as MyApplication).appContainer
    }

    private val viewModel: AlbumViewModel by viewModels {
        SimpleViewModelFactory {
            AlbumViewModel(appContainer.collectionRepository)
        }
    }

    private val collectionAdapter by lazy {
        CollectionAdapter { collection -> onCollectionClicked(collection) }
    }

    override fun initData() {

    }

    override fun initView() {
        AppThemeManager.applyFullBackground(binding.root)
        binding.rcvCollection.layoutManager = LinearLayoutManager(requireContext())
        binding.rcvCollection.adapter = collectionAdapter

        collectWithLifecycle {
            viewModel.uiState.collectLatest { state ->
                collectionAdapter.submitList(state.collections)
            }
        }
    }

    override fun initActionView() {

        binding.btnAchieve.setOnUnDoubleClick {
            startActivity(Intent(requireActivity(), AchieveActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        AppThemeManager.applyFullBackground(binding.root)
    }

    private fun onCollectionClicked(collection: AlbumCollection) {
        startActivity(CollectionDetailActivity.newIntent(requireContext(), collection.id))
    }

}
