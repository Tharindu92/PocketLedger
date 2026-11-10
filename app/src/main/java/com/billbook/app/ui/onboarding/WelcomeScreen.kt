package com.billbook.app.ui.onboarding

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.billbook.app.BillBookApp
import com.billbook.app.data.Categories
import com.billbook.app.ui.components.NotificationDisclosureText
import com.billbook.app.ui.components.SuggestField
import kotlinx.coroutines.launch

private data class Page(val icon: ImageVector, val title: String, val body: String)

private val INTRO = listOf(
    Page(
        Icons.Default.CameraAlt,
        "Your bills, in one place",
        "Scan a bill and Pocket Ledger reads the amount, date, shop and bill number. Or add an entry by hand, " +
            "or import a bank statement (CSV or PDF) and choose which bookings to keep.",
    ),
    Page(
        Icons.Default.Group,
        "Profiles and categories",
        "Keep personal money and a club or business apart with profiles. Categories are guessed for you; " +
            "pick one yourself and tap Remember, and it's filled in next time.",
    ),
    Page(
        Icons.Default.Insights,
        "See where it goes",
        "Insights shows spending by category, income and expenses per month, and how your savings grow. " +
            "Share a PDF or CSV statement for any period.",
    ),
)

/** First-launch introduction: three feature pages, bank notifications (with disclosure), then basics. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WelcomeScreen(onFinish: () -> Unit, onSetUpBank: () -> Unit) {
    val settings = (LocalContext.current.applicationContext as BillBookApp).settings
    val pageCount = INTRO.size + 2
    val pager = rememberPagerState { pageCount }
    val scope = rememberCoroutineScope()
    var currency by remember { mutableStateOf(settings.currency) }
    var business by remember { mutableStateOf(settings.businessName) }
    var setUpBank by remember { mutableStateOf(false) }

    fun finish() {
        settings.update(currency, business)
        settings.updateOnboardingDone(true)
        if (setUpBank) onSetUpBank() else onFinish()
    }
    fun next() = scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            if (pager.currentPage < pageCount - 1) TextButton(onClick = ::finish) { Text("Skip") }
            else Spacer(Modifier.height(48.dp))
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                when {
                    page < INTRO.size -> {
                        val p = INTRO[page]
                        Badge(p.icon)
                        Text(p.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Text(p.body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    }
                    page == INTRO.size -> {
                        Badge(Icons.Default.AccountBalance)
                        Text("Bank notifications (optional)", style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center)
                        Text(
                            "When your bank app shows a payment, Pocket Ledger can suggest it as an entry.",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                        NotificationDisclosureText()
                        if (setUpBank) {
                            Text(
                                "Agreed. You'll choose your banking apps after the last step.",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Button(onClick = {
                                settings.updateNotificationConsent(true)
                                setUpBank = true
                                next()
                            }) { Text("Agree and set up after start") }
                        }
                        TextButton(onClick = { setUpBank = false; next() }) { Text("Not now") }
                    }
                    else -> {
                        Badge(Icons.Default.Tune)
                        Text("Two quick settings", style = MaterialTheme.typography.headlineSmall)
                        SuggestField(
                            label = "Default currency",
                            value = currency,
                            onValueChange = { v -> currency = v.filter { it.isLetter() }.uppercase().take(3) },
                            options = Categories.currencies,
                        )
                        OutlinedTextField(
                            value = business,
                            onValueChange = { business = it },
                            label = { Text("Your name or business (optional)") },
                            supportingText = { Text("Printed at the top of statements") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Both can be changed later in Settings. Scanned bills use the currency printed on the bill.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(pageCount) { i ->
                    Box(
                        Modifier.size(8.dp).clip(CircleShape).background(
                            if (i == pager.currentPage) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                }
            }
            if (pager.currentPage == pageCount - 1) {
                Button(onClick = ::finish) { Text("Start") }
            } else {
                OutlinedButton(onClick = { next() }) { Text("Next") }
            }
        }
    }
}

@Composable
private fun Badge(icon: ImageVector) {
    Box(
        Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
