package org.fossify.filemanager.network.ui

import android.app.Activity
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.models.RadioItem
import org.fossify.filemanager.R
import org.fossify.filemanager.network.core.ConflictPolicy

/** Lets the user decide what happens with items that already exist at the destination. */
class ConflictDialog(activity: Activity, onChosen: (ConflictPolicy) -> Unit) {
    init {
        val items = arrayListOf(
            RadioItem(ConflictPolicy.KEEP_BOTH.ordinal, activity.getString(R.string.conflict_keep_both)),
            RadioItem(ConflictPolicy.OVERWRITE.ordinal, activity.getString(R.string.conflict_overwrite)),
            RadioItem(ConflictPolicy.SKIP.ordinal, activity.getString(R.string.conflict_skip)),
        )
        RadioGroupDialog(activity, items, ConflictPolicy.KEEP_BOTH.ordinal, R.string.conflict_title) {
            onChosen(ConflictPolicy.values()[it as Int])
        }
    }
}
