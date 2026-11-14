package dev.nixup.manager

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SearchView
import android.widget.TextView
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The tabs' small pieces: cards of rows, headings over them, buttons, filters, the tabs at the bottom, search boxes over
 * the lists, a dialog of text (searchable), a dialog to pick from a long list, and times as people say them. Their
 * colors and shapes are the app's (res/values/styles.xml), in a light and a dark version.
 */
object Ui {
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    /** A heading over a part of a list (the card of its rows). */
    fun heading(context: Context, text: String) = TextView(context).apply {
        this.text = text
        setTextAppearance(R.style.TextAppearance_Nixup_Heading)
        setPadding(dp(context, 4), dp(context, 16), dp(context, 4), dp(context, 8))
    }

    /** A line of text in a list: why it is empty, or a note. */
    fun note(context: Context, text: String) = TextView(context).apply {
        this.text = text
        setTextAppearance(R.style.TextAppearance_Nixup_Secondary)
        setTextIsSelectable(true)
        setPadding(dp(context, 4), dp(context, 8), dp(context, 4), dp(context, 8))
    }

    /** A button: a row's (text alone), or with [style] a tab's main one (filled) or its others (tonal). */
    fun button(context: Context, text: String, style: Int = R.style.Widget_Nixup_Button_Plain, click: () -> Unit) =
        Button(context, null, 0, style).apply {
            this.text = text
            setOnClickListener { click() }
        }

    /** A filter (the Logs tab's): outlined, filled while it is the one picked (selected). */
    fun chip(context: Context, text: String, click: () -> Unit) = TextView(context, null, 0, R.style.Widget_Nixup_Chip).apply {
        this.text = text
        setOnClickListener { click() }
    }

    /** A card for rows (a part of a list), with a line between each two. */
    fun card(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        // A row's press stays inside the rounded corners.
        clipToOutline = true
        dividerDrawable = context.getDrawable(R.drawable.divider)
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerPadding = dp(context, 16)
    }

    /** A round mark of [color] (a color resource): how a service is, how the last day went, behind an event's sign. */
    fun circle(context: Context, color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(context.getColor(color))
    }

