package com.billbook.app.ui.insights

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billbook.app.analysis.CategoryTotal
import com.billbook.app.analysis.Insights
import com.billbook.app.analysis.InsightsData
import com.billbook.app.analysis.MonthFlow
import com.billbook.app.ui.theme.MoneyColors
import com.billbook.app.util.Money
import com.billbook.app.ui.components.OneTimeTip
import com.billbook.app.ui.components.Tips
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private val ShortMonth = DateTimeFormatter.ofPattern("MMM")
private val MonthYear = DateTimeFormatter.ofPattern("MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(onBack: () -> Unit, vm: InsightsViewModel = viewModel(factory = InsightsViewModel.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val rateMessage by vm.rateMessage.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Insights") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        val s = state
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val data = s.data
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Filters(
                    s = s,
                    profiles = profiles.map { it.id to it.name },
                    onPeriod = vm::setPeriod,
                    onProfile = vm::setProfile,
                    onCurrency = vm::setCurrency,
                    onMonths = vm::setMonths,
                    onShift = vm::shift,
                )
            }
            item {
                OneTimeTip(
                    Tips.INSIGHTS_PERIOD,
                    "Use ◀ ▶ to move through months, or Choose… for one month or a range. " +
                        "Tap a category to see where the money went.",
                )
            }
            if (data == null || data.isEmpty) {
                item {
                    Text(
                        "No entries between ${s.from.format(MonthYear)} and ${s.to.format(MonthYear)}.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 32.dp),
                    )
                }
                return@LazyColumn
            }
            item { Summary(data, s.comparedTo) }
            if (data.unconverted.isNotEmpty()) {
                item {
                    Unconverted(data, vm.canDownloadRates, rateMessage, vm::downloadRates)
                }
            }
            if (data.months.size > 1) {
                item { SavingsGrowth(data) }
                item { MonthlyFlow(data) }
            }
            if (data.expenseCategories.isNotEmpty()) {
                item { CategoryCard("Spending by category", data.expenseCategories, data.currency, donut = true, moreIsBad = true) }
            }
            if (data.incomeCategories.isNotEmpty()) {
                item { CategoryCard("Income by category", data.incomeCategories, data.currency, donut = false, moreIsBad = false) }
            }
        }
    }
}

