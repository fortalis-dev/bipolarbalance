package com.bipolar.balance

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.bipolar.balance.databinding.FragmentSettingsBinding
import com.google.android.material.tabs.TabLayoutMediator

/**
 * Settings tab — hosts four sub-pages via an inner TabLayout + ViewPager2:
 *   1. Backup     — Google Drive sync (existing BackupFragment logic)
 *   2. Custom Data — data point management
 *   3. Widgets    — widget configuration
 *   4. How To     — app guide
 */
class SettingsFragment : Fragment() {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    private val subPages = listOf(
        "Backup"      to { BackupFragment() as Fragment },
        "Custom Data" to { SettingsCustomDataFragment() as Fragment },
        "Widgets"     to { SettingsWidgetsFragment() as Fragment },
        "How To"      to { HelpFragment() as Fragment },
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentSettingsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.settingsViewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = subPages.size
            override fun createFragment(position: Int) = subPages[position].second()
        }
        b.settingsViewPager.offscreenPageLimit = subPages.size - 1

        TabLayoutMediator(b.settingsTabLayout, b.settingsViewPager) { tab, pos ->
            tab.text = subPages[pos].first
        }.attach()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