    /** The icon of an installed app, as the launcher shows it; null when it is not installed. */
    fun appIcon(context: Context, id: String): Drawable? = try {
        context.packageManager.getApplicationIcon(id)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * A row of a card: before it [icon] (an app's) or a [dot] of a color resource (how a service is); its title, what
     * it says under it, and its buttons, beside them ([below]: under them, for more than two).
     */
    fun row(context: Context, title: String, subtitle: String, buttons: List<Button>, below: Boolean = false, icon: Drawable? = null, dot: Int? = null): View {
        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                this.text = title
                setTextAppearance(R.style.TextAppearance_Nixup_Title)
            })
            if (subtitle.isNotEmpty()) addView(TextView(context).apply {
                this.text = subtitle
                setTextAppearance(R.style.TextAppearance_Nixup_Secondary)
            })
        }
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            buttons.forEach { addView(it) }
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Level with the title's first line, however many lines are under it.
            if (icon != null) addView(ImageView(context).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(context, 40), dp(context, 40)).apply {
                marginEnd = dp(context, 16)
                gravity = Gravity.TOP
            })
            if (dot != null) addView(View(context).apply { background = circle(context, dot) }, LinearLayout.LayoutParams(dp(context, 10), dp(context, 10)).apply {
                marginEnd = dp(context, 14)
                topMargin = dp(context, 7)
                gravity = Gravity.TOP
            })
            addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (!below) addView(actions)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // The buttons' own padding is the room at the end (and under them, below).
            setPadding(dp(context, 16), dp(context, 12), dp(context, if (buttons.isEmpty()) 16 else 4), dp(context, if (below && buttons.isNotEmpty()) 4 else 12))
            addView(top)
            if (below) addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    // --- The tabs at the bottom.

    /** A tab: its icon (in a pill while it is the one shown: selected), a count on it, and its name under it. */
    class Tab(val view: View, val name: String, private val badge: TextView) {
        /** The count on the tab ([count] news: updates, problems) in [color], a color resource; none at 0. */
        fun count(count: Int, color: Int, said: String) {
            badge.visibility = if (count > 0) View.VISIBLE else View.GONE
            badge.text = if (count > 99) "99+" else count.toString()
            badge.background = GradientDrawable().apply {
                cornerRadius = dp(badge.context, 8).toFloat()
                setColor(badge.context.getColor(color))
            }
            view.contentDescription = if (count > 0) said else name
        }
    }

    fun tab(context: Context, name: String, icon: Int): Tab {
        val badge = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            minWidth = dp(context, 16)
            setPadding(dp(context, 4), 0, dp(context, 4), 0)
            visibility = View.GONE
        }
        val pill = FrameLayout(context).apply {
            setBackgroundResource(R.drawable.bg_tab_indicator)
            addView(ImageView(context).apply {
                setImageResource(icon)
                imageTintList = context.getColorStateList(R.color.tab)
            }, FrameLayout.LayoutParams(dp(context, 24), dp(context, 24), Gravity.CENTER))
            addView(badge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(context, 16), Gravity.TOP or Gravity.END).apply {
                marginEnd = dp(context, 6)
            })
        }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(context, 10), 0, dp(context, 10))
            isClickable = true
            isFocusable = true
            contentDescription = name
            context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless)).let {
                background = it.getDrawable(0)
                it.recycle()
            }
            addView(pill, LinearLayout.LayoutParams(dp(context, 56), dp(context, 28)))
            addView(TextView(context).apply {
                text = name
                setTextAppearance(R.style.TextAppearance_Nixup_Tab)
                setTextColor(context.getColorStateList(R.color.tab))
                setPadding(0, dp(context, 4), 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        return Tab(view, name, badge)
    }

    // --- Searching.

    /** Text as a search compares it: lower case, without accents (ş is s, é is e), so either way of typing finds it. */
    private fun folded(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT)

    private val MARKS = Regex("\\p{Mn}+")

    /** The words of a search, as [folded] has them. */
    private fun words(query: String): List<String> = folded(query).split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Whether [texts] have every word of [query] in them, whatever their case and accents; an empty one finds all. */
    fun matches(query: String, vararg texts: String?): Boolean {
        val words = words(query)
        if (words.isEmpty()) return true
        val haystack = folded(texts.filterNotNull().joinToString("\n"))
        return words.all { it in haystack }
    }

    /**
     * A search box (one of a tab's, or a dialog's): [changed] gets what is in it as it is typed, and again when it is
     * emptied with its ✕; the keyboard goes when the search is sent. It is a rounded field, without the line Android
     * draws under its text.
     */
    // The line is the background of Android's own part of the box, and the text its own field: it names both, but does
    // not make them public.
    @SuppressLint("DiscouragedApi")
    fun onSearch(box: SearchView, changed: (String) -> Unit) {
        box.setIconifiedByDefault(false)
        if (box.background == null) box.setBackgroundResource(R.drawable.bg_search)
        box.findViewById<View>(box.resources.getIdentifier("search_plate", "id", "android"))?.background = null
        box.findViewById<TextView>(box.resources.getIdentifier("search_src_text", "id", "android"))?.textSize = 16f
        box.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(text: String): Boolean {
                changed(text.trim())
                return true
            }

            override fun onQueryTextSubmit(text: String): Boolean {
                box.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(box.windowToken, 0)
                box.clearFocus()
                return true
            }
        })
    }

    /** A search box made here, for a dialog, with [hint] in it until something is typed. */
    fun searchBox(context: Context, hint: String, changed: (String) -> Unit) = SearchView(context).apply {
        queryHint = hint
        onSearch(this, changed)
    }

    /** What a list says when a search found nothing in it. */
    fun noMatch(context: Context, query: String) = note(context, context.getString(R.string.search_no_match, query))

    /**
     * A dialog to pick some of [items] (each its key, then what it shows), with a search box over them: what is typed
     * keeps the matching ones in sight, and what was picked stays picked while it is out of sight. [then] gets the keys
     * picked, in the items' order.
     */
    fun pickMany(activity: Activity, title: String, hint: String, items: List<Pair<String, String>>, positive: String, then: (List<String>) -> Unit) {
        val picked = mutableSetOf<String>()
        var shown = items
        val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_multiple_choice, mutableListOf())
        val list = ListView(activity).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            this.adapter = adapter
            setOnItemClickListener { _, _, i, _ -> shown[i].first.let { key -> if (isItemChecked(i)) picked += key else picked -= key } }
        }
        val count = TextView(activity).apply { setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small) }
        fun refill(query: String) {
            shown = items.filter { matches(query, it.second, it.first) }
            adapter.clear()
            adapter.addAll(shown.map { it.second })
            shown.forEachIndexed { i, item -> list.setItemChecked(i, item.first in picked) }
            count.text = if (shown.isEmpty()) activity.getString(R.string.search_no_match, query) else ""
            count.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        }
        val pad = dp(activity, 16)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(searchBox(activity, hint) { refill(it) })
            addView(count)
            // As tall as it may be in the dialog, not the one line a list in a scrolling dialog gets.
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.resources.displayMetrics.heightPixels / 2))
        }
        refill("")
        AlertDialog.Builder(activity).setTitle(title).setView(box)
            .setPositiveButton(positive) { _, _ -> then(items.map { it.first }.filter { it in picked }) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * A dialog of text (a log, a report): monospace and selectable, with its lines marked when [colored] (see
     * Logs.colored), a search box that keeps only the lines with what is typed in them (marked where it is), and a
     * button to copy it. What it gives back sets its text later (when it was read) and shows its end, where a log's
     * newest lines are.
     */
    fun textDialog(activity: Activity, title: String, text: String, colored: Boolean = false): (String) -> Unit {
        val pad = dp(activity, 20)
        var plain = text
        var query = ""
        val view = TextView(activity).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val found = TextView(activity).apply {
            setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
            setPadding(pad, 0, pad, 0)
            visibility = View.GONE
        }
        // Some height of its own, so the dialog keeps its size while a search narrows the lines.
        val scroll = ScrollView(activity).apply {
            addView(view)
            minimumHeight = activity.resources.displayMetrics.heightPixels * 2 / 5
        }
        fun show() {
            if (query.isEmpty()) {
                found.visibility = View.GONE
                view.text = if (colored) Logs.colored(activity, plain) else plain
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                return
            }
            val lines = plain.lines()
            val kept = lines.filter { matches(query, it) }
            found.text = if (kept.isEmpty()) activity.getString(R.string.search_no_match, query)
            else activity.resources.getQuantityString(R.plurals.search_lines, kept.size, kept.size, lines.size)
            found.visibility = View.VISIBLE
            val joined = kept.joinToString("\n")
            view.text = marked(activity, if (colored) Logs.colored(activity, joined) else joined, query)
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(searchBox(activity, activity.getString(R.string.search_text)) { query = it; show() }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(pad, pad / 4, pad, pad / 4) })
            addView(found)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        AlertDialog.Builder(activity).setTitle(title).setView(box).setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.copy) { _, _ ->
                activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title, plain))
            }
            .show()
        return { said ->
            plain = said
            show()
        }
    }

    /** [text] with each word of [query] marked where it is (as typed, whatever its case). */
    private fun marked(context: Context, text: CharSequence, query: String): CharSequence {
        val out = SpannableStringBuilder(text)
        val color = context.getColor(R.color.search_match)
        for (word in query.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            Regex(Regex.escape(word), RegexOption.IGNORE_CASE).findAll(text).forEach {
                out.setSpan(BackgroundColorSpan(color), it.range.first, it.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }

    /** A time, as "5 minutes ago" or "yesterday". */
    fun ago(time: Long): String = DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    /** A time, as 2026-11-03 10:20. */
    fun date(time: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(time))
}
