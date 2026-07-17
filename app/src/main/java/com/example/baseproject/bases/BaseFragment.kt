package com.example.baseproject.bases

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.launch

typealias Inflater<T> = (LayoutInflater, ViewGroup?, Boolean) -> T
abstract class BaseFragment<viewBinding : ViewBinding>(val inflate: Inflater<viewBinding>) : Fragment() {

    private var _binding : viewBinding ?= null
    val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initData()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = inflate.invoke(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initActionView()
    }

    abstract fun initData()

    abstract fun initView()

    abstract fun initActionView()

    protected fun collectWithLifecycle(
        minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
        collector: suspend () -> Unit
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(minActiveState) {
                collector()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

}
