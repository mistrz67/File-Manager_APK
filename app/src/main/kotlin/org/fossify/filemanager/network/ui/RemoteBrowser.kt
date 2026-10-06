package org.fossify.filemanager.network.ui

import android.content.Context
import org.fossify.filemanager.models.ListItem
import org.fossify.filemanager.network.core.RemotePath
import org.fossify.filemanager.network.data.networkManager

/** Lists folders of network drives as the items the file list shows. */
object RemoteBrowser {
    /** Blocking; throws network errors for the caller to report. */
    fun listItems(context: Context, path: String, showHidden: Boolean): ArrayList<ListItem> {
        val entries = context.networkManager.files.list(path)
        val items = ArrayList<ListItem>(entries.size)
        for (entry in entries) {
            if (!showHidden && entry.name.startsWith(".")) continue
            items.add(
                ListItem(
                    mPath = RemotePath.child(path, entry.name),
                    mName = entry.name,
                    mIsDirectory = entry.isDirectory,
                    mChildren = 0,
                    mSize = entry.size,
                    mModified = entry.modified,
                    isSectionTitle = false,
                    isGridTypeDivider = false,
                )
            )
        }
        return items
    }
}
