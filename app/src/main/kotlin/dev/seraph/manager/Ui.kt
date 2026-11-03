package dev.seraph.manager

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The tabs' small pieces: headings and rows of their lists, a dialog of text, and times as people say them. */
object Ui {
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    /** A heading over a part of a list. */
    fun heading(context: Context, text: String) = TextView(context).apply {
        this.text = text
        setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(context, 16), 0, dp(context, 4))
    }

    /** A line of text in a list: why it is empty, or a note. */
    fun note(context: Context, text: String) = TextView(context).apply {
        this.text = text
        setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
        setTextIsSelectable(true)
        setPadding(0, dp(context, 8), 0, dp(context, 8))
    }

    /** A button of a row: borderless, as small as its text. */
    fun button(context: Context, text: String, click: () -> Unit) = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
        this.text = text
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(context, 10), paddingTop, dp(context, 10), paddingBottom)
        setOnClickListener { click() }
    }

    /**
     * A row of a list: its title, what it says under it, and its buttons, beside them ([below]: under them, for more
     * than two), with a line under the row.
     */
    fun row(context: Context, title: String, subtitle: String, buttons: List<Button>, below: Boolean = false): View {
        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                this.text = title
                setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
            })
            if (subtitle.isNotEmpty()) addView(TextView(context).apply {
                this.text = subtitle
                setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
            })
        }
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            buttons.forEach { addView(it) }
        }
        val row = LinearLayout(context).apply {
            orientation = if (below) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 8), 0, if (below) 0 else dp(context, 8))
            if (below) {
                addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            } else {
                addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(actions)
            }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(View(context).apply {
                val divider = context.obtainStyledAttributes(intArrayOf(android.R.attr.listDivider))
                background = divider.getDrawable(0)
                divider.recycle()
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        }
    }

    /**
     * A dialog of text (a log, a report): monospace and selectable, with its lines marked when [colored] (see
     * Logs.colored), and a button to copy it. What it gives back sets its text later (when it was read) and shows its
     * end, where a log's newest lines are.
     */
    fun textDialog(activity: Activity, title: String, text: String, colored: Boolean = false): (String) -> Unit {
        val pad = dp(activity, 20)
        var plain = text
        val view = TextView(activity).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val scroll = ScrollView(activity).apply { addView(view) }
        AlertDialog.Builder(activity).setTitle(title).setView(scroll).setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.copy) { _, _ ->
                activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title, plain))
            }
            .show()
        return { said ->
            plain = said
            view.text = if (colored) Logs.colored(activity, said) else said
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** A time, as "5 minutes ago" or "yesterday". */
    fun ago(time: Long): String = DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    /** A time, as 2026-11-03 10:20. */
    fun date(time: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(time))
}
