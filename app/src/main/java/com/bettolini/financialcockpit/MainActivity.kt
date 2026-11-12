package com.bettolini.financialcockpit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import com.bettolini.financialcockpit.data.AppDatabase
import com.bettolini.financialcockpit.data.SmsHistoryScanner
import com.bettolini.financialcockpit.data.TransactionEntity
import com.bettolini.financialcockpit.data.VaultBalanceEntity
import com.bettolini.financialcockpit.domain.DynamicAllocationEngine
import com.bettolini.financialcockpit.ui.theme.*
import com.bettolini.financialcockpit.widget.CockpitWidget
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AppDatabase.getInstance(this).financeDao()
        val prefs = getSharedPreferences("cockpit_ui_prefs", Context.MODE_PRIVATE)

        setContent {
            val context = LocalContext.current
            val coroutineScope = rememberCoroutineScope()

            // Theme preference: "SYSTEM", "LIGHT", "DARK"
            var themeMode by remember {
                mutableStateOf(prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM")
            }
            var widgetMono by remember {
                mutableStateOf(prefs.getBoolean("widget_mono", false))
            }
            val systemDark = isSystemInDarkTheme()
            val isDark = when (themeMode) {
                "DARK" -> true
                "LIGHT" -> false
                else -> systemDark
            }
            val palette = if (isDark) DarkCockpitColors else LightCockpitColors

            var hasSmsPermission by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
                )
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                hasSmsPermission = permissions[Manifest.permission.READ_SMS] == true
                if (hasSmsPermission) {
                    coroutineScope.launch {
                        val count = SmsHistoryScanner.scanAndSyncInbox(context)
                        CockpitWidget().updateAll(context)
                        Toast.makeText(context, "Synced $count ENBD alerts", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            ProvideCockpitColors(palette) {
                MaterialTheme(colorScheme = if (isDark) CockpitDarkColorScheme else CockpitLightColorScheme) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = palette.background
                    ) {
                        val vaults by dao.getAllVaults().collectAsState(initial = emptyList())
                        val snapshot by dao.getSnapshot().collectAsState(initial = null)
                        val recentTxns by dao.getRecentTransactions().collectAsState(initial = emptyList())

                        val today = LocalDate.now()
                        val payday = if (today.dayOfMonth < 22) today.withDayOfMonth(22) else today.plusMonths(1).withDayOfMonth(22)
                        val daysUntilPayday = ChronoUnit.DAYS.between(today, payday).coerceAtLeast(1)

                        val checkingCash = snapshot?.liveDebitBalance ?: 2737.93
                        val currentStatementDue = snapshot?.statementDue ?: 0.0
                        val dueDateLabel = snapshot?.statementDueDate ?: "19 Nov"
                        val unbilledVal = snapshot?.unbilledSpend ?: 4987.45
                        val ccTags = snapshot?.ccSubTagsSummary ?: ""
                        val totalCcOwed = snapshot?.creditTotalOwed ?: (currentStatementDue + unbilledVal)
                        val ccLimit = snapshot?.creditCardLimit ?: 42000.0
                        val avlLimit = snapshot?.creditAvailableLimit ?: (ccLimit - totalCcOwed)
                        val cycleLabel = snapshot?.cycleLabel ?: "22 Oct – 21 Nov"
                        val cyclePool = snapshot?.cycleStartingPool ?: 6380.0

                        val usableCashToday = (checkingCash - currentStatementDue).coerceAtLeast(0.0)
                        val safeDaily = (usableCashToday / daysUntilPayday.toDouble()).coerceAtLeast(0.0)

                        var simulatedModeOverride by remember { mutableStateOf<Pair<String, String>?>(null) }
                        val liveMode = DynamicAllocationEngine.getLiveModeBadge(usableCashToday)
                        val activeMode = simulatedModeOverride ?: liveMode

                        val expectedSalary = 9750.00
                        val autoLoanEmi = 1594.00
                        val nextMonthCcCommitment = if (unbilledVal > 1000.0) 147.00 else unbilledVal
                        val projectedNextMonthBudget = (usableCashToday + expectedSalary - autoLoanEmi - nextMonthCcCommitment).coerceAtLeast(0.0)

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 18.dp),
                            contentPadding = PaddingValues(top = 20.dp, bottom = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // 1. Minimalist Top Bar (Title + Theme Toggle + Sync)
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "Cockpit",
                                            fontSize = 26.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = (-0.5).sp,
                                            color = palette.textPrimary
                                        )
                                        Text(
                                            text = cycleLabel,
                                            fontSize = 12.sp,
                                            color = palette.textSecondary
                                        )
                                    }

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Widget Color/Mono Pill
                                        Surface(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable {
                                                    val nextMono = !widgetMono
                                                    widgetMono = nextMono
                                                    prefs.edit().putBoolean("widget_mono", nextMono).apply()
                                                    coroutineScope.launch { CockpitWidget().updateAll(context) }
                                                },
                                            shape = RoundedCornerShape(12.dp),
                                            color = palette.surface,
                                            border = BorderStroke(1.dp, palette.border)
                                        ) {
                                            Text(
                                                text = if (widgetMono) "◈ Mono" else "◈ Color",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = palette.textPrimary,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)
                                            )
                                        }

                                        // Theme Cycle Pill (Auto -> Light -> Dark)
                                        Surface(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable {
                                                    val next = when (themeMode) {
                                                        "SYSTEM" -> "LIGHT"
                                                        "LIGHT" -> "DARK"
                                                        else -> "SYSTEM"
                                                    }
                                                    themeMode = next
                                                    prefs.edit().putString("theme_mode", next).apply()
                                                    coroutineScope.launch { CockpitWidget().updateAll(context) }
                                                },
                                            shape = RoundedCornerShape(12.dp),
                                            color = palette.surface,
                                            border = BorderStroke(1.dp, palette.border)
                                        ) {
                                            val modeLabel = when (themeMode) {
                                                "LIGHT" -> "☀ Light"
                                                "DARK" -> "☾ Dark"
                                                else -> "◐ Auto"
                                            }
                                            Text(
                                                text = modeLabel,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = palette.textPrimary,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                                            )
                                        }

                                        // Sync Button
                                        Surface(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable {
                                                    simulatedModeOverride = null
                                                    if (hasSmsPermission) {
                                                        coroutineScope.launch {
                                                            val count = SmsHistoryScanner.scanAndSyncInbox(context)
                                                            CockpitWidget().updateAll(context)
                                                            Toast.makeText(context, "Synced $count alerts", Toast.LENGTH_SHORT).show()
                                                        }
                                                    } else {
                                                        permissionLauncher.launch(
                                                            arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
                                                        )
                                                    }
                                                },
                                            shape = RoundedCornerShape(12.dp),
                                            color = palette.primaryAccent
                                        ) {
                                            Text(
                                                text = "↻ Sync",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // 2. Signature Royal Violet Gradient Hero Card
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(24.dp))
                                        .background(palette.heroGradient)
                                        .padding(22.dp)
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "TRUE USABLE CASH",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                letterSpacing = 1.sp,
                                                color = Color.White.copy(alpha = 0.78f)
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(20.dp),
                                                color = Color.White.copy(alpha = 0.16f)
                                            ) {
                                                Text(
                                                    text = activeMode.first,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    letterSpacing = 0.6.sp,
                                                    color = Color.White,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))

                                        Text(
                                            text = "AED ${String.format("%,.2f", usableCashToday)}",
                                            fontSize = 36.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = (-1).sp,
                                            color = Color.White
                                        )

                                        Spacer(modifier = Modifier.height(4.dp))

                                        Text(
                                            text = if (currentStatementDue <= 0.01) {
                                                "Checking AED ${String.format("%,.2f", checkingCash)} • CC Bill Settled"
                                            } else {
                                                "Checking AED ${String.format("%,.2f", checkingCash)} − Due AED ${String.format("%,.2f", currentStatementDue)}"
                                            },
                                            fontSize = 12.sp,
                                            color = Color.White.copy(alpha = 0.75f)
                                        )

                                        Spacer(modifier = Modifier.height(18.dp))
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(1.dp)
                                                .background(Color.White.copy(alpha = 0.16f))
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text(
                                                    text = "Safe Daily Pace",
                                                    fontSize = 11.sp,
                                                    color = Color.White.copy(alpha = 0.75f)
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = "AED ${String.format("%.0f", safeDaily)} / day",
                                                    fontSize = 19.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = GoldTop
                                                )
                                            }

                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    text = "Runway to 22nd",
                                                    fontSize = 11.sp,
                                                    color = Color.White.copy(alpha = 0.75f)
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = "$daysUntilPayday days left",
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 3. Skywards Signature Card (Clean Minimalist Surface + Amber Accent)
                            item {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(22.dp),
                                    color = palette.surface,
                                    border = BorderStroke(1.dp, palette.border)
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .background(AmberTop, CircleShape)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Skywards Signature • 3445",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = palette.textPrimary
                                                )
                                            }
                                            Text(
                                                text = "Avl AED ${String.format("%,.0f", avlLimit)}",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = palette.textSecondary
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(16.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column {
                                                Text(
                                                    text = if (currentStatementDue <= 0.01) "Statement ($dueDateLabel)" else "Billed Due ($dueDateLabel)",
                                                    fontSize = 11.sp,
                                                    color = palette.textSecondary
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = if (currentStatementDue <= 0.01) "Paid ✓" else "AED ${String.format("%,.2f", currentStatementDue)}",
                                                    fontSize = 21.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (currentStatementDue <= 0.01) palette.positiveGreen else palette.amberAccent
                                                )
                                            }

                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    text = "Unbilled Balance",
                                                    fontSize = 11.sp,
                                                    color = palette.textSecondary
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = "AED ${String.format("%,.2f", unbilledVal)}",
                                                    fontSize = 21.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = palette.textPrimary
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(12.dp))

                                        // Subtle Credit Utilization Bar
                                        val ccProgress = (totalCcOwed / ccLimit).toFloat().coerceIn(0f, 1f)
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(CircleShape)
                                                .background(palette.surfaceMuted)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth(ccProgress)
                                                    .fillMaxHeight()
                                                    .clip(CircleShape)
                                                    .background(palette.amberGradient)
                                            )
                                        }

                                        if (ccTags.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Text(
                                                text = ccTags,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = palette.amberAccent,
                                                lineHeight = 16.sp
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(14.dp))
                                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.border))
                                        Spacer(modifier = Modifier.height(14.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text("22nd Liquid Forecast", fontSize = 11.sp, color = palette.textSecondary)
                                                Text(
                                                    text = "AED ${String.format("%,.0f", projectedNextMonthBudget)}",
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = palette.primaryAccent
                                                )
                                            }
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text("Total Card Debt", fontSize = 11.sp, color = palette.textSecondary)
                                                Text(
                                                    text = "AED ${String.format("%,.2f", totalCcOwed)}",
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = palette.textPrimary
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 4. Vault Allocation Donut + Payday Simulator Pills
                            item {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(22.dp),
                                    color = palette.surface,
                                    border = BorderStroke(1.dp, palette.border)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Cash Vault Allocation",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = palette.textPrimary
                                            )
                                            if (cyclePool > 0.0) {
                                                Text(
                                                    text = "Pool AED ${String.format("%,.0f", cyclePool)}",
                                                    fontSize = 11.sp,
                                                    color = palette.textSecondary
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(20.dp))
                                        DonutChart(vaults = vaults, modifier = Modifier.size(176.dp))
                                        Spacer(modifier = Modifier.height(20.dp))
                                        DonutLegend(vaults = vaults)

                                        Spacer(modifier = Modifier.height(18.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Surface(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable {
                                                        coroutineScope.launch {
                                                            val alloc = DynamicAllocationEngine.calculateDynamicSplit(9750.0)
                                                            simulatedModeOverride = alloc.allocationMode to alloc.modeSubtitle
                                                            dao.updateVaultBalances(listOf(
                                                                VaultBalanceEntity("RENT", alloc.rentVault, 0.0, alloc.rentVault),
                                                                VaultBalanceEntity("FIXED_OVERHEAD", alloc.fixedOverheadVault, 0.0, alloc.fixedOverheadVault),
                                                                VaultBalanceEntity("SUSTENANCE_FLEET", alloc.sustenanceFleetVault, 0.0, alloc.sustenanceFleetVault),
                                                                VaultBalanceEntity("DISCRETIONARY", alloc.discretionaryVault, 0.0, alloc.discretionaryVault),
                                                                VaultBalanceEntity("TRAVEL", alloc.travelVault, 0.0, alloc.travelVault),
                                                                VaultBalanceEntity("WEALTH_SURPLUS", alloc.wealthSurplusVault, 0.0, alloc.wealthSurplusVault)
                                                            ))
                                                        }
                                                    },
                                                shape = RoundedCornerShape(12.dp),
                                                color = palette.surfaceMuted
                                            ) {
                                                Box(
                                                    modifier = Modifier.padding(vertical = 11.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = "Sim 9,750 Payday",
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = palette.textPrimary
                                                    )
                                                }
                                            }

                                            Surface(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable {
                                                        coroutineScope.launch {
                                                            val alloc = DynamicAllocationEngine.calculateDynamicSplit(17000.0)
                                                            simulatedModeOverride = alloc.allocationMode to alloc.modeSubtitle
                                                            dao.updateVaultBalances(listOf(
                                                                VaultBalanceEntity("RENT", alloc.rentVault, 0.0, alloc.rentVault),
                                                                VaultBalanceEntity("FIXED_OVERHEAD", alloc.fixedOverheadVault, 0.0, alloc.fixedOverheadVault),
                                                                VaultBalanceEntity("SUSTENANCE_FLEET", alloc.sustenanceFleetVault, 0.0, alloc.sustenanceFleetVault),
                                                                VaultBalanceEntity("DISCRETIONARY", alloc.discretionaryVault, 0.0, alloc.discretionaryVault),
                                                                VaultBalanceEntity("TRAVEL", alloc.travelVault, 0.0, alloc.travelVault),
                                                                VaultBalanceEntity("WEALTH_SURPLUS", alloc.wealthSurplusVault, 0.0, alloc.wealthSurplusVault)
                                                            ))
                                                        }
                                                    },
                                                shape = RoundedCornerShape(12.dp),
                                                color = palette.primaryAccent
                                            ) {
                                                Box(
                                                    modifier = Modifier.padding(vertical = 11.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = "Sim 17,000 Raise",
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // 5. Vault Ledger Header
                            item {
                                Text(
                                    text = "Vault Ledger",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.textPrimary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }

                            items(vaults) { vault ->
                                VaultItemCard(vault)
                            }

                            // 6. Recent ENBD Activity
                            if (recentTxns.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "Recent ENBD Alerts",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.textPrimary,
                                        modifier = Modifier.padding(top = 6.dp)
                                    )
                                }
                                items(recentTxns) { txn ->
                                    TransactionRowCard(txn)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatVaultLabel(key: String): String = when (key) {
    "SUSTENANCE_FLEET" -> "Sustenance & Fleet"
    "FIXED_OVERHEAD" -> "Fixed Overhead"
    "DISCRETIONARY" -> "Discretionary"
    "WEALTH_SURPLUS" -> "Wealth Surplus"
    "TRAVEL" -> "Travel"
    "RENT" -> "Rent"
    else -> key.replace("_", " ")
}

@Composable
fun DonutChart(vaults: List<VaultBalanceEntity>, modifier: Modifier = Modifier) {
    val palette = LocalCockpitColors.current
    val positiveVaults = vaults.filter { it.currentRemaining > 0.0 }
    val totalPositive = positiveVaults.sumOf { it.currentRemaining }.toFloat()

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 20.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
            val arcSize = Size(diameter, diameter)

            if (totalPositive <= 0f) {
                drawArc(
                    color = palette.surfaceMuted,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth)
                )
            } else {
                var currentAngle = -90f
                positiveVaults.forEach { vault ->
                    val sweep = (vault.currentRemaining.toFloat() / totalPositive) * 360f
                    if (sweep > 0f) {
                        drawArc(
                            color = getVaultSolidColor(vault.vaultKey, palette.isDark),
                            startAngle = currentAngle,
                            sweepAngle = (sweep - 3f).coerceAtLeast(1f),
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )
                        currentAngle += sweep
                    }
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "AED ${String.format("%,.2f", totalPositive)}",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary
            )
            Text(
                text = "Usable Cash",
                fontSize = 11.sp,
                color = palette.textSecondary
            )
        }
    }
}

@Composable
fun DonutLegend(vaults: List<VaultBalanceEntity>) {
    val palette = LocalCockpitColors.current
    val chunked = vaults.chunked(3)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chunked.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { vault ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(getVaultSolidColor(vault.vaultKey, palette.isDark), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = formatVaultLabel(vault.vaultKey),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.textSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VaultItemCard(vault: VaultBalanceEntity) {
    val palette = LocalCockpitColors.current
    val gradPair = getVaultPalettePair(vault.vaultKey)
    val solidAccent = getVaultSolidColor(vault.vaultKey, palette.isDark)
    val progress = if (vault.allocatedAmount > 0) (vault.currentRemaining / vault.allocatedAmount).toFloat() else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(600),
        label = "vault_progress"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.border)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(Brush.linearGradient(listOf(gradPair.first, gradPair.second)), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = formatVaultLabel(vault.vaultKey),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary
                    )
                }

                Text(
                    text = "AED ${String.format("%,.2f", vault.currentRemaining)}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (vault.currentRemaining > 0) palette.textPrimary else palette.textSecondary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Gradient Progress Pill
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(CircleShape)
                    .background(palette.surfaceMuted)
            ) {
                if (animatedProgress > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedProgress)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(Brush.horizontalGradient(listOf(gradPair.first, gradPair.second)))
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Target AED ${String.format("%.0f", vault.allocatedAmount)}  •  Spent AED ${String.format("%.0f", vault.spentAmount)}",
                    fontSize = 11.sp,
                    color = palette.textSecondary
                )
                Text(
                    text = "${(progress * 100).toInt()}% left",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary
                )
            }

            if (vault.topSubTagsSummary.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = vault.topSubTagsSummary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = solidAccent
                )
            }
        }
    }
}

@Composable
fun TransactionRowCard(txn: TransactionEntity) {
    val palette = LocalCockpitColors.current
    var expanded by remember { mutableStateOf(false) }
    val formatter = remember { DateTimeFormatter.ofPattern("dd MMM, HH:mm").withZone(ZoneId.systemDefault()) }
    val formattedDate = formatter.format(Instant.ofEpochMilli(txn.timestamp))
    val tagColor = if (txn.isInternalTransfer) palette.positiveGreen else getVaultSolidColor(txn.deducedVault, palette.isDark)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.border)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = txn.merchant,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary
                    )
                    Spacer(modifier = Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${if (txn.isCreditCard) "Card 3445" else "Checking"} • $formattedDate",
                            fontSize = 11.sp,
                            color = palette.textSecondary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = tagColor.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = txn.subTag,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = tagColor,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text = "AED ${String.format("%,.2f", txn.amount)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (txn.isInternalTransfer) palette.positiveGreen else palette.textPrimary
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.border))
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = txn.rawSms,
                    fontSize = 11.sp,
                    color = palette.textSecondary,
                    lineHeight = 16.sp
                )
            }
        }
    }
}
