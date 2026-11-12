package com.bettolini.financialcockpit.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.bettolini.financialcockpit.MainActivity
import com.bettolini.financialcockpit.data.AccountSnapshotEntity
import com.bettolini.financialcockpit.data.AppDatabase
import com.bettolini.financialcockpit.data.SmsHistoryScanner
import com.bettolini.financialcockpit.data.VaultBalanceEntity
import com.bettolini.financialcockpit.domain.DynamicAllocationEngine
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class RefreshCockpitAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        try {
            SmsHistoryScanner.scanAndSyncInbox(context.applicationContext)
        } catch (_: Exception) {
        }
        CockpitWidget().updateAll(context)
    }
}

class CockpitWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = AppDatabase.getInstance(context).financeDao()
        val snapshot = dao.getSnapshotSync()
        val vaults = dao.getAllVaultsSync()

        provideContent {
            CockpitWidgetContent(snapshot = snapshot, vaults = vaults)
        }
    }
}

private fun vaultDisplayName(key: String): String = when (key) {
    "SUSTENANCE_FLEET" -> "Sustenance & Fleet"
    "FIXED_OVERHEAD" -> "Fixed Overhead"
    "DISCRETIONARY" -> "Discretionary"
    "WEALTH_SURPLUS" -> "Wealth Surplus"
    "TRAVEL" -> "Travel"
    "RENT" -> "Rent"
    else -> key.replace("_", " ")
}

private fun vaultShortName(key: String): String = when (key) {
    "SUSTENANCE_FLEET" -> "Sustenance"
    "FIXED_OVERHEAD" -> "Overhead"
    "DISCRETIONARY" -> "Discretionary"
    "WEALTH_SURPLUS" -> "Surplus"
    "TRAVEL" -> "Travel"
    "RENT" -> "Rent"
    else -> key
}

private fun vaultBarColor(key: String): Color = when (key) {
    "RENT" -> Color(0xFF388AF6)
    "FIXED_OVERHEAD" -> Color(0xFFFF9500)
    "SUSTENANCE_FLEET" -> Color(0xFF30D158)
    "DISCRETIONARY" -> Color(0xFFAF52DE)
    "TRAVEL" -> Color(0xFF5AC8FA)
    "WEALTH_SURPLUS" -> Color(0xFFFFD60A)
    else -> Color(0xFF00E676)
}

