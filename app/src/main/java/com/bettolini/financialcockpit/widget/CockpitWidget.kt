package com.bettolini.financialcockpit.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
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

private data class WidgetColorTokens(
    val surface: ColorProvider,
    val surfaceMuted: ColorProvider,
    val textPrimary: ColorProvider,
    val textSecondary: ColorProvider,
    val heroAccent: ColorProvider,
    val syncBg: ColorProvider,
    val syncFg: ColorProvider,
    val sustenanceBar: ColorProvider,
    val overheadBar: ColorProvider,
    val discretionaryBar: ColorProvider,
    val cardLimitBar: ColorProvider,
    val ccPaidFg: ColorProvider,
    val ccAlertFg: ColorProvider
)

@Composable
private fun rememberWidgetTokens(widgetStyle: String, isDark: Boolean): WidgetColorTokens {
    if (widgetStyle == "SYSTEM") {
        return WidgetColorTokens(
            surface = GlanceTheme.colors.surface,
            surfaceMuted = GlanceTheme.colors.surfaceVariant,
            textPrimary = GlanceTheme.colors.onSurface,
            textSecondary = GlanceTheme.colors.onSurfaceVariant,
            heroAccent = GlanceTheme.colors.primary,
            syncBg = GlanceTheme.colors.secondaryContainer,
            syncFg = GlanceTheme.colors.onSecondaryContainer,
            sustenanceBar = GlanceTheme.colors.primary,
            overheadBar = GlanceTheme.colors.tertiary,
            discretionaryBar = GlanceTheme.colors.secondary,
            cardLimitBar = GlanceTheme.colors.tertiary,
            ccPaidFg = GlanceTheme.colors.primary,
            ccAlertFg = GlanceTheme.colors.tertiary
        )
    }

    val isMono = widgetStyle == "MONO"
    return when {
        isMono && isDark -> WidgetColorTokens(
            surface = ColorProvider(Color(0xFF1A1A1D)),
            surfaceMuted = ColorProvider(Color(0xFF2C2C30)),
            textPrimary = ColorProvider(Color(0xFFF2F2F7)),
            textSecondary = ColorProvider(Color(0xFF9E9EA4)),
            heroAccent = ColorProvider(Color(0xFFFFFFFF)),
            syncBg = ColorProvider(Color(0xFF2C2C30)),
            syncFg = ColorProvider(Color(0xFFE5E5EA)),
            sustenanceBar = ColorProvider(Color(0xFFD1D1D6)),
            overheadBar = ColorProvider(Color(0xFF8E8E93)),
            discretionaryBar = ColorProvider(Color(0xFF636366)),
            cardLimitBar = ColorProvider(Color(0xFFAEAEB2)),
            ccPaidFg = ColorProvider(Color(0xFFD1D1D6)),
            ccAlertFg = ColorProvider(Color(0xFFAEAEB2))
        )
        isMono && !isDark -> WidgetColorTokens(
            surface = ColorProvider(Color(0xFFF3F4F6)),
            surfaceMuted = ColorProvider(Color(0xFFDCDFE4)),
            textPrimary = ColorProvider(Color(0xFF1C1C1E)),
            textSecondary = ColorProvider(Color(0xFF636366)),
            heroAccent = ColorProvider(Color(0xFF1C1C1E)),
            syncBg = ColorProvider(Color(0xFFE2E4E9)),
            syncFg = ColorProvider(Color(0xFF1C1C1E)),
            sustenanceBar = ColorProvider(Color(0xFF3A3A3C)),
            overheadBar = ColorProvider(Color(0xFF636366)),
            discretionaryBar = ColorProvider(Color(0xFF8E8E93)),
            cardLimitBar = ColorProvider(Color(0xFF48484A)),
            ccPaidFg = ColorProvider(Color(0xFF2C2C2E)),
            ccAlertFg = ColorProvider(Color(0xFF48484A))
        )
        isDark -> WidgetColorTokens(
            surface = ColorProvider(Color(0xFF171229)),
            surfaceMuted = ColorProvider(Color(0xFF261E42)),
            textPrimary = ColorProvider(Color(0xFFF5F3FC)),
            textSecondary = ColorProvider(Color(0xFF9C93BA)),
            heroAccent = ColorProvider(Color(0xFFB57EE8)),
            syncBg = ColorProvider(Color(0xFF2A1F4D)),
            syncFg = ColorProvider(Color(0xFFD7B5FA)),
            sustenanceBar = ColorProvider(Color(0xFF9F5CE0)),
            overheadBar = ColorProvider(Color(0xFFFAB301)),
            discretionaryBar = ColorProvider(Color(0xFFD177B5)),
            cardLimitBar = ColorProvider(Color(0xFFFF8601)),
            ccPaidFg = ColorProvider(Color(0xFF2EE58F)),
            ccAlertFg = ColorProvider(Color(0xFFFFB429))
        )
        else -> WidgetColorTokens(
            surface = ColorProvider(Color(0xFFEEF7FF)),
            surfaceMuted = ColorProvider(Color(0xFFD6E6F7)),
            textPrimary = ColorProvider(Color(0xFF19132E)),
            textSecondary = ColorProvider(Color(0xFF625B7A)),
            heroAccent = ColorProvider(Color(0xFF6D35A3)),
            syncBg = ColorProvider(Color(0xFFE3D5F5)),
            syncFg = ColorProvider(Color(0xFF5D2A91)),
            sustenanceBar = ColorProvider(Color(0xFF8B4ABE)),
            overheadBar = ColorProvider(Color(0xFFFAB301)),
            discretionaryBar = ColorProvider(Color(0xFFA85990)),
            cardLimitBar = ColorProvider(Color(0xFFFF8601)),
            ccPaidFg = ColorProvider(Color(0xFF12945A)),
            ccAlertFg = ColorProvider(Color(0xFFE58200))
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
        val widgetStyle = prefs.getString("widget_style", "BRAND") ?: "BRAND"

        val nightFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val systemDark = nightFlags == Configuration.UI_MODE_NIGHT_YES
        val isDark = when (themeMode) {
            "DARK" -> true
            "LIGHT" -> false
            else -> systemDark
        }

        provideContent {
            GlanceTheme {
                val tokens = rememberWidgetTokens(widgetStyle = widgetStyle, isDark = isDark)
                CockpitWidgetContent(snapshot = snapshot, vaults = vaults, palette = tokens)
            }
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
    "TRAVEL" -> "Travel
cat << 'EOF' > apply_widget_update.sh
#!/bin/bash
set -e

rm -f sync_widget_palette.sh fix_widget_3x1_and_system_theme.sh

echo "1. Writing CockpitWidget.kt with dedicated 3x1 layout and Brand/System/Mono styles..."
cat << 'INNER_EOF' > app/src/main/java/com/bettolini/financialcockpit/widget/CockpitWidget.kt
package com.bettolini.financialcockpit.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
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

private data class WidgetColorTokens(
    val surface: ColorProvider,
    val surfaceMuted: ColorProvider,
    val textPrimary: ColorProvider,
    val textSecondary: ColorProvider,
    val heroAccent: ColorProvider,
    val syncBg: ColorProvider,
    val syncFg: ColorProvider,
    val sustenanceBar: ColorProvider,
    val overheadBar: ColorProvider,
    val discretionaryBar: ColorProvider,
    val cardLimitBar: ColorProvider,
    val ccPaidFg: ColorProvider,
    val ccAlertFg: ColorProvider
)

@Composable
private fun rememberWidgetTokens(widgetStyle: String, isDark: Boolean): WidgetColorTokens {
    if (widgetStyle == "SYSTEM") {
        return WidgetColorTokens(
            surface = GlanceTheme.colors.surface,
            surfaceMuted = GlanceTheme.colors.surfaceVariant,
            textPrimary = GlanceTheme.colors.onSurface,
            textSecondary = GlanceTheme.colors.onSurfaceVariant,
            heroAccent = GlanceTheme.colors.primary,
            syncBg = GlanceTheme.colors.secondaryContainer,
            syncFg = GlanceTheme.colors.onSecondaryContainer,
            sustenanceBar = GlanceTheme.colors.primary,
            overheadBar = GlanceTheme.colors.tertiary,
            discretionaryBar = GlanceTheme.colors.secondary,
            cardLimitBar = GlanceTheme.colors.tertiary,
            ccPaidFg = GlanceTheme.colors.primary,
            ccAlertFg = GlanceTheme.colors.tertiary
        )
    }

    val isMono = widgetStyle == "MONO"
    return when {
        isMono && isDark -> WidgetColorTokens(
            surface = ColorProvider(Color(0xFF1A1A1D)),
            surfaceMuted = ColorProvider(Color(0xFF2C2C30)),
            textPrimary = ColorProvider(Color(0xFFF2F2F7)),
            textSecondary = ColorProvider(Color(0xFF9E9EA4)),
            heroAccent = ColorProvider(Color(0xFFFFFFFF)),
            syncBg = ColorProvider(Color(0xFF2C2C30)),
            syncFg = ColorProvider(Color(0xFFE5E5EA)),
            sustenanceBar = ColorProvider(Color(0xFFD1D1D6)),
            overheadBar = ColorProvider(Color(0xFF8E8E93)),
            discretionaryBar = ColorProvider(Color(0xFF636366)),
            cardLimitBar = ColorProvider(Color(0xFFAEAEB2)),
            ccPaidFg = ColorProvider(Color(0xFFD1D1D6)),
            ccAlertFg = ColorProvider(Color(0xFFAEAEB2))
        )
        isMono && !isDark -> WidgetColorTokens(
            surface = ColorProvider(Color(0xFFF3F4F6)),
            surfaceMuted = ColorProvider(Color(0xFFDCDFE4)),
            textPrimary = ColorProvider(Color(0xFF1C1C1E)),
            textSecondary = ColorProvider(Color(0xFF636366)),
            heroAccent = ColorProvider(Color(0xFF1C1C1E)),
            syncBg = ColorProvider(Color(0xFFE2E4E9)),
            syncFg = ColorProvider(Color(0xFF1C1C1E)),
            sustenanceBar = ColorProvider(Color(0xFF3A3A3C)),
            overheadBar = ColorProvider(Color(0xFF636366)),
            discretionaryBar = ColorProvider(Color(0xFF8E8E93)),
            cardLimitBar = ColorProvider(Color(0xFF48484A)),
            ccPaidFg = ColorProvider(Color(0xFF2C2C2E)),
            ccAlertFg = ColorProvider(Color(0xFF48484A))
        )
        isDark -> WidgetColorTokens(
            surface = ColorProvider(Color(0xFF171229)),
            surfaceMuted = ColorProvider(Color(0xFF261E42)),
            textPrimary = ColorProvider(Color(0xFFF5F3FC)),
            textSecondary = ColorProvider(Color(0xFF9C93BA)),
            heroAccent = ColorProvider(Color(0xFFB57EE8)),
            syncBg = ColorProvider(Color(0xFF2A1F4D)),
            syncFg = ColorProvider(Color(0xFFD7B5FA)),
            sustenanceBar = ColorProvider(Color(0xFF9F5CE0)),
            overheadBar = ColorProvider(Color(0xFFFAB301)),
            discretionaryBar = ColorProvider(Color(0xFFD177B5)),
            cardLimitBar = ColorProvider(Color(0xFFFF8601)),
            ccPaidFg = ColorProvider(Color(0xFF2EE58F)),
            ccAlertFg = ColorProvider(Color(0xFFFFB429))
        )
        else -> WidgetColorTokens(
            surface = ColorProvider(Color(0xFFEEF7FF)),
            surfaceMuted = ColorProvider(Color(0xFFD6E6F7)),
            textPrimary = ColorProvider(Color(0xFF19132E)),
            textSecondary = ColorProvider(Color(0xFF625B7A)),
            heroAccent = ColorProvider(Color(0xFF6D35A3)),
            syncBg = ColorProvider(Color(0xFFE3D5F5)),
            syncFg = ColorProvider(Color(0xFF5D2A91)),
            sustenanceBar = ColorProvider(Color(0xFF8B4ABE)),
            overheadBar = ColorProvider(Color(0xFFFAB301)),
            discretionaryBar = ColorProvider(Color(0xFFA85990)),
            cardLimitBar = ColorProvider(Color(0xFFFF8601)),
            ccPaidFg = ColorProvider(Color(0xFF12945A)),
            ccAlertFg = ColorProvider(Color(0xFFE58200))
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
        val widgetStyle = prefs.getString("widget_style", "BRAND") ?: "BRAND"

        val nightFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val systemDark = nightFlags == Configuration.UI_MODE_NIGHT_YES
        val isDark = when (themeMode) {
            "DARK" -> true
            "LIGHT" -> false
            else -> systemDark
        }

        provideContent {
            GlanceTheme {
                val tokens = rememberWidgetTokens(widgetStyle = widgetStyle, isDark = isDark)
                CockpitWidgetContent(snapshot = snapshot, vaults = vaults, palette = tokens)
            }
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

private fun vaultBarColor(key: String, palette: WidgetColorTokens): ColorProvider = when (key) {
    "SUSTENANCE_FLEET" -> palette.sustenanceBar
    "FIXED_OVERHEAD" -> palette.overheadBar
    "DISCRETIONARY" -> palette.discretionaryBar
    "TRAVEL" -> palette.discretionaryBar
    "WEALTH_SURPLUS" -> palette.overheadBar
    "RENT" -> palette.sustenanceBar
    else -> palette.sustenanceBar
}

@Composable
private fun SyncPill(palette: WidgetColorTokens, compact: Boolean = false) {
    Box(
        modifier = GlanceModifier
            .background(palette.syncBg)
            .cornerRadius(8.dp)
            .padding(
                horizontal = if (compact) 6.dp else 9.dp,
                vertical = if (compact) 4.dp else 3.dp
            )
            .clickable(actionRunCallback<RefreshCockpitAction>()),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (compact) "↻" else "↻ Sync",
            style = TextStyle(
                color = palette.syncFg,
                fontSize = if (compact) 12.sp else 10.sp,
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
    palette: WidgetColorTokens
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
                    .background(palette.surface)
                    .cornerRadius(18.dp)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = "AED ${String.format("%,.0f", usableCash)}",
                        style = TextStyle(color = palette.heroAccent, fontSize = 17.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                    Text(
                        text = "AED ${String.format("%.0f", dailySafe)}/d",
                        style = TextStyle(color = palette.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
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
                    .background(palette.surface)
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
                        style = TextStyle(color = palette.heroAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        modifier = GlanceModifier.defaultWeight()
                    )
                    SyncPill(palette = palette, compact = true)
                }

                Spacer(modifier = GlanceModifier.height(4.dp))

                Text(
                    text = "USABLE CASH",
                    style = TextStyle(color = palette.textSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1
                )
                Text(
                    text = "AED ${String.format("%,.0f", usableCash)}",
                    style = TextStyle(color = palette.heroAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1
                )

                Spacer(modifier = GlanceModifier.height(6.dp))

                Text(
                    text = "DAILY (${daysLeft}D)",
                    style = TextStyle(color = palette.textSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1
                )
                Text(
                    text = "AED ${String.format("%.0f", dailySafe)}/d",
                    style = TextStyle(color = palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold),
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
                                    style = TextStyle(color = palette.textSecondary, fontSize = 9.sp, fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                    modifier = GlanceModifier.defaultWeight()
                                )
                                Text(
                                    text = String.format("%,.0f", vault.currentRemaining),
                                    style = TextStyle(color = palette.textPrimary, fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                    maxLines = 1
                                )
                            }
                            Spacer(modifier = GlanceModifier.height(2.dp))
                            LinearProgressIndicator(
                                progress = frac,
                                modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                                color = vaultBarColor(vault.vaultKey, palette),
                                backgroundColor = palette.surfaceMuted
                            )
                        }
                    }
                }
            }
        }

        // 3. MEDIUM COMPACT BAR (3x1) - Dedicated sizing so AED never truncates
        isMedium && isShort -> {
            Row(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(palette.surface)
                    .cornerRadius(18.dp)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = "USABLE CASH",
                        style = TextStyle(color = palette.textSecondary, fontSize = 8.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                    Text(
                        text = "AED ${String.format("%,.0f", usableCash)}",
                        style = TextStyle(color = palette.heroAccent, fontSize = 17.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                }

                Column(
                    horizontalAlignment = Alignment.Horizontal.End,
                    modifier = GlanceModifier.padding(end = 6.dp)
                ) {
                    Text(
                        text = "DAILY (${daysLeft}D)",
                        style = TextStyle(color = palette.textSecondary, fontSize = 8.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                    Text(
                        text = "AED ${String.format("%.0f", dailySafe)}/d",
                        style = TextStyle(color = palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                }

                SyncPill(palette = palette, compact = true)
            }
        }

        // 4. WIDE COMPACT BAR (4x1 / 5x1)
        !isNarrow && !isMedium && isShort -> {
            Row(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(palette.surface)
                    .cornerRadius(18.dp)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = "USABLE CASH",
                        style = TextStyle(color = palette.textSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                    Text(
                        text = "AED ${String.format("%,.0f", usableCash)}",
                        style = TextStyle(color = palette.heroAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                }

                Column(
                    horizontalAlignment = Alignment.Horizontal.End,
                    modifier = GlanceModifier.padding(end = 12.dp)
                ) {
                    Text(
                        text = "DAILY PACE (${daysLeft}D)",
                        style = TextStyle(color = palette.textSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                    Text(
                        text = "AED ${String.format("%.0f", dailySafe)}/day",
                        style = TextStyle(color = palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                }

                SyncPill(palette = palette, compact = true)
            }
        }

        // 5. MEDIUM (3x2 / 3x3) & WIDE (4x2 / 5x2 / 4x3) COCKPIT
        else -> {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(palette.surface)
                    .cornerRadius(22.dp)
                    .padding(if (isMedium) 13.dp else 15.dp)
                    .clickable(actionStartActivity<MainActivity>())
            ) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Text(
                        text = "● ${activeMode.first}",
                        style = TextStyle(
                            color = palette.heroAccent,
                            fontSize = if (isMedium) 9.sp else 10.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight()
                    )
                    SyncPill(palette = palette, compact = isMedium)
                }

                Spacer(modifier = GlanceModifier.height(6.dp))

                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Vertical.Bottom
                ) {
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            text = "USABLE CASH",
                            style = TextStyle(color = palette.textSecondary, fontSize = if (isMedium) 9.sp else 10.sp, fontWeight = FontWeight.Bold),
                            maxLines = 1
                        )
                        Text(
                            text = "AED ${String.format("%,.0f", usableCash)}",
                            style = TextStyle(
                                color = palette.heroAccent,
                                fontSize = if (isMedium) 20.sp else 25.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            maxLines = 1
                        )
                    }

                    Column(horizontalAlignment = Alignment.Horizontal.End) {
                        Text(
                            text = "DAILY (${daysLeft}D)",
                            style = TextStyle(color = palette.textSecondary, fontSize = if (isMedium) 9.sp else 10.sp, fontWeight = FontWeight.Bold),
                            maxLines = 1
                        )
                        Text(
                            text = if (isMedium) "AED ${String.format("%.0f", dailySafe)}/d" else "AED ${String.format("%.0f", dailySafe)}/day",
                            style = TextStyle(
                                color = palette.textPrimary,
                                fontSize = if (isMedium) 14.sp else 16.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = GlanceModifier.defaultWeight())

                if (isTall) {
                    val cashPct = if (cyclePool > 0.0) (usableCash / cyclePool).toFloat().coerceIn(0f, 1f) else 0.43f
                    Column(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        Row(modifier = GlanceModifier.fillMaxWidth()) {
                            Text(
                                text = "Cash Runway",
                                style = TextStyle(color = palette.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Medium),
                                maxLines = 1,
                                modifier = GlanceModifier.defaultWeight()
                            )
                            Text(
                                text = "${(cashPct * 100).toInt()}%",
                                style = TextStyle(color = palette.heroAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                maxLines = 1
                            )
                        }
                        Spacer(modifier = GlanceModifier.height(3.dp))
                        LinearProgressIndicator(
                            progress = cashPct,
                            modifier = GlanceModifier.fillMaxWidth().height(5.dp),
                            color = palette.heroAccent,
                            backgroundColor = palette.surfaceMuted
                        )
                    }

                    activeVaults.take(3).forEach { vault ->
                        val frac = if (vault.allocatedAmount > 0.0) (vault.currentRemaining / vault.allocatedAmount).toFloat().coerceIn(0f, 1f) else 0f
                        Column(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
                            Row(modifier = GlanceModifier.fillMaxWidth()) {
                                Text(
                                    text = if (isMedium) vaultShortName(vault.vaultKey) else vaultDisplayName(vault.vaultKey),
                                    style = TextStyle(color = palette.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                    modifier = GlanceModifier.defaultWeight()
                                )
                                Text(
                                    text = "AED ${String.format("%,.0f", vault.currentRemaining)}",
                                    style = TextStyle(color = palette.textPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                    maxLines = 1
                                )
                            }
                            Spacer(modifier = GlanceModifier.height(3.dp))
                            LinearProgressIndicator(
                                progress = frac,
                                modifier = GlanceModifier.fillMaxWidth().height(5.dp),
                                color = vaultBarColor(vault.vaultKey, palette),
                                backgroundColor = palette.surfaceMuted
                            )
                        }
                    }

                    val ccUtil = (totalCcOwed / ccLimit).toFloat().coerceIn(0f, 1f)
                    Column(modifier = GlanceModifier.fillMaxWidth()) {
                        Row(modifier = GlanceModifier.fillMaxWidth()) {
                            Text(
                                text = "Card 3445 Limit",
                                style = TextStyle(color = palette.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Medium),
                                maxLines = 1,
                                modifier = GlanceModifier.defaultWeight()
                            )
                            Text(
                                text = "AED ${String.format("%,.0f", totalCcOwed)} / 42k",
                                style = TextStyle(color = palette.ccAlertFg, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                maxLines = 1
                            )
                        }
                        Spacer(modifier = GlanceModifier.height(3.dp))
                        LinearProgressIndicator(
                            progress = ccUtil,
                            modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                            color = palette.cardLimitBar,
                            backgroundColor = palette.surfaceMuted
                        )
                    }
                } else if (isMedium) {
                    activeVaults.take(2).forEachIndexed { idx, vault ->
                        val frac = if (vault.allocatedAmount > 0.0) (vault.currentRemaining / vault.allocatedAmount).toFloat().coerceIn(0f, 1f) else 0f
                        Column(modifier = GlanceModifier.fillMaxWidth().padding(bottom = if (idx == 0) 6.dp else 0.dp)) {
                            Row(modifier = GlanceModifier.fillMaxWidth()) {
                                Text(
                                    text = vaultShortName(vault.vaultKey),
                                    style = TextStyle(color = palette.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                    modifier = GlanceModifier.defaultWeight()
                                )
                                Text(
                                    text = "AED ${String.format("%,.0f", vault.currentRemaining)}",
                                    style = TextStyle(color = palette.textPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                    maxLines = 1
                                )
                            }
                            Spacer(modifier = GlanceModifier.height(2.dp))
                            LinearProgressIndicator(
                                progress = frac,
                                modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                                color = vaultBarColor(vault.vaultKey, palette),
                                backgroundColor = palette.surfaceMuted
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
                                            style = TextStyle(color = palette.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Medium),
                                            maxLines = 1,
                                            modifier = GlanceModifier.defaultWeight()
                                        )
                                        Text(
                                            text = "AED ${String.format("%,.0f", vault.currentRemaining)}",
                                            style = TextStyle(color = palette.textPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                            maxLines = 1
                                        )
                                    }
                                    Spacer(modifier = GlanceModifier.height(3.dp))
                                    LinearProgressIndicator(
                                        progress = frac,
                                        modifier = GlanceModifier.fillMaxWidth().height(5.dp),
                                        color = vaultBarColor(vault.vaultKey, palette),
                                        backgroundColor = palette.surfaceMuted
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = GlanceModifier.defaultWeight())

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
                        style = TextStyle(color = billColor, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight()
                    )
                    Text(
                        text = if (isMedium) "Unbilled: ${String.format("%,.0f", unbilled)}" else "Unbilled: AED ${String.format("%,.0f", unbilled)}",
                        style = TextStyle(color = palette.ccAlertFg, fontSize = 10.sp, fontWeight = FontWeight.Bold),
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
