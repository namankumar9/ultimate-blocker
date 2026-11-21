package neth.iecal.curbox.utils

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppGroupAutostartConfig
import neth.iecal.curbox.data.models.AutostartMode
import java.util.Calendar

/**
 * Lets the user pick when a group switches itself back on. Returns the chosen config through
 * [onSave]; the caller arms it and saves it through the usual gated path.
 */
object AppGroupAutostartDialog {

    // Shown Monday first; the stored value runs Sunday = 0 through Saturday = 6.
    private val DAY_VALUES = intArrayOf(1, 2, 3, 4, 5, 6, 0)

    fun show(fragment: Fragment, title: String, config: AppGroupAutostartConfig, onSave: (AppGroupAutostartConfig) -> Unit) {
        val context = fragment.requireContext()
        val content = fragment.layoutInflater.inflate(R.layout.dialog_app_group_autostart, null)
        val modeGroup = content.findViewById<RadioGroup>(R.id.group_autostart_mode)
        val sectionOneTime = content.findViewById<View>(R.id.section_one_time)
        val sectionTimeOfDay = content.findViewById<View>(R.id.section_time_of_day)
        val btnOneTime = content.findViewById<Button>(R.id.btn_one_time_datetime)
        val btnTimeOfDay = content.findViewById<Button>(R.id.btn_time_of_day_time)
        val chipsDays = content.findViewById<ChipGroup>(R.id.chips_days)

        // One Time starts an hour ahead when the group has none set yet.
        var oneTimeMs = if (config.oneTimeAtMs > 0) config.oneTimeAtMs
            else System.currentTimeMillis() + 60 * 60_000L
        var timeOfDayMinutes = config.timeOfDayMinutes.coerceIn(0, 1439)
        // A group with no days yet opens with every day ticked, like the toggle screenshot.
        val selectedDays = config.days.toMutableSet()
        if (selectedDays.isEmpty()) selectedDays.addAll(0..6)

        val dayLabels = context.resources.getStringArray(R.array.autostart_day_labels)
        DAY_VALUES.forEachIndexed { index, dayValue ->
            // Filter chips show a checkmark and a tinted fill when a day is picked.
            (fragment.layoutInflater.inflate(R.layout.item_autostart_day_chip, chipsDays, false) as Chip).apply {
                text = dayLabels[index]
                isChecked = dayValue in selectedDays
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedDays.add(dayValue) else selectedDays.remove(dayValue)
                }
                chipsDays.addView(this)
            }
        }

        fun refreshButtons() {
            btnOneTime.text = context.getString(
                R.string.autostart_at,
                DateFormat.getMediumDateFormat(context).format(oneTimeMs),
                DateFormat.getTimeFormat(context).format(oneTimeMs)
            )
            btnTimeOfDay.text = DateFormat.getTimeFormat(context).format(minutesToMillis(timeOfDayMinutes))
        }

        fun refreshSections() {
            sectionOneTime.visibility =
                if (modeGroup.checkedRadioButtonId == R.id.autostart_one_time) View.VISIBLE else View.GONE
            sectionTimeOfDay.visibility =
                if (modeGroup.checkedRadioButtonId == R.id.autostart_time_of_day) View.VISIBLE else View.GONE
        }

        modeGroup.check(
            when (config.mode) {
                AutostartMode.OneTime -> R.id.autostart_one_time
                AutostartMode.TimeOfDay -> R.id.autostart_time_of_day
                AutostartMode.None -> R.id.autostart_none
            }
        )
        refreshButtons()
        refreshSections()
        modeGroup.setOnCheckedChangeListener { _, _ -> refreshSections() }

        btnOneTime.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = oneTimeMs }
            DatePickerDialog(context, { _, year, month, day ->
                TimePickerDialog(context, { _, hour, minute ->
                    oneTimeMs = Calendar.getInstance().apply {
                        set(year, month, day, hour, minute, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    refreshButtons()
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE),
                    DateFormat.is24HourFormat(context)).show()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
                .apply { datePicker.minDate = System.currentTimeMillis() }
                .show()
        }

        btnTimeOfDay.setOnClickListener {
            TimePickerDialog(context, { _, hour, minute ->
                timeOfDayMinutes = hour * 60 + minute
                refreshButtons()
            }, timeOfDayMinutes / 60, timeOfDayMinutes % 60, DateFormat.is24HourFormat(context)).show()
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val result = when (modeGroup.checkedRadioButtonId) {
                    R.id.autostart_one_time -> {
                        if (oneTimeMs <= System.currentTimeMillis()) {
                            toast(context, R.string.autostart_one_time_past)
                            return@setOnClickListener
                        }
                        AppGroupAutostartConfig(mode = AutostartMode.OneTime, oneTimeAtMs = oneTimeMs)
                    }
                    R.id.autostart_time_of_day -> {
                        if (selectedDays.isEmpty()) {
                            toast(context, R.string.autostart_no_days)
                            return@setOnClickListener
                        }
                        AppGroupAutostartConfig(
                            mode = AutostartMode.TimeOfDay,
                            timeOfDayMinutes = timeOfDayMinutes,
                            days = selectedDays.sorted()
                        )
                    }
                    else -> AppGroupAutostartConfig(mode = AutostartMode.None)
                }
                onSave(result)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun minutesToMillis(minutes: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minutes / 60)
        set(Calendar.MINUTE, minutes % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun toast(context: Context, resId: Int) =
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
}
