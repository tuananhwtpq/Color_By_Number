package com.example.baseproject.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.baseproject.fragments.AlbumFragment
import com.example.baseproject.fragments.LibraryFragment
import com.example.baseproject.fragments.MyWorkFragment
import com.example.baseproject.fragments.RealmFragment
import com.example.baseproject.fragments.SettingFragment

class MainVPAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {
    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> LibraryFragment()
            1 -> AlbumFragment()
            2 -> RealmFragment()
            3 -> MyWorkFragment()
            4 -> SettingFragment()
            else -> throw IllegalArgumentException("Invalid position: $position")
        }
    }

    override fun getItemCount(): Int {
        return 5
    }

}
