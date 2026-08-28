package blbl.cat3399.core.ui

import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout

fun Fragment.findCurrentViewPagerChildFragment(viewPager: ViewPager2): Fragment? {
    val adapter = viewPager.adapter as? FragmentStateAdapter
    if (adapter != null) {
        val itemId = adapter.getItemId(viewPager.currentItem)
        childFragmentManager.findFragmentByTag("f$itemId")?.let { return it }
    }
    return childFragmentManager.fragments.firstOrNull { it.isVisible }
}

inline fun <reified T> Fragment.findCurrentViewPagerChildFragmentAs(viewPager: ViewPager2): T? {
    val current = findCurrentViewPagerChildFragment(viewPager)
    if (current is T) return current
    return childFragmentManager.fragments.firstOrNull { it.isVisible && it is T } as? T
}

fun TabLayout.requestFocusSelectedTab(
    fallbackPosition: Int = 0,
    isAlive: () -> Boolean,
): Boolean {
    val tabStrip = getChildAt(0) as? ViewGroup ?: return false
    val pos = selectedTabPosition.takeIf { it >= 0 } ?: fallbackPosition
    if (pos < 0 || pos >= tabStrip.childCount) return false
    postIfAlive(isAlive = isAlive) {
        tabStrip.getChildAt(pos)?.requestFocus()
    }
    return true
}

/**
 * Drop-in replacement for [TabLayoutMediator] that switches pages instantly
 * (`setCurrentItem(position, false)`) instead of smooth-scrolling, and never lets the pager
 * animate while the user flips tabs with the shoulder buttons.
 */
class InstantTabPagerMediator(
    private val tabLayout: TabLayout,
    private val viewPager: ViewPager2,
    private val configureTab: (TabLayout.Tab, Int) -> Unit,
) {
    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            if (tabLayout.selectedTabPosition != position) {
                tabLayout.getTabAt(position)?.select()
            }
        }
    }

    private val tabListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab) {
            if (viewPager.currentItem != tab.position) {
                viewPager.setCurrentItem(tab.position, false)
            }
        }

        override fun onTabUnselected(tab: TabLayout.Tab) = Unit

        override fun onTabReselected(tab: TabLayout.Tab) = Unit
    }

    fun attach() {
        viewPager.registerOnPageChangeCallback(pageCallback)
        tabLayout.addOnTabSelectedListener(tabListener)
        populateTabs()
    }

    fun detach() {
        viewPager.unregisterOnPageChangeCallback(pageCallback)
        tabLayout.removeOnTabSelectedListener(tabListener)
    }

    private fun populateTabs() {
        tabLayout.removeAllTabs()
        val count = viewPager.adapter?.itemCount ?: 0
        for (position in 0 until count) {
            val tab = tabLayout.newTab()
            configureTab(tab, position)
            tabLayout.addTab(tab, false)
        }
        val current = viewPager.currentItem
        if (current in 0 until count) {
            tabLayout.getTabAt(current)?.select()
        }
    }
}

/**
 * Tab strip is switched with the shoulder buttons only: keep it out of the focus traversal
 * entirely so DPAD navigation never lands on a tab.
 */
fun TabLayout.disableDpadTabFocus() {
    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    isFocusable = false
    isFocusableInTouchMode = false
}
