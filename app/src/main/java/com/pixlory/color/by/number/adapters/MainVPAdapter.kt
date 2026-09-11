package com.pixlory.color.by.number.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.pixlory.color.by.number.fragments.AlbumFragment
import com.pixlory.color.by.number.fragments.LibraryFragment
import com.pixlory.color.by.number.fragments.MyWorkFragment
import com.pixlory.color.by.number.fragments.RealmFragment
import com.pixlory.color.by.number.fragments.SettingFragment

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