@Composable
private fun Filters(
    s: InsightsState,
    profiles: List<Pair<Long, String>>,
    onPeriod: (Period) -> Unit,
    onProfile: (Long?) -> Unit,
    onCurrency: (String) -> Unit,
    onMonths: (YearMonth, YearMonth) -> Unit,
    onShift: (Int) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        MonthRangeDialog(
            initialFrom = s.from,
            initialTo = s.to,
            onPick = { a, b -> picking = false; onMonths(a, b) },
            onDismiss = { picking = false },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ChipRow {
            Period.entries.forEach { p ->
                FilterChip(
                    selected = s.filter.period == p,
                    onClick = { if (p == Period.CUSTOM) picking = true else onPeriod(p) },
                    label = { Text(p.label) },
                )
            }
        }
        if (profiles.size > 1) {
            ChipRow {
                FilterChip(selected = s.filter.profileId == null, onClick = { onProfile(null) }, label = { Text("All profiles") })
                profiles.forEach { (id, name) ->
                    FilterChip(selected = s.filter.profileId == id, onClick = { onProfile(id) }, label = { Text(name) })
                }
            }
        }
        if (s.currencies.size > 1) {
            ChipRow {
                Text("Show in", style = MaterialTheme.typography.labelLarge)
                s.currencies.forEach { code ->
                    FilterChip(selected = s.filter.currency == code, onClick = { onCurrency(code) }, label = { Text(code) })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val stepping = s.filter.period != Period.ALL_TIME
            if (stepping) {
                IconButton(onClick = { onShift(-1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Earlier months")
                }
            }
            Text(
                rangeLabel(s.from, s.to),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).clickable { picking = true },
                textAlign = if (stepping) TextAlign.Center else TextAlign.Start,
            )
            if (stepping) {
                IconButton(onClick = { onShift(1) }, enabled = s.to < YearMonth.now()) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Later months")
                }
            }
        }
    }
}

private fun rangeLabel(from: YearMonth, to: YearMonth) = when {
    from == to -> from.format(FullMonthYear)
    from.year == to.year -> "${from.format(ShortMonth)} – ${to.format(MonthYear)}"
    else -> "${from.format(MonthYear)} – ${to.format(MonthYear)}"
}

private val FullMonthYear = DateTimeFormatter.ofPattern("MMMM yyyy")

/**
 * Month grid for one year at a time. The first tap picks a single month; a second tap on a later
 * month extends it into a range (tap an earlier month to start again).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonthRangeDialog(
    initialFrom: YearMonth,
    initialTo: YearMonth,
    onPick: (YearMonth, YearMonth) -> Unit,
    onDismiss: () -> Unit,
) {
    var from by remember { mutableStateOf(initialFrom) }
    var to by remember { mutableStateOf(initialTo) }
    var year by remember { mutableStateOf(initialTo.year) }
    // After a range is shown, the next tap starts a new selection.
    var extending by remember { mutableStateOf(initialFrom == initialTo) }
    val now = YearMonth.now()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose months") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Tap a month, then a later month for a range.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { year-- }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous year")
                    }
                    Text("$year", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = { year++ }, enabled = year < now.year) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next year")
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    maxItemsInEachRow = 4,
                ) {
                    for (m in 1..12) {
                        val month = YearMonth.of(year, m)
                        FilterChip(
                            selected = month in from..to,
                            enabled = month <= now,
                            onClick = {
                                if (extending && month > from) {
                                    to = month
                                    extending = false
                                } else {
                                    from = month
                                    to = month
                                    extending = true
                                }
                            },
                            label = { Text(month.format(ShortMonth)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text(rangeLabel(from, to), style = MaterialTheme.typography.titleSmall)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(from, to) }) { Text("Show") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun InsightCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Summary(d: InsightsData, comparedTo: String?) {
    InsightCard("Overview") {
        Row(Modifier.fillMaxWidth()) {
            Figure("Income", d.income, d.currency, MoneyColors.income, Insights.change(d.income, d.previousIncome), false, Modifier.weight(1f))
            Figure("Spent", d.expense, d.currency, MoneyColors.expense, Insights.change(d.expense, d.previousExpense), true, Modifier.weight(1f))
            Figure(
                if (d.net >= 0) "Saved" else "Overspent", abs(d.net), d.currency,
                if (d.net >= 0) MoneyColors.income else MoneyColors.expense, null, false, Modifier.weight(1f),
            )
        }
        if (d.invested > 0) {
            Text(
                if (d.net > 0) {
                    "Of what you saved, ${Money.format(d.invested, d.currency)} went into investments."
                } else {
                    "${Money.format(d.invested, d.currency)} went into investments."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        val rate = d.savingsRate
        Text(
            buildString {
                if (rate != null) append("You kept ${(rate * 100).roundToInt()}% of your income. ")
                if (d.months.size > 1) append("Average spending ${Money.format(d.averageMonthlyExpense, d.currency)} a month.")
            }.trim(),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (comparedTo != null && (d.previousIncome ?: 0) + (d.previousExpense ?: 0) > 0) {
            Text("▲▼ compared with $comparedTo", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Figure(label: String, cents: Long, currency: String, color: Color, change: Float?, moreIsBad: Boolean, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            Money.format(cents),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(currency, style = MaterialTheme.typography.labelSmall)
        if (change != null) ChangeLabel(change, moreIsBad)
    }
}

/** "▲ 12%" with an arrow and words, so the direction never depends on colour alone. */
@Composable
private fun ChangeLabel(change: Float, moreIsBad: Boolean) {
    val pct = (change * 100).roundToInt()
    if (pct == 0) {
        Text("same", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val worse = (pct > 0) == moreIsBad
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (pct > 0) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
            contentDescription = if (pct > 0) "up" else "down",
            tint = if (worse) MoneyColors.expense else MoneyColors.income,
            modifier = Modifier.size(18.dp),
        )
        Text(
            "${abs(pct).coerceAtMost(999)}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Unconverted(d: InsightsData, canDownload: Boolean, message: String?, onDownload: () -> Unit) {
    val currencies = d.unconverted.map { it.currency }.distinct().joinToString(", ")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${d.unconverted.size} ${if (d.unconverted.size == 1) "entry" else "entries"} in $currencies " +
                    "not included: no exchange rate to ${d.currency} yet.",
                style = MaterialTheme.typography.bodySmall,
            )
            when {
                message != null -> Text(message, style = MaterialTheme.typography.bodySmall)
                canDownload -> TextButton(onClick = onDownload) { Text("Download exchange rates") }
                else -> Text(
                    "Turn on “Download exchange rates” in Settings, or enter a rate when creating a statement.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SavingsGrowth(d: InsightsData) {
    var selected by rememberSaveable(d.months.size) { mutableStateOf<Int?>(null) }
    val color = savingsColor()
    InsightCard("Savings growth") {
        val i = selected ?: d.months.lastIndex
        val total = d.savingsGrowth[i]
        Text(
            "${if (total >= 0) "Saved" else "Short by"} ${Money.format(abs(total), d.currency)} " +
                "from ${d.months.first().month.format(MonthYear)} to end of ${d.months[i].month.format(MonthYear)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        SavingsLine(d.savingsGrowth, color, selected, onSelect = { selected = if (selected == it) null else it })
        MonthAxis(d.months.map { it.month })
        Text(
            "Income minus spending, added up month by month. Money you invested counts as saved. " +
                "Tap the chart to see a month.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MonthlyFlow(d: InsightsData) {
    var selected by rememberSaveable(d.months.size) { mutableStateOf<Int?>(null) }
    InsightCard("Income and expenses per month") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem("Income", MoneyColors.income)
            LegendItem("Spent", MoneyColors.expense)
        }
        MonthBars(
            income = d.months.map { it.income },
            expense = d.months.map { it.expense },
            incomeColor = MoneyColors.income,
            expenseColor = MoneyColors.expense,
            selected = selected,
            onSelect = { selected = if (selected == it) null else it },
        )
        MonthAxis(d.months.map { it.month })
        val m: MonthFlow? = selected?.let { d.months[it] }
        if (m != null) {
            Text(
                "${m.month.format(MonthYear)}: in ${Money.format(m.income, d.currency)}, " +
                    "spent ${Money.format(m.expense, d.currency)}, " +
                    "${if (m.net >= 0) "saved" else "overspent"} ${Money.format(abs(m.net), d.currency)}" +
                    if (m.invested > 0) " (invested ${Money.format(m.invested, d.currency)})" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            val best = d.months.maxBy { it.net }
            val worst = d.months.maxBy { it.expense }
            Text(
                "Best month: ${best.month.format(MonthYear)} (saved ${Money.format(best.net, d.currency)}). " +
                    "Highest spending: ${worst.month.format(MonthYear)} (${Money.format(worst.expense, d.currency)}).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Month names under a chart; with many months only every few are labelled. */
@Composable
private fun MonthAxis(months: List<YearMonth>) {
    val every = when {
        months.size <= 7 -> 1
        months.size <= 14 -> 2
        else -> (months.size + 5) / 6
    }
    Row(Modifier.fillMaxWidth()) {
        months.forEachIndexed { i, m ->
            Text(
                if (i % every == 0) m.format(if (m.monthValue == 1 || i == 0) MonthYearShort else ShortMonth) else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private val MonthYearShort = DateTimeFormatter.ofPattern("MMM yy")

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun CategoryCard(title: String, categories: List<CategoryTotal>, currency: String, donut: Boolean, moreIsBad: Boolean) {
    val palette = seriesColors()
    val other = otherColor()
    fun colorAt(i: Int) = if (i < COLOURED_CATEGORIES) palette[i] else other
    var expanded by rememberSaveable(title) { mutableStateOf<String?>(null) }
    val total = categories.sumOf { it.cents }

    InsightCard(title) {
        if (donut) {
            val slices = categories.take(COLOURED_CATEGORIES).mapIndexed { i, c -> c.share to colorAt(i) } +
                listOfNotNull(
                    categories.drop(COLOURED_CATEGORIES).sumOf { it.share.toDouble() }.toFloat()
                        .takeIf { it > 0f }?.let { it to other }
                )
            DonutChart(slices, size = 168.dp, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Money.format(total), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(currency, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        categories.forEachIndexed { i, c ->
            if (i > 0) HorizontalDivider()
            CategoryRow(
                c, currency, colorAt(i), moreIsBad,
                expanded = expanded == c.category,
                onClick = { expanded = if (expanded == c.category) null else c.category },
            )
        }
        Text(
            "Tap a category to see where the money went.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CategoryRow(
    c: CategoryTotal,
    currency: String,
    color: Color,
    moreIsBad: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(
                c.category,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("${(c.share * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(Money.format(c.cents, currency), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(18.dp))
            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                Box(Modifier.fillMaxWidth(c.share.coerceIn(0.01f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "${c.count} ${if (c.count == 1) "entry" else "entries"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Insights.change(c.cents, c.previousCents)?.let { ChangeLabel(it, moreIsBad) }
        }
        if (expanded) {
            Column(Modifier.padding(start = 18.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                c.topPayees.forEach { (party, cents) ->
                    Row {
                        Text(party, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(Money.format(cents, currency), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (c.previousCents != null) {
                    Text(
                        "Period before: ${Money.format(c.previousCents, currency)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
