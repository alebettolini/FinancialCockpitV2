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
private fun SyncPill(compact: Boolean = false) {
    Box(
        modifier = GlanceModifier
            .background(ColorProvider(Color(0xFF1E1E22)))
            .cornerRadius(8.dp)
            .padding(
                horizontal = if (compact) 7.dp else 8.dp,
                vertical = if (compact) 5.dp else 3.dp
            )
            .clickable(actionRunCallback<RefreshCockpitAction>()),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (compact) "↻" else "↻ Sync",
            style = TextStyle(
                color = ColorProvider(Color(0xFF00E676)),
                fontSize = if (compact) 13.sp else 10.sp,
                fontWeight = FontWeight.Bold
            ),
            maxLines = 1
        )
    }
}

@Composable
private fun CockpitWidgetContent(
    snapshot: AccountSnapshotEntity?,
    vaults: List<VaultBalanceEntity>
) {
    val size = LocalSize.current
    val isNarrow = size.width < 195.dp
    val isMedium = size.width >= 195.dp && size.width < 275.dp
    val isShort = size.height < 115.dp
    val isTall = size.height >= 235.dp

    val today = LocalDate.now()
    val payday = if (today.dayOfMonth < 22) today.withDayOfMonth(22) else today.plusMonths(1).withDayOfMonth(22)
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

    val priorityOrder = listOf("SUSTENANCE_FLEET", "FIXED_OVERHEAD", "DISCRETIONARY", "TRAVEL", "WEALTH_SURPLUS", "RENT")
    val activeVaults = priorityOrder
        .mapNotNull { key -> vaults.find { it.vaultKey == key } }
        .filter { it.allocatedAmount > 0.0 || it.currentRemaining > 0.0 }

    when {
        // 1. TINY SQUARE (2x1)
        isNarrow && isShort -> {
            Row(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(Color(0xFF121214)))
                    .cornerRadius(16.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = "AED ${String.format("%,.0f", usableCash)}",
                        style = TextStyle(color = ColorProvider(Color(0xFF00E676)), fontSize = 18.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                    Text(
                        text = "AED ${String.format("%.0f", dailySafe)}/d",
                        style = TextStyle(color = ColorProvider(Color.White), fontSize = 12.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1
                    )
                }
                SyncPill(compact = true)
            }
        }

        // 2. NARROW TALL (2x2 / 2x3)
        isNarrow && !isShort -> {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(Color(0xFF121214)))
                    .cornerRadius(18.dp)
                    .padding(12.dp)
                    .clickable(actionStartActivity<MainActivity>())
            ) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Text(
                        text = "●",
                        style = TextStyle(color = ColorProvider(modeColor), fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        modifier = GlanceModifier.defaultWeight()
                    )
                    SyncPill(compact = true)
                }

                Spacer(modifier = GlanceModifier.height(4.dp))

                Text(
                    text = "USABLE CASH",
                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 9.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1
                )
                Text(
                    text = "AED ${String.format("%,.0f", usableCash)}",
                    style = TextStyle(color = ColorProvider(Color(0xFF00E676)), fontSize = 20.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1
                )

                Spacer(modifier = GlanceModifier.height(6.dp))

                Text(
                    text = "DAILY PACE (${daysLeft}D)",
                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 9.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1
                )
                Text(
                    text = "AED ${String.format("%.0f", dailySafe)}/d",
                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1
                )

                if (size.height >= 175.dp && activeVaults.isNotEmpty()) {
                    Spacer(modifier = GlanceModifier.defaultWeight())
                    activeVaults.take(2).forEach { vault ->
                        val frac = if (vault.allocatedAmount > 0.0) (vault.currentRemaining / vault.allocatedAmount).toFloat().coerceIn(0f, 1f) else 0f
                        Column(modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp)) {
                            Row(modifier = GlanceModifier.fillMaxWidth()) {
                                Text(
                                    text = vaultShortName(vault.vaultKey),
                                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 9.sp, fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                    modifier = GlanceModifier.defaultWeight()
                                )
                                Text(
                                    text = "${String.format("%,.0f", vault.currentRemaining)}",
                                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                    maxLines = 1
                                )
                            }
                            Spacer(modifier = GlanceModifier.height(2.dp))
                            LinearProgressIndicator(
                                progress = frac,
                                modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                                color = ColorProvider(vaultBarColor(vault.vaultKey)),
                                backgroundColor = ColorProvider(Color(0xFF242426))
                            )
                        }
                    }
                }
            }
        }

        // 3. WIDE COMPACT BAR (3x1 / 4x1)
        !isNarrow && isShort -> {
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
                        text = "USABLE CASH",
                        style = TextStyle(color = ColorProvider(Color.White), fontSize = 9.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                    Text(
                        text = "AED ${String.format("%,.0f", usableCash)}",
                        style = TextStyle(color = ColorProvider(Color(0xFF00E676)), fontSize = 20.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                }

                Column(
                    horizontalAlignment = Alignment.Horizontal.End,
                    modifier = GlanceModifier.padding(end = 12.dp)
                ) {
                    Text(
                        text = "DAILY PACE (${daysLeft}D)",
                        style = TextStyle(color = ColorProvider(Color.White), fontSize = 9.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                    Text(
                        text = "AED ${String.format("%.0f", dailySafe)}/d",
                        style = TextStyle(color = ColorProvider(Color.White), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                }

                SyncPill(compact = true)
            }
        }

        // 4. MEDIUM (3x2 / 3x3) & WIDE (4x2 / 5x2 / 4x3) COCKPIT
        else -> {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(Color(0xFF121214)))
                    .cornerRadius(18.dp)
                    .padding(if (isMedium) 12.dp else 14.dp)
                    .clickable(actionStartActivity<MainActivity>())
            ) {
                // Header: Mode + Sync
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Text(
                        text = "● ${activeMode.first}",
                        style = TextStyle(
                            color = ColorProvider(modeColor),
                            fontSize = if (isMedium) 9.sp else 10.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight()
                    )
                    SyncPill(compact = isMedium)
                }

                Spacer(modifier = GlanceModifier.height(6.dp))

                // Hero Row: Scales typography cleanly at 3 columns so AED never truncates
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Vertical.Bottom
                ) {
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            text = "USABLE CASH",
                            style = TextStyle(color = ColorProvider(Color.White), fontSize = if (isMedium) 9.sp else 10.sp, fontWeight = FontWeight.Bold),
                            maxLines = 1
                        )
                        Text(
                            text = "AED ${String.format("%,.0f", usableCash)}",
                            style = TextStyle(
                                color = ColorProvider(Color(0xFF00E676)),
                                fontSize = if (isMedium) 20.sp else 25.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            maxLines = 1
                        )
                    }

                    Column(horizontalAlignment = Alignment.Horizontal.End) {
                        Text(
                            text = "DAILY (${daysLeft}D)",
                            style = TextStyle(color = ColorProvider(Color.White), fontSize = if (isMedium) 9.sp else 10.sp, fontWeight = FontWeight.Bold),
                            maxLines = 1
                        )
                        Text(
                            text = if (isMedium) "AED ${String.format("%.0f", dailySafe)}/d" else "AED ${String.format("%.0f", dailySafe)}/day",
                            style = TextStyle(
                                color = ColorProvider(Color.White),
                                fontSize = if (isMedium) 14.sp else 16.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = GlanceModifier.defaultWeight())

                // Progressive Middle Telemetry
                if (isTall) {
                    val cashPct = if (cyclePool > 0.0) (usableCash / cyclePool).toFloat().coerceIn(0f, 1f) else 0.43f
                    Column(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        Row(modifier = GlanceModifier.fillMaxWidth()) {
                            Text(
                                text = "Cash Runway",
                                style = TextStyle(color = ColorProvider(Color.White), fontSize = 10.sp, fontWeight = FontWeight.Medium),
                                maxLines = 1,
                                modifier = GlanceModifier.defaultWeight()
                            )
                            Text(
                                text = "${(cashPct * 100).toInt()}%",
                                style = TextStyle(color = ColorProvider(Color(0xFF00E676)), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                maxLines = 1
                            )
                        }
                        Spacer(modifier = GlanceModifier.height(3.dp))
                        LinearProgressIndicator(
                            progress = cashPct,
                            modifier = GlanceModifier.fillMaxWidth().height(5.dp),
                            color = ColorProvider(Color(0xFF00E676)),
                            backgroundColor = ColorProvider(Color(0xFF242426))
                        )
                    }

                    activeVaults.take(3).forEach { vault ->
                        val frac = if (vault.allocatedAmount > 0.0) (vault.currentRemaining / vault.allocatedAmount).toFloat().coerceIn(0f, 1f) else 0f
                        Column(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
                            Row(modifier = GlanceModifier.fillMaxWidth()) {
                                Text(
                                    text = if (isMedium) vaultShortName(vault.vaultKey) else vaultDisplayName(vault.vaultKey),
                                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 10.sp, fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                    modifier = GlanceModifier.defaultWeight()
                                )
                                Text(
                                    text = "AED ${String.format("%,.0f", vault.currentRemaining)}",
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

                    val ccUtil = (totalCcOwed / ccLimit).toFloat().coerceIn(0f, 1f)
                    Column(modifier = GlanceModifier.fillMaxWidth()) {
                        Row(modifier = GlanceModifier.fillMaxWidth()) {
                            Text(
                                text = "Card 3445 Limit",
                                style = TextStyle(color = ColorProvider(Color.White), fontSize = 10.sp, fontWeight = FontWeight.Medium),
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
                } else if (isMedium) {
                    // 3-Column 2-Row Height: Stack top 2 Vault Bars vertically so "Sustenance" never truncates
                    activeVaults.take(2).forEachIndexed { idx, vault ->
                        val frac = if (vault.allocatedAmount > 0.0) (vault.currentRemaining / vault.allocatedAmount).toFloat().coerceIn(0f, 1f) else 0f
                        Column(modifier = GlanceModifier.fillMaxWidth().padding(bottom = if (idx == 0) 6.dp else 0.dp)) {
                            Row(modifier = GlanceModifier.fillMaxWidth()) {
                                Text(
                                    text = vaultShortName(vault.vaultKey),
                                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 10.sp, fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                    modifier = GlanceModifier.defaultWeight()
                                )
                                Text(
                                    text = "AED ${String.format("%,.0f", vault.currentRemaining)}",
                                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                    maxLines = 1
                                )
                            }
                            Spacer(modifier = GlanceModifier.height(2.dp))
                            LinearProgressIndicator(
                                progress = frac,
                                modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                                color = ColorProvider(vaultBarColor(vault.vaultKey)),
                                backgroundColor = ColorProvider(Color(0xFF242426))
                            )
                        }
                    }
                } else {
                    // Wide 4/5-Column 2-Row Height: Side-by-side Top 2 Vault Bars
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
                                        .padding(end = if (idx == 0 && topTwo.size > 1) 10.dp else 0.dp)
                                ) {
                                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                                        Text(
                                            text = vaultShortName(vault.vaultKey),
                                            style = TextStyle(color = ColorProvider(Color.White), fontSize = 10.sp, fontWeight = FontWeight.Medium),
                                            maxLines = 1,
                                            modifier = GlanceModifier.defaultWeight()
                                        )
                                        Text(
                                            text = "AED ${String.format("%,.0f", vault.currentRemaining)}",
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
                        }
                    }
                }

                Spacer(modifier = GlanceModifier.defaultWeight())

                // Footer Row: CC Status + Unbilled
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    val billText = when {
                        billedDue <= 0.01 && isMedium -> "CC: PAID ✓"
                        billedDue <= 0.01 -> "CC Bill: PAID ✓"
                        isMedium -> "Due: ${String.format("%,.0f", billedDue)}"
                        else -> "CC Due: AED ${String.format("%,.0f", billedDue)}"
                    }
                    val billColor = if (billedDue <= 0.01) Color(0xFF00E676) else Color(0xFFFF9F0A)

                    Text(
                        text = billText,
                        style = TextStyle(color = ColorProvider(billColor), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight()
                    )
                    Text(
                        text = if (isMedium) "Unbilled: ${String.format("%,.0f", unbilled)}" else "Unbilled: AED ${String.format("%,.0f", unbilled)}",
                        style = TextStyle(color = ColorProvider(Color(0xFFFF453A)), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

class CockpitWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CockpitWidget()
}
