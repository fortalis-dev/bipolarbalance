package com.bipolar.balance

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.viewpager2.widget.ViewPager2
import com.bipolar.balance.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val pages = listOf(
        DashboardFragment()  to R.id.nav_dashboard,
        DriveInputFragment() to R.id.nav_drive,
        DailyEntryFragment() to R.id.nav_daily,
        InsightsFragment()   to R.id.nav_histogram,
        SettingsFragment()   to R.id.nav_backup,
    )

    private val titles = listOf(
        R.string.nav_dashboard,
        R.string.nav_drive,
        R.string.nav_daily,
        R.string.nav_insights,
        R.string.nav_settings,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Handle window insets for edge-to-edge
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            
            // Apply top padding to AppBarLayout
            b.root.findViewById<android.view.View>(R.id.toolbar)?.parent?.let { appBar ->
                (appBar as? com.google.android.material.appbar.AppBarLayout)?.updatePadding(top = insets.top)
            }

            // Apply bottom padding to BottomNavigationView
            b.bottomNav.updatePadding(bottom = insets.bottom)
            
            windowInsets
        }

        setSupportActionBar(b.toolbar)
        supportActionBar?.title = getString(titles[0])

        b.viewPager.adapter = MainPagerAdapter(this, pages.map { it.first })
        b.viewPager.offscreenPageLimit = pages.size - 1

        // ViewPager2 → BottomNav sync
        b.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                b.bottomNav.menu.findItem(pages[position].second)?.isChecked = true
                supportActionBar?.title = getString(titles[position])
            }
        })

        // BottomNav → ViewPager2 sync
        b.bottomNav.setOnItemSelectedListener { item ->
            val pos = pages.indexOfFirst { it.second == item.itemId }
            if (pos >= 0) {
                b.viewPager.setCurrentItem(pos, true)
                true
            } else false
        }
    }

    /** Navigate to the Settings tab (index 4). Called from DailyEntryFragment. */
    fun navigateToSettings() {
        b.viewPager.setCurrentItem(4, true)
    }
}
