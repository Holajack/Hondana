package hondana.safety

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceGroup
import androidx.preference.TwoStatePreference

/**
 * Extensions bring their own settings, and some offer adult content there: a "Show NSFW" switch,
 * or "Erotica" in a content-rating list. Hondana has no NSFW switches, so when a source's
 * settings open, adult switches are set to their safe position and hidden, and adult choices are
 * taken out of lists (an "exclude" list keeps them excluded instead).
 */
object AdultSourceSettings {

    fun apply(group: PreferenceGroup, preferences: SharedPreferences) {
        for (index in group.preferenceCount - 1 downTo 0) {
            when (val preference = group.getPreference(index)) {
                is PreferenceGroup -> apply(preference, preferences)
                is TwoStatePreference -> applySwitch(preference, preferences)
                is MultiSelectListPreference -> applyMultiSelect(preference, preferences)
                is ListPreference -> applyList(preference, preferences)
            }
        }
    }

    private fun applySwitch(switch: TwoStatePreference, preferences: SharedPreferences) {
        val title = switch.title?.toString().orEmpty()
        val summary = switch.summary?.toString().orEmpty()
        if (listOf(title, summary, switch.key).none { AdultContentRules.isAdultSetting(it) }) return
        val safe = AdultContentRules.onKeepsAdultOut(title.ifBlank { summary })
        switch.isChecked = safe
        switch.key?.let { preferences.edit().putBoolean(it, safe).apply() }
        switch.isVisible = false
    }

    private fun applyMultiSelect(list: MultiSelectListPreference, preferences: SharedPreferences) {
        val entries = list.entries ?: return
        val values = list.entryValues ?: return
        val adult = adultChoices(entries, values)
        if (adult.isEmpty()) return
        val adultValues = adult.map { values[it].toString() }.toSet()
        val chosen = if (AdultContentRules.onKeepsAdultOut(list.title?.toString().orEmpty())) {
            list.values + adultValues
        } else {
            list.values - adultValues
        }
        val kept = entries.indices.filter { it !in adult }
        list.entries = kept.map { entries[it] }.toTypedArray()
        list.entryValues = kept.map { values[it] }.toTypedArray()
        if (kept.isEmpty()) list.isVisible = false
        if (chosen != list.values) {
            list.values = chosen
            list.key?.let { preferences.edit().putStringSet(it, chosen).apply() }
        }
    }

    private fun applyList(list: ListPreference, preferences: SharedPreferences) {
        val entries = list.entries ?: return
        val values = list.entryValues ?: return
        val adult = adultChoices(entries, values)
        if (adult.isEmpty()) return
        val kept = entries.indices.filter { it !in adult }
        if (kept.isEmpty()) {
            list.isVisible = false
            return
        }
        val current = list.value
        list.entries = kept.map { entries[it] }.toTypedArray()
        list.entryValues = kept.map { values[it] }.toTypedArray()
        if (current != null && adult.any { values[it].toString() == current }) {
            val fallback = kept.firstOrNull { AdultContentRules.isSafeChoice(entries[it].toString()) } ?: kept.first()
            val value = values[fallback].toString()
            list.value = value
            list.key?.let { preferences.edit().putString(it, value).apply() }
        }
    }

    private fun adultChoices(entries: Array<CharSequence>, values: Array<CharSequence>): Set<Int> =
        entries.indices
            .filter { it < values.size }
            .filter { AdultContentRules.isAdultChoice(entries[it].toString()) || AdultContentRules.isAdultChoice(values[it].toString()) }
            .toSet()
}
