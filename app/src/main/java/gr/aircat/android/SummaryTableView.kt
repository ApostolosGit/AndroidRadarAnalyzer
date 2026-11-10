package gr.aircat.android

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Mobile stacked counterpart of desktop AIRCAT500 error/alert Summary tabs. */
class SummaryTableView(context: Context) : ScrollView(context) {
    private val navy = Color.rgb(20, 76, 122)
    private val ink = Color.rgb(23, 39, 55)
    private val surface = Color.rgb(241, 246, 251)
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(14))
    }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + .5f).toInt()
    private fun cell(text: String, weight: Float, numeric: Boolean = false, heading: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            textSize = if (heading) 11f else 11.5f
            setPadding(dp(4), dp(10), dp(4), dp(10))
            setTextColor(if (heading) Color.WHITE else ink)
            if (numeric) typeface = Typeface.MONOSPACE
            if (heading) setTypeface(typeface, Typeface.BOLD)
            textAlignment = if (numeric) TEXT_ALIGNMENT_TEXT_END else TEXT_ALIGNMENT_VIEW_START
            layoutParams = LinearLayout.LayoutParams(0, -2, weight)
        }
    private fun headerRow(vararg pairs: Pair<String, Float>) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(navy) }
        pairs.forEach { (name, weight) -> row.addView(cell(name, weight, heading = true, numeric = name != "Μέτρηση" && name != "Alert")) }
        column.addView(row)
    }
    private fun row(vararg pairs: Pair<String, Float>, idx: Int) {
        val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(if (idx % 2 == 0) Color.WHITE else surface) }
        pairs.forEachIndexed { i, pair -> line.addView(cell(pair.first, pair.second, numeric = i > 0)) }
        column.addView(line)
    }
    private fun banner(text: String) {
        column.addView(TextView(context).apply {
            this.text = text; textSize = 14f; setTextColor(navy)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(5), dp(10), dp(5), dp(13))
        })
    }
    private fun note(text: String) {
        column.addView(TextView(context).apply {
            this.text = text; textSize = 10f; setTextColor(Color.rgb(80, 99, 114))
            setPadding(dp(5), dp(12), dp(5), dp(4))
        })
    }
    init { isFillViewport = true; setBackgroundColor(surface); addView(column) }
    fun setErrorRows(rows: List<ErrorSummaryRow>) {
        column.removeAllViews()
        banner("Πίνακες σφαλμάτων  •  Σύνοψη")
        headerRow("Μέτρηση" to 5f, "Count" to 1f)
        rows.forEachIndexed { i, item -> row(item.name to 5f, item.value to 1f, idx = i) }
        note("Invalid North cycles: διαγνωστικό πλήθος, όχι πρόσθετα σφάλματα.\n" +
            "— : μη υπολογιζόμενος μετρητής στον τρέχοντα offline decoder.")
    }
    fun setAlertRows(rows: List<AlertSummaryRow>) {
        column.removeAllViews()
        banner("AIRCAT500 Alerts  •  Σύνοψη")
        headerRow("Alert" to 4.2f, "PSR" to 1.05f, "MSSR" to 1.2f, "Other" to 1.2f, "Σύνολο" to 1.35f)
        rows.forEachIndexed { i, item ->
            row(item.name to 4.2f, item.psr.toString() to 1.05f,
                item.mssr.toString() to 1.2f, item.other.toString() to 1.2f,
                item.total.toString() to 1.35f, idx = i)
        }
        if (rows.isEmpty()) note("Δεν βρέθηκαν alerts στην καταγραφή.")
        else note("PSR/MSSR/Other: κατηγοριοποίηση από τα αποκωδικοποιημένα AIRCAT tracks.\n" +
            "Cancellations χωρίς προηγούμενη αναγνωρίσιμη πηγή ταξινομούνται ως Other.")
    }
}
