package com.example.kotlinroomdatabase.fragments.analytics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.example.kotlinroomdatabase.InterfaceAppearance
import com.example.kotlinroomdatabase.R

/** Keep the navigation destination stable when switching the app-wide interface. */
class AnalyticsFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        FrameLayout(requireContext()).apply { id = R.id.analyticsContent }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val modern = InterfaceAppearance.isModern(requireContext())
        val tag = if (modern) "analytics-modern" else "analytics-classic"
        if (childFragmentManager.findFragmentByTag(tag) == null) {
            childFragmentManager.beginTransaction().replace(
                R.id.analyticsContent,
                if (modern) ModernAnalyticsFragment() else LegacyAnalyticsFragment(),
                tag
            ).commitNow()
        }
    }
}
