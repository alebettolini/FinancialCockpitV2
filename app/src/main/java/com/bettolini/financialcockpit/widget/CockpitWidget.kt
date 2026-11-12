package com.bettolini.financialcockpit.widget

import android.content.Context
import android.content.res.Configuration
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

private data class WidgetPalette(
    val surface: Color,
    val surfaceMuted: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val heroAccent: Color,
    val syncBg: Color,
    val syncFg: Color,
    val sustenanceBar: Color,
    val overheadBar: Color,
    val discretionaryBar: Color,
    val cardLimitBar: Color,
    val ccPaidFg: Color,
    val ccAlertFg: Color
)

private fun resolveWidgetPalette(isDark: Boolean, isMono: Boolean): WidgetPalette {
    return when {
        // 1. Monochrome Mode (Matches Pixel Launcher "Minimal" greyscale icons)
        isMono && isDark -> WidgetPalette(
            surface = Color(0xFF1A1A1D),
            surfaceMuted = Color(0xFF2C2C30),
            textPrimary = Color(0xFFF2F2F7),
            textSecondary = Color(0xFF9E9EA4),
            heroAccent = Color(0xFFFFFFFF),
            syncBg = Color(0xFF2C2C30),
            syncFg = Color(0xFFE5E5EA),
            sustenanceBar = Color(0xFFD1D1D6),
            overheadBar = Color(0xFF8E8E93),
            discretionaryBar = Color(0xFF636366),
            cardLimitBar = Color(0xFFAEAEB2),
            ccPaidFg = Color(0xFFD1D1D6),
            ccAlertFg = Color(0xFFAEAEB2)
        )
        isMono && !isDark -> WidgetPalette(
            surface = Color(0xFFF3F4F6),
            surfaceMuted = Color(0xFFDCDFE4),
            textPrimary = Color(0xFF1C1C1E),
            textSecondary = Color(0xFF636366),
            heroAccent = Color(0xFF1C1C1E),
            syncBg = Color(0xFFE2E4E9),
            syncFg = Color(0xFF1C1C1E),
            sustenanceBar = Color(0xFF3A3A3C),
            overheadBar = Color(0xFF636366),
            discretionaryBar = Color(0xFF8E8E93),
            cardLimitBar = Color(0xFF48484A),
            ccPaidFg = Color(0xFF2C2C2E),
            ccAlertFg = Color(0xFF48484A)
        )

        // 2. Full Brand Palette - Dark (#0D0A18 / #171229 + Violet, Amber, Plum, Gold)
        isDark -> WidgetPalette(
            surface = Color(0xFF171229),
            surfaceMuted = Color(0xFF261E42),
            textPrimary = Color(0xFFF5F3FC),
            textSecondary = Color(0xFF9C93BA),
            heroAccent = Color(0xFFB57EE8),
            syncBg = Color(0xFF2A1F4D),
            syncFg = Color(0xFFD7B5FA),
            sustenanceBar = Color(0xFF9F5CE0),
            overheadBar = Color(0xFFFAB301),
            discretionaryBar = Color(0xFFD177B5),
            cardLimitBar = Color(0xFFFF8601),
            ccPaidFg = Color(0xFF2EE58F),
            ccAlertFg = Color(0xFFFFB429)
        )

        // 3. Full Brand Palette - Light (#EEF7FF + Violet #8B4ABE, Amber #FAB301, Plum #A85990)
        else -> WidgetPalette(
            surface = Color(0xFFEEF7FF),
            surfaceMuted = Color(0xFFD6E6F7),
            textPrimary = Color(0xFF19132E),
            textSecondary = Color(0xFF625B7A),
            heroAccent = Color(0xFF6D35A3),
            syncBg = Color(0xFFE3D5F5),
            syncFg = Color(0xFF5D2A91),
            sustenanceBar = Color(0xFF8B4ABE),
            overheadBar = Color(0xFFFAB301),
            discretionaryBar = Color(0xFFA85990),
            cardLimitBar = Color(0xFFFF8601),
            ccPaidFg = Color(0xFF12945A),
            ccAlertFg = Color(0xFFE58200)
        )
    }
}

class CockpitWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = AppDatabase.getInstance(context).financeDao()
        val snapshot = dao.getSnapshotSync()
        val vaults = dao.getAllVaultsSync()

        val prefs = context.getSharedPreferences("cockpit_ui_prefs", Context.MODE_PRIVATE)
        val themeMode = prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM"
        val isMono = prefs.getBoolean("widget_mono", false)

        val nightFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val systemDark = nightFlags == Configuration.UI_MODE_NIGHT_YES
        val isDark = when (themeMode) {
            "DARK" -> true
            "LIGHT" -> false
            else -> systemDark
        }

        val palette = resolveWidgetPalette(isDark = isDark, isMono = isMono)

        provideContent {
            CockpitWidgetContent(snapshot = snapshot, vaults = vaults, palette = palette)
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

private fun vaultBarColor(key: String, palette: WidgetPalette): Color = when (key) {
    "SUSTENANCE_FLEET" -> palette.sustenanceBar
    "FIXED_OVERHEAD" -> palette.overheadBar
    "DISCRETIONARY" -> palette.discretionaryBar
    "TRAVEL" -> palette.discretionaryBar
    "WEALTH_SURPLUS" -> palette.overheadBar
    "RENT" -> palette.sustenanceBar
    else -> palette.sustenanceBar
}

@Composable
private fun SyncPill(palette: WidgetPalette, compact: Boolean = false) {
    Box(
        modifier = GlanceModifier
            .background(ColorProvider(palette.syncBg))
            .cornerRadius(8.dp)
            .padding(
                horizontal = if (compact) 7.dp else 9.dp,
                vertical = if (compact) 5.dp else 3.dp
            )
            .clickable(actionRunCallback<RefreshCockpitAction>()),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (compact) "↻" else "↻ Sync",
            style = TextStyle(
                color = ColorProvider(palette.syncFg),
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
    vaults: List<VaultBalanceEntity>,
    palette: WidgetPalette
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
                    .background(ColorProvider(palette.surface))
                    .cornerRadius(18.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = "AED ${String.format("%,.0f", usableCash)}",
                        style = TextStyle(color = ColorProvider(palette.heroAccent), fontSize = 18.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                    Text(
                        text = "AED ${String.format("%.0f", dailySafe)}/d",
                        style = TextStyle(color = ColorProvider(palette.textPrimary), fontSize = 12.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1
                    )
                }
                SyncPill(palette = palette, compact = true)
            }
        }

        // 2. NARROW TALL (2x2 / 2x3)
        isNarrow && !isShort -> {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(palette.surface))
                    .cornerRadius(20.dp)
                    .padding(12.dp)
                    .clickable(actionStartActivity<MainActivity>())
            ) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Text(
                        text = "●",
                        style = TextStyle(color = ColorProvider(palette.heroAccent), fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        modifier = GlanceModifier.defaultWeight()
                    )
                    SyncPill(palette = palette, compact = true)
                }

                Spacer(modifier = GlanceModifier.height(4.dp))

                Text(
                    text = "USABLE CASH",
                    style = TextStyle(color = ColorProvider(palette.textSecondary), fontSize = 9.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1
                )
                Text(
                    text = "AED ${String.format("%,.0f", usableCash)}",
                    style = TextStyle(color = ColorProvider(palette.heroAccent), fontSize = 20.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1
                )

                Spacer(modifier = GlanceModifier.height(6.dp))

                Text(
                    text = "DAILY PACE (${daysLeft}D)",
                    style = TextStyle(color = ColorProvider(palette.textSecondary), fontSize = 9.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1
                )
                Text(
                    text = "AED ${String.format("%.0f", dailySafe)}/d",
                    style = TextStyle(color = ColorProvider(palette.textPrimary), fontSize = 15.sp, fontWeight = FontWeight.Bold),
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
                                    style = TextStyle(color = ColorProvider(palette.textSecondary), fontSize = 9.sp, fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                    modifier = GlanceModifier.defaultWeight()
                                )
                                Text(
                                    text = String.format("%,.0f", vault.currentRemaining),
                                    style = TextStyle(color = ColorProvider(palette.textPrimary), fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                    maxLines = 1
                                )
                            }
                            Spacer(modifier = GlanceModifier.height(2.dp))
                            LinearProgressIndicator(
                                progress = frac,
                                modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                                color = ColorProvider(vaultBarColor(vault.vaultKey, palette)),
                                backgroundColor = ColorProvider(palette.surfaceMuted)
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
                    .background(ColorProvider(palette.surface))
                    .cornerRadius(18.dp)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = "USABLE CASH",
                        style = TextStyle(color = ColorProvider(palette.textSecondary), fontSize = 9.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                    Text(
                        text = "AED ${String.format("%,.0f", usableCash)}",
                        style = TextStyle(color = ColorProvider(palette.heroAccent), fontSize = 20.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                }

                Column(
                    horizontalAlignment = Alignment.Horizontal.End,
                    modifier = GlanceModifier.padding(end = 12.dp)
                ) {
                    Text(
                        text = "DAILY PACE (${daysLeft}D)",
                        style = TextStyle(color = ColorProvider(palette.textSecondary), fontSize = 9.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                    Text(
                        text = "AED ${String.format("%.0f", dailySafe)}/d",
                        style = TextStyle(color = ColorProvider(palette.textPrimary), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                }

                SyncPill(palette = palette, compact = true)
            }
        }

        // 4. MEDIUM (3x2 / 3x3) & WIDE (4x2 / 5x2 / 4x3) COCKPIT
        else -> {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(palette.surface))
                    .cornerRadius(22.dp)
                    .padding(if (isMedium) 13.dp else 15.dp)
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
                            color = ColorProvider(palette.heroAccent),
                            fontSize = if (isMedium) 9.sp else 10.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight()
                    )
                    SyncPill(palette = palette, compact = isMedium)
                }

                Spacer(modifier = GlanceModifier.height(6.dp))

                // Hero Row
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Vertical.Bottom
                ) {
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            text = "USABLE CASH",
                            style = TextStyle(color = ColorProvider(palette.textSecondary), fontSize = if (isMedium) 9.sp else 10.sp, fontWeight = FontWeight.Bold),
                            maxLines = 1
                        )
                        Text(
                            text = "AED ${String.format("%,.0f", usableCash)}",
                            style = TextStyle(
                                color = ColorProvider(palette.heroAccent),
                                fontSize = if (isMedium) 20.sp else 25.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            maxLines = 1
                        )
                    }

                    Column(horizontalAlignment = Alignment.Horizontal.End) {
                        Text(
                            text = "DAILY (${daysLeft}D)",
                            style = TextStyle(color = ColorProvider(palette.textSecondary), fontSize = if (isMedium) 9.sp else 10.sp, fontWeight = FontWeight.Bold),
                            maxLines = 1
                        )
                        Text(
                            text = if (isMedium) "AED ${String.format("%.0f", dailySafe)}/d" else "AED ${String.format("%.0f", dailySafe)}/day",
                            style = TextStyle(
                                color = ColorProvider(palette.textPrimary),
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
                                style = TextStyle(color = ColorProvider(palette.textSecondary), fontSize = 10.sp, fontWeight = FontWeight.Medium),
                                maxLines = 1,
                                modifier = GlanceModifier.defaultWeight()
                            )
                            Text(
                                text = "${(cashPct * 100).toInt()}%",
                                style = TextStyle(color = ColorProvider(palette.heroAccent), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                maxLines = 1
                            )
                        }
                        Spacer(modifier = GlanceModifier.height(3.dp))
                        LinearProgressIndicator(
                            progress = cashPct,
                            modifier = GlanceModifier.fillMaxWidth().height(5.dp),
                            color = ColorProvider(palette.heroAccent),
                            backgroundColor = ColorProvider(palette.surfaceMuted)
                        )
                    }

                    activeVaults.take(3).forEach { vault ->
                        val frac = if (vault.allocatedAmount > 0.0) (vault.currentRemaining / vault.allocatedAmount).toFloat().coerceIn(0f, 1f) else 0f
                        Column(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
                            Row(modifier = GlanceModifier.fillMaxWidth()) {
                                Text(
                                    text = if (isMedium) vaultShortName(vault.vaultKey) else vaultDisplayName(vault.vaultKey),
                                    style = TextStyle(color = ColorProvider(palette.textSecondary), fontSize = 10.sp, fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                    modifier = GlanceModifier.defaultWeight()
                                )
                                Text(
                                    text = "AED ${String.format("%,.0f", vault.currentRemaining)}",
                                    style = TextStyle(color = ColorProvider(palette.textPrimary), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                    maxLines = 1
                                )
                            }
                            Spacer(modifier = GlanceModifier.height(3.dp))
                            LinearProgressIndicator(
                                progress = frac,
                                modifier = GlanceModifier.fillMaxWidth().height(5.dp),
                                color = ColorProvider(vaultBarColor(vault.vaultKey, palette)),
                                backgroundColor = ColorProvider(palette.surfaceMuted)
                            )
                        }
                    }

                    val ccUtil = (totalCcOwed / ccLimit).toFloat().coerceIn(0f, 1f)
                    Column(modifier = GlanceModifier.fillMaxWidth()) {
                        Row(modifier = GlanceModifier.fillMaxWidth()) {
                            Text(
                                text = "Card 3445 Limit",
                                style = TextStyle(color = ColorProvider(palette.textSecondary), fontSize = 10.sp, fontWeight = FontWeight.Medium),
                                maxLines = 1,
                                modifier = GlanceModifier.defaultWeight()
                            )
                            Text(
                                text = "AED ${String.format("%,.0f", totalCcOwed)} / 42k",
                                style = TextStyle(color = ColorProvider(palette.ccAlertFg), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                maxLines = 1
                            )
                        }
                        Spacer(modifier = GlanceModifier.height(3.dp))
                        LinearProgressIndicator(
                            progress = ccUtil,
                            modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                            color = ColorProvider(palette.cardLimitBar),
                            backgroundColor = ColorProvider(palette.surfaceMuted)
                        )
                    }
                } else if (isMedium) {
                    activeVaults.take(2).forEachIndexed { idx, vault ->
                        val frac = if (vault.allocatedAmount > 0.0) (vault.currentRemaining / vault.allocatedAmount).toFloat().coerceIn(0f, 1f) else 0f
                        Column(modifier = GlanceModifier.fillMaxWidth().padding(bottom = if (idx == 0) 6.dp else 0.dp)) {
                            Row(modifier = GlanceModifier.fillMaxWidth()) {
                                Text(
                                    text = vaultShortName(vault.vaultKey),
                                    style = TextStyle(color = ColorProvider(palette.textSecondary), fontSize = 10.sp, fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                    modifier = GlanceModifier.defaultWeight()
                                )
                                Text(
                                    text = "AED ${String.format("%,.0f", vault.currentRemaining)}",
                                    style = TextStyle(color = ColorProvider(palette.textPrimary), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                    maxLines = 1
                                )
                            }
                            Spacer(modifier = GlanceModifier.height(2.dp))
                            LinearProgressIndicator(
                                progress = frac,
                                modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                                color = ColorProvider(vaultBarColor(vault.vaultKey, palette)),
                                backgroundColor = ColorProvider(palette.surfaceMuted)
                            )
                        }
                    }
                } else {
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
                                            style = TextStyle(color = ColorProvider(palette.textSecondary), fontSize = 10.sp, fontWeight = FontWeight.Medium),
                                            maxLines = 1,
                                            modifier = GlanceModifier.defaultWeight()
                                        )
                                        Text(
                                            text = "AED ${String.format("%,.0f", vault.currentRemaining)}",
                                            style = TextStyle(color = ColorProvider(palette.textPrimary), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                            maxLines = 1
                                        )
                                    }
                                    Spacer(modifier = GlanceModifier.height(3.dp))
                                    LinearProgressIndicator(
                                        progress = frac,
                                        modifier = GlanceModifier.fillMaxWidth().height(5.dp),
                                        color = ColorProvider(vaultBarColor(vault.vaultKey, palette)),
                                        backgroundColor = ColorProvider(palette.surfaceMuted)
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
                    val billColor = if (billedDue <= 0.01) palette.ccPaidFg else palette.ccAlertFg

                    Text(
                        text = billText,
                        style = TextStyle(color = ColorProvider(billColor), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight()
                    )
                    Text(
                        text = if (isMedium) "Unbilled: ${String.format("%,.0f", unbilled)}" else "Unbilled: AED ${String.format("%,.0f", unbilled)}",
                        style = TextStyle(color = ColorProvider(palette.ccAlertFg), fontSize = 10.sp, fontWeight = FontWeight.Bold),
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
