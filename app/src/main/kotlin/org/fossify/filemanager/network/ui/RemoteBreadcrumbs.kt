package org.fossify.filemanager.network.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.TextViewCompat
import org.fossify.commons.R as CommonsR
import org.fossify.commons.databinding.ItemBreadcrumbBinding
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setDrawablesRelativeWithIntrinsicBounds
import org.fossify.filemanager.network.core.Paths
import org.fossify.filemanager.network.core.RemotePath

/**
 * Breadcrumbs for paths on a network drive (`NAS › Documents › 2024`). The regular breadcrumbs of the app only
 * understand paths on the phone. Looks like them, but is much simpler.
 */
class RemoteBreadcrumbs(context: Context, attrs: AttributeSet) : HorizontalScrollView(context, attrs) {
    interface Listener {
        /** A crumb was tapped; [path] is the `remote://` path it stands for. */
        fun remoteBreadcrumbClicked(path: String)
    }

    var listener: Listener? = null

    private val inflater = LayoutInflater.from(context)
    private val itemsLayout = LinearLayout(context)
    private var textColor = context.getProperTextColor()
    private var accentColor = context.getProperPrimaryColor()
    private var fontSize = resources.getDimension(CommonsR.dimen.bigger_text_size)
    private var lastPath = ""
    private var lastDriveName = ""

    private val textColorStateList: ColorStateList
        get() = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_activated), intArrayOf()),
            intArrayOf(accentColor, textColor),
        )

    init {
        isHorizontalScrollBarEnabled = false
        itemsLayout.orientation = LinearLayout.HORIZONTAL
        itemsLayout.setPaddingRelative(paddingStart, paddingTop, paddingEnd, paddingBottom)
        setPaddingRelative(0, 0, 0, 0)
        addView(itemsLayout, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    fun setPath(driveName: String, remotePath: String) {
        lastPath = remotePath
        lastDriveName = driveName
        itemsLayout.removeAllViews()

        val chain = RemotePath.chain(remotePath)
        chain.forEachIndexed { index, path ->
            val label = if (index == 0) driveName else Paths.name(RemotePath.innerPath(path))
            addCrumb(label, path, index, isLast = index == chain.lastIndex)
        }
        post { fullScroll(FOCUS_RIGHT) }
    }

    private fun addCrumb(label: String, path: String, index: Int, isLast: Boolean) {
        val crumb = ItemBreadcrumbBinding.inflate(inflater, itemsLayout, false)
        crumb.breadcrumbText.apply {
            isActivated = isLast && index != 0
            text = label
            setTextColor(textColorStateList)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
            if (index > 0) {
                setDrawablesRelativeWithIntrinsicBounds(start = AppCompatResources.getDrawable(context, CommonsR.drawable.ic_chevron_right_vector))
                TextViewCompat.setCompoundDrawableTintList(this, textColorStateList)
            } else {
                val padding = resources.getDimensionPixelSize(CommonsR.dimen.normal_margin)
                setPadding(padding, paddingTop, padding, paddingBottom)
            }
            setOnClickListener { if (!isLast || index == 0) listener?.remoteBreadcrumbClicked(path) }
        }
        itemsLayout.addView(crumb.root)
    }

    fun updateColor(color: Int) {
        textColor = color
        accentColor = context.getProperPrimaryColor()
        if (lastPath.isNotEmpty()) setPath(lastDriveName, lastPath)
    }

    fun updateFontSize(size: Float) {
        fontSize = size
        if (lastPath.isNotEmpty()) setPath(lastDriveName, lastPath)
    }
}