@Composable
private fun CockpitWidgetContent(
    snapshot: AccountSnapshotEntity?,
    vaults: List<VaultBalanceEntity>
) {
    val size = LocalSize.current
    val isCompact = size.height < 115.dp
    val isExpanded = size.height >= 205.dp
    val isNarrow = size.width < 235.dp

    val today = LocalDate.now()
    val cycleStart = SmsHistoryScanner.getCurrentCycleStart(today)
    val payday = if (today.dayOfMonth < 22) today.withDayOfMonth(22) else today.plusMonths(1).withDayOfMonth(22)
    val totalCycleDays = ChronoUnit.DAYS.between(cycleStart, payday).coerceAtLeast(1)
    val daysLeft = ChronoUnit.DAYS.between(today, payday).coerceAtLeast(1)

    val checking = snapshot?.liveDebitBalance ?: 2737.93
    val billedDue = snapshot?.statementDue ?: 0.0
    val unbilled = snapshot?.unbilledSpend ?: 4987.45
    val totalCcOwed = snapshot?.creditTotalOwed ?: (billedDue + unbilled)
    val ccLimit = snapshot?.creditCardLimit ?: 42000.0
    val cyclePool = snapshot?.cycleStartingPool ?: 6380.0

    val usableCash = (checking - billedDue).coerceAtLeast(0.0)
    val dailySafe = (usableCash / daysLeft.toDouble()).coerceAtLeast(0.0)
    val activeMode = DynamicAllocationEngine.getLiveModeBadge(usableCash)

    val modeColor = when (activeMode.first) {
        "APEX WEALTH MODE" -> Color(0xFFFFD60A)
        "BRIDGE VELOCITY" -> Color(0xFF388AF6)
        "CRUISING ALTITUDE" -> Color(0xFF5AC8FA)
        "STEADY RUNWAY" -> Color(0xFF00E676)
        else -> Color(0xFFFF9F0A)
    }

    // Order vaults by priority: Sustenance & Fleet first, then Fixed Overhead, then remaining funded vaults
    val priorityOrder = listOf("SUSTENANCE_FLEET", "FIXED_OVERHEAD", "DISCRETIONARY", "TRAVEL", "WEALTH_SURPLUS", "RENT")
    val activeVaults = priorityOrder
        .mapNotNull { key -> vaults.find { it.vaultKey == key } }
        .filter { it.allocatedAmount > 0.0 || it.currentRemaining > 0.0 }

    if (isCompact) {
        // 1-ROW COMPACT BAR (e.g. 4x1 or 3x1) - Zero vertical clipping
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Color(0xFF121214)))
                .cornerRadius(16.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = "AED ${String.format("%,.0f", usableCash)}",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF00E676)),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
                val statusSub = if (billedDue <= 0.01) {
                    "${daysLeft}d left • CC Paid ✓"
                } else {
                    "${daysLeft}d left • Due ${String.format("%,.0f", billedDue)}"
                }
                Text(
                    text = statusSub,
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF8E8E93)),
                        fontSize = 10.sp
                    ),
                    maxLines = 1
                )
            }

            Column(
                horizontalAlignment = Alignment.Horizontal.End,
                modifier = GlanceModifier.padding(end = 10.dp)
            ) {
                Text(
                    text = "AED ${String.format("%.0f", dailySafe)}/d",
                    style = TextStyle(
                        color = ColorProvider(Color.White),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
                Text(
                    text = "Card: ${String.format("%,.0f", unbilled)}",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFFFF453A)),
                        fontSize = 10.sp
                    ),
                    maxLines = 1
                )
            }

            Box(
                modifier = GlanceModifier
                    .background(ColorProvider(Color(0xFF1E1E22)))
                    .cornerRadius(10.dp)
                    .padding(horizontal = 9.dp, vertical = 7.dp)
                    .clickable(actionRunCallback<RefreshCockpitAction>()),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "↻",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF00E676)),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
            }
        }
    } else {
        // STANDARD (2-ROW) & EXPANDED (3+ ROW) VISUAL COCKPIT
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Color(0xFF121214)))
                .cornerRadius(18.dp)
                .padding(14.dp)
                .clickable(actionStartActivity<MainActivity>())
        ) {
            // 1. Header Row: Live Mode Badge + Sync Button
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Text(
                    text = "● ${activeMode.first}",
                    style = TextStyle(
                        color = ColorProvider(modeColor),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight()
                )
                Box(
                    modifier = GlanceModifier
                        .background(ColorProvider(Color(0xFF1E1E22)))
                        .cornerRadius(8.dp)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .clickable(actionRunCallback<RefreshCockpitAction>()),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "↻ Sync",
                        style = TextStyle(
                            color = ColorProvider(Color(0xFF00E676)),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = GlanceModifier.height(6.dp))

            // 2. Hero Balance & Safe Daily Spend (single-line locked so AED never wraps)
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.Bottom
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = "AED ${String.format("%,.0f", usableCash)}",
                        style = TextStyle(
                            color = ColorProvider(Color(0xFF00E676)),
                            fontSize = if (isNarrow) 20.sp else 24.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1
                    )
                    Text(
                        text = "Usable Cash • ${daysLeft}d to 22nd",
                        style = TextStyle(
                            color = ColorProvider(Color(0xFF8E8E93)),
                            fontSize = 10.sp
                        ),
                        maxLines = 1
                    )
                }

                Column(horizontalAlignment = Alignment.Horizontal.End) {
                    Text(
                        text = "AED ${String.format("%.0f", dailySafe)}/day",
                        style = TextStyle(
                            color = ColorProvider(Color.White),
                            fontSize = if (isNarrow) 13.sp else 15.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1
                    )
                    Text(
                        text = "Safe Daily Pace",
                        style = TextStyle(
                            color = ColorProvider(Color(0xFF8E8E93)),
                            fontSize = 10.sp
                        ),
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = GlanceModifier.defaultWeight())

            // 3. Visual Progress Section (Fills the center with live vault & runway telemetry)
            if (isExpanded) {
                // Cash vs. Time Runway Gauge
                val cashPct = if (cyclePool > 0.0) (usableCash / cyclePool).toFloat().coerceIn(0f, 1f) else 0.43f
                val timePct = (daysLeft.toFloat() / totalCycleDays.toFloat()).coerceIn(0f, 1f)

                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        Text(
                            text = "Cash Runway ${(cashPct * 100).toInt()}% left",
                            style = TextStyle(color = ColorProvider(Color(0xFFD1D1D6)), fontSize = 10.sp, fontWeight = FontWeight.Medium),
                            maxLines = 1,
                            modifier = GlanceModifier.defaultWeight()
                        )
                        Text(
                            text = "Cycle Time ${(timePct * 100).toInt()}% left",
                            style = TextStyle(color = ColorProvider(Color(0xFF8E8E93)), fontSize = 10.sp),
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = GlanceModifier.height(3.dp))
                    LinearProgressIndicator(
                        progress = cashPct,
                        modifier = GlanceModifier.fillMaxWidth().height(5.dp),
                        color = ColorProvider(if (cashPct >= timePct) Color(0xFF00E676) else Color(0xFFFF9F0A)),
                        backgroundColor = ColorProvider(Color(0xFF242426))
                    )
                }

                Spacer(modifier = GlanceModifier.height(8.dp))

                // Up to 3 Active Vault Bars
                activeVaults.take(3).forEach { vault ->
                    val frac = if (vault.allocatedAmount > 0.0) (vault.currentRemaining / vault.allocatedAmount).toFloat().coerceIn(0f, 1f) else 0f
                    Column(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        Row(modifier = GlanceModifier.fillMaxWidth()) {
                            Text(
                                text = vaultDisplayName(vault.vaultKey),
                                style = TextStyle(color = ColorProvider(Color(0xFFD1D1D6)), fontSize = 10.sp, fontWeight = FontWeight.Medium),
                                maxLines = 1,
                                modifier = GlanceModifier.defaultWeight()
                            )
                            Text(
                                text = "AED ${String.format("%,.0f", vault.currentRemaining)} (${(frac * 100).toInt()}%)",
                                style = TextStyle(color = ColorProvider(Color.White), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                maxLines = 1
                            )
                        }
                        Spacer(modifier = GlanceModifier.height(3.dp))
                        LinearProgressIndicator(
                            progress = frac,
                            modifier = GlanceModifier.fillMaxWidth().height(5.dp),
                            color = ColorProvider(vaultBarColor(vault.vaultKey)),
                            backgroundColor = ColorProvider(Color(0xFF242426))
                        )
                    }
                }

                // Credit Card Limit Utilization Bar
                val ccUtil = (totalCcOwed / ccLimit).toFloat().coerceIn(0f, 1f)
                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        Text(
                            text = "Card 3445 Limit Used (${(ccUtil * 100).toInt()}%)",
                            style = TextStyle(color = ColorProvider(Color(0xFF8E8E93)), fontSize = 10.sp),
                            maxLines = 1,
                            modifier = GlanceModifier.defaultWeight()
                        )
                        Text(
                            text = "AED ${String.format("%,.0f", totalCcOwed)} / 42k",
                            style = TextStyle(color = ColorProvider(Color(0xFFFF453A)), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = GlanceModifier.height(3.dp))
                    LinearProgressIndicator(
                        progress = ccUtil,
                        modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                        color = ColorProvider(Color(0xFFFF453A)),
                        backgroundColor = ColorProvider(Color(0xFF242426))
                    )
                }
            } else {
                // Standard 2-Row Height: Side-by-side or stacked top 2 Cash Vault Bars
                val topTwo = activeVaults.take(2)
                if (topTwo.isNotEmpty()) {
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Vertical.CenterVertically
                    ) {
                        topTwo.forEachIndexed { idx, vault ->
                            val frac = if (vault.allocatedAmount > 0.0) (vault.currentRemaining / vault.allocatedAmount).toFloat().coerceIn(0f, 1f) else 0f
                            Column(
                                modifier = GlanceModifier
                                    .defaultWeight()
                                    .padding(end = if (idx == 0 && topTwo.size > 1) 8.dp else 0.dp)
                            ) {
                                Row(modifier = GlanceModifier.fillMaxWidth()) {
                                    Text(
                                        text = vaultShortName(vault.vaultKey),
                                        style = TextStyle(color = ColorProvider(Color(0xFF8E8E93)), fontSize = 9.sp, fontWeight = FontWeight.Medium),
                                        maxLines = 1,
                                        modifier = GlanceModifier.defaultWeight()
                                    )
                                    Text(
                                        text = "AED ${String.format("%,.0f", vault.currentRemaining)}",
                                        style = TextStyle(color = ColorProvider(Color.White), fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                        maxLines = 1
                                    )
                                }
                                Spacer(modifier = GlanceModifier.height(3.dp))
                                LinearProgressIndicator(
                                    progress = frac,
                                    modifier = GlanceModifier.fillMaxWidth().height(5.dp),
                                    color = ColorProvider(vaultBarColor(vault.vaultKey)),
                                    backgroundColor = ColorProvider(Color(0xFF242426))
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = GlanceModifier.defaultWeight())

            // 4. Bottom Footer Row: CC Statement Status + Unbilled Balance
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                val billText = if (billedDue <= 0.01) "CC Bill: PAID ✓" else "CC Due: AED ${String.format("%,.0f", billedDue)}"
                val billColor = if (billedDue <= 0.01) Color(0xFF00E676) else Color(0xFFFF9F0A)

                Text(
                    text = billText,
                    style = TextStyle(
                        color = ColorProvider(billColor),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight()
                )
                Text(
                    text = "Unbilled: AED ${String.format("%,.0f", unbilled)}",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFFFF453A)),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
            }
        }
    }
}

class CockpitWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CockpitWidget()
}
