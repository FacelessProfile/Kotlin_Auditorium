package com.example.kotlinroomdatabase.fragments.analytics

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.databinding.FragmentModernAnalyticsBinding
import com.example.kotlinroomdatabase.databinding.ItemAnalyticsEntryBinding
import com.example.kotlinroomdatabase.getColorFromAttr
import com.example.kotlinroomdatabase.model.*
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import com.example.kotlinroomdatabase.util.RoleUtils
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.RadarData
import com.github.mikephil.charting.data.RadarDataSet
import com.github.mikephil.charting.data.RadarEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class ModernAnalyticsFragment : Fragment() {
    private var _binding: FragmentModernAnalyticsBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: StudentRepositoryHTTPS
    private var request: Job? = null
    private var entries = emptyList<AnalyticsEntry>()
    private var subjects = emptyList<TeacherSubject>()
    private var subjectId = -1
    private var groupId = -1
    private var selectedId = -1
    private var metric = AnalyticsMetric.RATING
    private var order = AnalyticsOrder.LOWEST
    private var chart = "radar"
    private var target = 80
    private var onlyBelow = false
    private var visibleCount = 20
    private var chartEntries = emptyList<AnalyticsEntry>()
    private val teacher: Boolean
        get() = RoleUtils.isTeacherOrHead(requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
            .getString("user_role", "student") ?: "student")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentModernAnalyticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        savedInstanceState?.let {
            metric = AnalyticsMetric.entries.getOrElse(it.getInt("metric")) { AnalyticsMetric.RATING }
            order = AnalyticsOrder.entries.getOrElse(it.getInt("order")) { AnalyticsOrder.LOWEST }
            chart = it.getString("chart", "radar")
            target = it.getInt("target", 80).coerceIn(50, 100)
            onlyBelow = it.getBoolean("below")
            subjectId = it.getInt("subject", -1)
            groupId = it.getInt("group", -1)
            selectedId = it.getInt("selected", -1)
        }
        repository = StudentRepositoryHTTPS(requireContext(), StudentDatabase.getInstance(requireContext()).studentDao())
        binding.tvTitle.setText(if (teacher) R.string.analytics_teacher_title else R.string.analytics_title)
        binding.tvSubtitle.setText(if (teacher) R.string.analytics_teacher_subtitle else R.string.analytics_subtitle)
        binding.teacherFilters.isVisible = teacher
        binding.swipeRefresh.setOnRefreshListener { loadInitial() }
        binding.btnRetry.setOnClickListener { loadInitial() }
        binding.metricGroup.check(if (metric == AnalyticsMetric.RATING) R.id.btnRating else R.id.btnAttendance)
        binding.metricGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                metric = if (id == R.id.btnRating) AnalyticsMetric.RATING else AnalyticsMetric.ATTENDANCE
                visibleCount = 20
                render()
            }
        }
        binding.viewGroup.check(when (chart) { "bars" -> R.id.btnBars; "compare" -> R.id.btnCompare; else -> R.id.btnRadar })
        binding.viewGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                chart = when (id) { R.id.btnBars -> "bars"; R.id.btnCompare -> "compare"; else -> "radar" }
                render()
            }
        }
        binding.seekTarget.progress = (target - 50) / 5
        binding.seekTarget.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                target = 50 + progress * 5
                render()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        binding.checkBelow.isChecked = onlyBelow
        binding.checkBelow.setOnCheckedChangeListener { _, checked -> onlyBelow = checked; visibleCount = 20; render() }
        binding.spinnerOrder.contentDescription = getString(R.string.analytics_sort_accessibility)
        binding.spinnerOrder.adapter = adapter(listOf(getString(R.string.analytics_order_low), getString(R.string.analytics_order_high), getString(R.string.analytics_order_name)))
        binding.spinnerOrder.setSelection(order.ordinal)
        binding.spinnerOrder.onItemSelectedListener = onSelected { index ->
            order = AnalyticsOrder.entries[index]; visibleCount = 20; render()
        }
        binding.btnMore.setOnClickListener { visibleCount += 20; renderRows(filtered()) }
        binding.radarChart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(entry: Entry?, highlight: Highlight?) {
                val item = chartEntries.getOrNull(highlight?.x?.roundToInt() ?: -1) ?: return
                selectedId = item.id
                renderFocus(item)
                renderRows(filtered())
            }
            override fun onNothingSelected() = Unit
        })
        loadInitial()
    }

    private fun loadInitial() {
        request?.cancel()
        request = viewLifecycleOwner.lifecycleScope.launch {
            loading()
            try {
                if (teacher) {
                    subjects = repository.getAnalyticsSubjects()
                    val subject = subjects.find { it.subject_id == subjectId } ?: subjects.firstOrNull()
                    subjectId = subject?.subject_id ?: -1
                    groupId = subject?.groups?.find { it.id == groupId }?.id ?: subject?.groups?.firstOrNull()?.id ?: -1
                    bindTeacherFilters()
                    if (subject == null || groupId < 0) {
                        showEmpty(if (subject == null) R.string.analytics_no_subjects else R.string.analytics_no_groups)
                    } else show(repository.getGroupAnalytics(groupId, subjectId))
                } else show(repository.getStudentAnalytics())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { showError() }
        }
    }

    private fun bindTeacherFilters() {
        val subjectIndex = subjects.indexOfFirst { it.subject_id == subjectId }.coerceAtLeast(0)
        binding.spinnerSubject.onItemSelectedListener = null
        binding.spinnerSubject.adapter = adapter(subjects.map { it.subject_name })
        binding.spinnerSubject.setSelection(subjectIndex)
        binding.spinnerSubject.onItemSelectedListener = onSelected { index ->
            val selected = subjects.getOrNull(index) ?: return@onSelected
            if (subjectId != selected.subject_id) {
                subjectId = selected.subject_id
                groupId = selected.groups.firstOrNull()?.id ?: -1
                bindTeacherFilters()
                loadGroup()
            }
        }
        val groups = subjects.getOrNull(subjectIndex)?.groups.orEmpty()
        binding.spinnerGroup.onItemSelectedListener = null
        binding.spinnerGroup.adapter = adapter(groups.map { it.name })
        binding.spinnerGroup.setSelection(groups.indexOfFirst { it.id == groupId }.coerceAtLeast(0))
        binding.spinnerGroup.onItemSelectedListener = onSelected { index ->
            val selected = groups.getOrNull(index) ?: return@onSelected
            if (groupId != selected.id) { groupId = selected.id; loadGroup() }
        }
    }

    private fun loadGroup() {
        request?.cancel()
        request = viewLifecycleOwner.lifecycleScope.launch {
            loading()
            if (groupId < 0) { showEmpty(R.string.analytics_no_groups); return@launch }
            try { show(repository.getGroupAnalytics(groupId, subjectId)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { showError() }
        }
    }

    private fun loading() {
        entries = emptyList()
        binding.progressLoading.isVisible = true
        binding.contentAnalytics.isVisible = false
        binding.errorPanel.isVisible = false
        binding.tvEmpty.isVisible = false
    }

    private fun finishLoading() {
        binding.progressLoading.isVisible = false
        binding.swipeRefresh.isRefreshing = false
    }

    private fun show(snapshot: AnalyticsSnapshot) {
        entries = snapshot.entries
        binding.errorPanel.isVisible = false
        binding.tvEmpty.isVisible = false
        finishLoading()
        if (entries.isEmpty()) { showEmpty(R.string.analytics_empty); return }
        binding.contentAnalytics.isVisible = true
        binding.tvSubtitle.text = if (teacher) {
            val subject = subjects.find { it.subject_id == subjectId }
            getString(R.string.analytics_teacher_period, subject?.subject_name.orEmpty(), subject?.groups?.find { it.id == groupId }?.name.orEmpty())
        } else snapshot.period
        render()
    }

    private fun showEmpty(message: Int) {
        finishLoading()
        binding.contentAnalytics.isVisible = false
        binding.tvEmpty.setText(message)
        binding.tvEmpty.isVisible = true
    }

    private fun showError() {
        if (_binding == null) return
        finishLoading()
        binding.contentAnalytics.isVisible = false
        binding.errorPanel.isVisible = true
    }

    private fun filtered(): List<AnalyticsEntry> = AnalyticsMath.sorted(entries, metric, order)
        .filter { !onlyBelow || it.value(metric)?.let { value -> value < target } == true }

    private fun render() {
        if (_binding == null) return
        val measured = entries.mapNotNull { it.value(metric) }
        val reached = measured.count { it >= target }
        binding.tvAverage.text = AnalyticsMath.format(AnalyticsMath.average(entries, metric))
        binding.tvMeasured.text = "${measured.size} / ${entries.size}"
        binding.tvReached.text = "$reached / ${measured.size}"
        binding.tvTarget.text = getString(R.string.analytics_target, target)
        binding.tvGoalSummary.text = getString(R.string.analytics_goal_summary, measured.size - reached, entries.size - measured.size)
        val visible = filtered()
        val selected = visible.find { it.id == selectedId } ?: visible.firstOrNull()
        selectedId = selected?.id ?: -1
        binding.focusCard.isVisible = selected != null
        selected?.let(::renderFocus)
        renderChart(visible)
        renderRows(visible)
    }

    private fun renderChart(visible: List<AnalyticsEntry>) {
        chartEntries = visible.filter { it.value(metric) != null }.take(12)
        val showRadar = chart == "radar" && chartEntries.size >= 3
        binding.radarChart.isVisible = showRadar
        binding.tvChartHint.isVisible = chart == "radar"
        binding.tvChartHint.text = if (showRadar) getString(R.string.analytics_radar_hint) else getString(R.string.analytics_radar_fallback)
        binding.tvLegend.isVisible = chart == "compare"
        if (!showRadar) return
        val color = requireContext().getColor(if (metric == AnalyticsMetric.RATING) R.color.sib_blue_primary else R.color.sib_success)
        val valueSet = RadarDataSet(chartEntries.map { RadarEntry(it.value(metric)!!.toFloat()) }, "").apply {
            setColor(color); fillColor = color; fillAlpha = 45
            setDrawFilled(true); setDrawValues(false); lineWidth = 2f
        }
        val targetSet = RadarDataSet(chartEntries.map { RadarEntry(target.toFloat()) }, "").apply {
            setColor(requireContext().getColorFromAttr(R.attr.sib_text_secondary))
            setDrawValues(false); setDrawFilled(false); lineWidth = 1f; isHighlightEnabled = false
        }
        binding.radarChart.apply {
            description.isEnabled = false
            legend.isEnabled = false
            isRotationEnabled = false
            webColor = requireContext().getColorFromAttr(R.attr.sib_border_subtle)
            webColorInner = webColor
            xAxis.textColor = requireContext().getColorFromAttr(R.attr.sib_text_secondary)
            xAxis.textSize = 12f
            xAxis.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = (visible.indexOf(chartEntries.getOrNull(value.roundToInt())) + 1).toString()
            }
            yAxis.axisMinimum = 0f; yAxis.axisMaximum = 100f
            yAxis.setDrawLabels(false)
            yAxis.setLabelCount(5, true)
            data = RadarData(targetSet, valueSet)
            contentDescription = chartEntries.joinToString(". ") { "${it.name}: ${AnalyticsMath.format(it.value(metric))}" }
            invalidate()
        }
    }

    private fun renderFocus(item: AnalyticsEntry) {
        val value = item.value(metric)
        binding.tvSelectedName.text = item.name
        binding.tvSelectedValue.text = AnalyticsMath.format(value)
        binding.tvSelectedMetric.setText(if (metric == AnalyticsMetric.RATING) R.string.analytics_rating else R.string.analytics_attendance)
        binding.selectedRing.progress = value?.roundToInt() ?: 0
        binding.selectedRing.setIndicatorColor(requireContext().getColor(if (metric == AnalyticsMetric.RATING) R.color.sib_blue_primary else R.color.sib_success))
        val details = mutableListOf(getString(R.string.analytics_focus_values, AnalyticsMath.format(item.value(AnalyticsMetric.RATING)), AnalyticsMath.format(item.value(AnalyticsMetric.ATTENDANCE))))
        if (item.score != null && item.maximum != null && item.maximum > 0) details += getString(R.string.analytics_score, number(item.score), number(item.maximum))
        if (item.attended != null && item.sessions != null && item.sessions > 0) details += getString(R.string.analytics_sessions, item.attended, item.sessions)
        binding.tvSelectedDetails.text = details.joinToString("\n")
        binding.tvSelectedNote.text = when {
            value == null -> getString(R.string.analytics_no_value)
            value >= target -> getString(R.string.analytics_above, (value - target).roundToInt())
            else -> getString(R.string.analytics_below, (target - value).roundToInt())
        }
    }

    private fun renderRows(visible: List<AnalyticsEntry>) {
        binding.containerRows.removeAllViews()
        binding.tvEmpty.isVisible = visible.isEmpty() && entries.isNotEmpty()
        if (visible.isEmpty() && entries.isNotEmpty()) binding.tvEmpty.setText(R.string.analytics_filtered_empty)
        visible.take(visibleCount).forEachIndexed { index, item ->
            val row = ItemAnalyticsEntryBinding.inflate(layoutInflater, binding.containerRows, false)
            row.tvName.text = "${index + 1}. ${item.name}"
            val compare = chart == "compare"
            row.tvRating.isVisible = compare || metric == AnalyticsMetric.RATING
            row.barRating.isVisible = row.tvRating.isVisible
            row.tvAttendance.isVisible = compare || metric == AnalyticsMetric.ATTENDANCE
            row.barAttendance.isVisible = row.tvAttendance.isVisible
            row.tvRating.text = getString(R.string.analytics_metric_value, getString(R.string.analytics_rating), AnalyticsMath.format(item.value(AnalyticsMetric.RATING)))
            row.tvAttendance.text = getString(R.string.analytics_metric_value, getString(R.string.analytics_attendance), AnalyticsMath.format(item.value(AnalyticsMetric.ATTENDANCE)))
            row.barRating.value = item.value(AnalyticsMetric.RATING); row.barRating.target = target
            row.barAttendance.value = item.value(AnalyticsMetric.ATTENDANCE); row.barAttendance.target = target
            row.barAttendance.barColor = requireContext().getColor(R.color.sib_success)
            row.root.strokeColor = requireContext().getColorFromAttr(R.attr.sib_border_subtle)
            row.root.strokeWidth = resources.displayMetrics.density.roundToInt()
            row.root.isSelected = item.id == selectedId
            if (row.root.isSelected) row.root.strokeColor = requireContext().getColor(R.color.sib_blue_primary)
            row.root.contentDescription = getString(R.string.analytics_entry_accessibility, item.name, AnalyticsMath.format(item.value(AnalyticsMetric.RATING)), AnalyticsMath.format(item.value(AnalyticsMetric.ATTENDANCE)))
            row.root.setOnClickListener {
                selectedId = item.id
                render()
                binding.focusCard.announceForAccessibility("${item.name}. ${AnalyticsMath.format(item.value(metric))}")
            }
            binding.containerRows.addView(row.root)
        }
        binding.btnMore.isVisible = visible.size > visibleCount
    }

    private fun number(value: Double): String = java.text.DecimalFormat("0.##").format(value)
    private fun adapter(values: List<String>) = ArrayAdapter(requireContext(), R.layout.item_spinner_selected, values).apply {
        setDropDownViewResource(R.layout.dropdown_item)
    }
    private fun onSelected(action: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = action(position)
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("metric", metric.ordinal); outState.putInt("order", order.ordinal)
        outState.putString("chart", chart); outState.putInt("target", target); outState.putBoolean("below", onlyBelow)
        outState.putInt("subject", subjectId); outState.putInt("group", groupId); outState.putInt("selected", selectedId)
    }

    override fun onDestroyView() {
        request?.cancel()
        binding.radarChart.setOnChartValueSelectedListener(null)
        super.onDestroyView()
        _binding = null
    }
}
