package com.example.baseproject.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.baseproject.fragments.AlbumFragment
import com.example.baseproject.fragments.DailyFragment
import com.example.baseproject.fragments.LibraryFragment
import com.example.baseproject.fragments.MyWorkFragment
import com.example.baseproject.fragments.RealmFragment

class MainVPAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {
    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> LibraryFragment()
            1 -> DailyFragment()
            2 -> RealmFragment()
            3 -> AlbumFragment()
            4 -> MyWorkFragment()
            else -> throw IllegalArgumentException("Invalid position: $position")
        }
    }

    override fun getItemCount(): Int {
        return 5
    }

}