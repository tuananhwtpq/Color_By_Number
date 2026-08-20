package com.example.baseproject.fragments

import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.baseproject.MyApplication
import com.example.baseproject.activities.CollectionDetailActivity
import com.example.baseproject.adapters.CollectionAdapter
import com.example.baseproject.app.SimpleViewModelFactory
import com.example.baseproject.bases.BaseFragment
import com.example.baseproject.data.AlbumCollection
import com.example.baseproject.databinding.FragmentAlbumBinding
import com.example.baseproject.ui.album.AlbumViewModel
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
        binding.rcvCollection.layoutManager = LinearLayoutManager(requireContext())
        binding.rcvCollection.adapter = collectionAdapter

        collectWithLifecycle {
            viewModel.uiState.collectLatest { state ->
                collectionAdapter.submitList(state.collections)
            }
        }
    }

    override fun initActionView() {
    }

    private fun onCollectionClicked(collection: AlbumCollection) {
        startActivity(CollectionDetailActivity.newIntent(requireContext(), collection.id))
    }

}
