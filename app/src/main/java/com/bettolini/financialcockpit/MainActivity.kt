package com.bettolini.financialcockpit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.bettolini.financialcockpit.data.*
import com.bettolini.financialcockpit.domain.DynamicAllocationEngine
import com.bettolini.financialcockpit.ui.theme.*
import com.bettolini.financialcockpit.widget.CockpitWidget
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private enum class ActiveScreen {
    DASHBOARD,
    DEBIT_ANALYTICS,
    CREDIT_ANALYTICS,
    VAULT_LEDGER,
    VAULT_DETAIL
}

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AppDatabase.getInstance(this).financeDao()
        val prefs = getSharedPreferences("cockpit_ui_prefs", Context.MODE_PRIVATE)

        setContent {
            val context = LocalContext.current
            val coroutineScope = rememberCoroutineScope()

            var themeMode by remember {
                mutableStateOf(prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM")
            }
            var widgetStyle by remember {
                mutableStateOf(prefs.getString("widget_style", "BRAND") ?: "BRAND")
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
                        CockpitWidget.forceUpdateAll(context)
                        Toast.makeText(context, "Synced $count ENBD alerts", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            val vaults by dao.getAllVaults().collectAsState(initial = emptyList())
            val snapshot by dao.getSnapshot().collectAsState(initial = null)
            val recentTxns by dao.getRecentTransactions().collectAsState(initial = emptyList())
            val cycleSnapshots by dao.getAllCycleSnapshots().collectAsState(initial = emptyList())
            val merchantRules by dao.getAllMerchantRules().collectAsState(initial = emptyList())

            LaunchedEffect(hasSmsPermission, snapshot) {
                if (hasSmsPermission && snapshot == null) {
                    SmsHistoryScanner.scanAndSyncInbox(context)
                    CockpitWidget.forceUpdateAll(context)
                }
            }

            // Navigation & Modal State
            var currentScreen by remember { mutableStateOf(ActiveScreen.DASHBOARD) }
            var selectedVaultKey by remember { mutableStateOf<String?>(null) }
            var showSettingsSheet by remember { mutableStateOf(false) }
            var showCycleHistorySheet by remember { mutableStateOf(false) }
            var selectedTxnForEdit by remember { mutableStateOf<TransactionEntity?>(null) }

            BackHandler(enabled = currentScreen != ActiveScreen.DASHBOARD) {
                if (currentScreen == ActiveScreen.VAULT_DETAIL) {
                    currentScreen = ActiveScreen.VAULT_LEDGER
                    selectedVaultKey = null
                } else {
                    currentScreen = ActiveScreen.DASHBOARD
                    selectedVaultKey = null
                }
            }

            ProvideCockpitColors(palette) {
                MaterialTheme(colorScheme = if (isDark) CockpitDarkColorScheme else CockpitLightColorScheme) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = palette.background
                    ) {
                        val today = LocalDate.now()
                        val payday = if (today.dayOfMonth < 22) today.withDayOfMonth(22) else today.plusMonths(1).withDayOfMonth(22)
                        val daysUntilPayday = ChronoUnit.DAYS.between(today, payday).coerceAtLeast(1)

                        val checkingCash = snapshot?.liveDebitBalance ?: 2737.93
                        val currentStatementDue = snapshot?.statementDue ?: 0.0
                        val dueDateLabel = snapshot?.statementDueDate ?: "19 Nov"
                        val unbilledVal = snapshot?.unbilledSpend ?: 4987.45
                        val totalCcOwed = snapshot?.creditTotalOwed ?: (currentStatementDue + unbilledVal)
                        val ccLimit = snapshot?.creditCardLimit ?: 42000.0
                        val avlLimit = snapshot?.creditAvailableLimit ?: (ccLimit - totalCcOwed)
                        val cycleLabel = snapshot?.cycleLabel ?: "Cycle: 22 Oct – 21 Nov"
                        val cyclePool = snapshot?.cycleStartingPool ?: 6380.0

                        val usableCashToday = (checkingCash - currentStatementDue).coerceAtLeast(0.0)
                        val safeDaily = (usableCashToday / daysUntilPayday.toDouble()).coerceAtLeast(0.0)
                        val utilizationPct = ((totalCcOwed / ccLimit) * 100).toInt()

                        var simulatedModeOverride by remember { mutableStateOf<Pair<String, String>?>(null) }
                        val liveMode = DynamicAllocationEngine.getLiveModeBadge(usableCashToday)
                        val activeMode = simulatedModeOverride ?: liveMode

                        val expectedSalary = 9750.00
                        val autoLoanEmi = 1594.00
                        val nextMonthCcCommitment = if (unbilledVal > 1000.0) 147.00 else unbilledVal
                        val projectedNextMonthBudget = (usableCashToday + expectedSalary - autoLoanEmi - nextMonthCcCommitment).coerceAtLeast(0.0)

                        AnimatedContent(
                            targetState = currentScreen,
                            transitionSpec = {
                                (fadeIn(tween(240)) + slideInVertically(tween(260)) { it / 18 })
                                    .togetherWith(fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it / 24 })
                            },
                            label = "screen_transition"
                        ) { screen ->
                            when (screen) {
                                ActiveScreen.DEBIT_ANALYTICS -> {
                                    AccountAnalyticsScreen(
                                        isCreditCard = false,
                                        title = "Checking • 8691",
                                        cycleLabel = cycleLabel,
                                        allTransactions = recentTxns,
                                        primaryMetricLabel = "CURRENT CYCLE DEBIT SPEND",
                                        allTimeMetricLabel = "TOTAL SYNCED DEBIT SPEND",
                                        heroGradient = palette.heroGradient,
                                        leftStatLabel = "Usable Cash",
                                        leftStatValue = "AED ${String.format("%,.2f", usableCashToday)}",
                                        leftStatAccent = GoldTop,
                                        midStatLabel = "Safe Daily Pace",
                                        midStatValue = "AED ${String.format("%.0f", safeDaily)}/d",
                                        rightStatLabel = "Runway",
                                        rightStatValue = "${daysUntilPayday}d left",
                                        onBack = { currentScreen = ActiveScreen.DASHBOARD },
                                        onSelectTransaction = { selectedTxnForEdit = it }
                                    )
                                }

                                ActiveScreen.CREDIT_ANALYTICS -> {
                                    AccountAnalyticsScreen(
                                        isCreditCard = true,
                                        title = "Card 3445",
                                        cycleLabel = cycleLabel,
                                        allTransactions = recentTxns,
                                        primaryMetricLabel = "CURRENT CYCLE CARD SPEND",
                                        allTimeMetricLabel = "TOTAL SYNCED CARD SPEND",
                                        heroGradient = Brush.linearGradient(listOf(PlumTop, VioletBot)),
                                        leftStatLabel = "Billed ($dueDateLabel)",
                                        leftStatValue = if (currentStatementDue <= 0.01) "Paid ✓" else "AED ${String.format("%,.2f", currentStatementDue)}",
                                        leftStatAccent = GoldTop,
                                        midStatLabel = "Unbilled",
                                        midStatValue = "AED ${String.format("%,.2f", unbilledVal)}",
                                        rightStatLabel = "Utilization",
                                        rightStatValue = "$utilizationPct%",
                                        onBack = { currentScreen = ActiveScreen.DASHBOARD },
                                        onSelectTransaction = { selectedTxnForEdit = it }
                                    )
                                }

                                ActiveScreen.VAULT_LEDGER -> {
                                    VaultLedgerScreen(
                                        vaults = vaults,
                                        cyclePool = cyclePool,
                                        cycleLabel = cycleLabel,
                                        activeMode = activeMode.first,
                                        onBack = { currentScreen = ActiveScreen.DASHBOARD },
                                        onSelectVault = { vaultKey ->
                                            selectedVaultKey = vaultKey
                                            currentScreen = ActiveScreen.VAULT_DETAIL
                                        }
                                    )
                                }

                                ActiveScreen.VAULT_DETAIL -> {
                                    val activeVault = vaults.find { it.vaultKey == selectedVaultKey }
                                    if (activeVault != null) {
                                        VaultDetailScreen(
                                            vault = activeVault,
                                            allTransactions = recentTxns,
                                            onBack = {
                                                currentScreen = ActiveScreen.VAULT_LEDGER
                                                selectedVaultKey = null
                                            },
                                            onSelectTransaction = { selectedTxnForEdit = it }
                                        )
                                    } else {
                                        currentScreen = ActiveScreen.VAULT_LEDGER
                                    }
                                }

                                ActiveScreen.DASHBOARD -> {
                                    val accountPagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })

                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 18.dp),
                                        contentPadding = PaddingValues(top = 20.dp, bottom = 32.dp),
                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        // 1. Minimalist Top Bar (Title + Cycles + Settings Gear + Sync)
                                        item {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    Text(
                                                        text = "Cockpit",
                                                        fontSize = 24.sp,
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
                                                    Surface(
                                                        modifier = Modifier
                                                            .height(36.dp)
                                                            .clip(RoundedCornerShape(12.dp))
                                                            .clickable { showCycleHistorySheet = true },
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = palette.surface,
                                                        border = BorderStroke(1.dp, palette.border)
                                                    ) {
                                                        Box(
                                                            modifier = Modifier.padding(horizontal = 12.dp),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Text(
                                                                text = "Cycles",
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.SemiBold,
                                                                color = palette.textPrimary
                                                            )
                                                        }
                                                    }

                                                    Surface(
                                                        modifier = Modifier
                                                            .size(36.dp)
                                                            .clip(RoundedCornerShape(12.dp))
                                                            .clickable { showSettingsSheet = true },
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = palette.surface,
                                                        border = BorderStroke(1.dp, palette.border)
                                                    ) {
                                                        Box(contentAlignment = Alignment.Center) {
                                                            Icon(
                                                                imageVector = Icons.Outlined.Settings,
                                                                contentDescription = "Settings",
                                                                tint = palette.textPrimary,
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                        }
                                                    }

                                                    Surface(
                                                        modifier = Modifier
                                                            .height(36.dp)
                                                            .clip(RoundedCornerShape(12.dp))
                                                            .clickable {
                                                                simulatedModeOverride = null
                                                                if (hasSmsPermission) {
                                                                    coroutineScope.launch {
                                                                        val count = SmsHistoryScanner.scanAndSyncInbox(context)
                                                                        CockpitWidget.forceUpdateAll(context)
                                                                        Toast.makeText(context, "Synced $count alerts", Toast.LENGTH_SHORT).show()
                                                                    }
                                                                } else {
                                                                    val perms = mutableListOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
                                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                                        perms.add(Manifest.permission.POST_NOTIFICATIONS)
                                                                    }
                                                                    permissionLauncher.launch(perms.toTypedArray())
                                                                }
                                                            },
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = palette.primaryAccent
                                                    ) {
                                                        Box(
                                                            modifier = Modifier.padding(horizontal = 13.dp),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Text(
                                                                text = "↻ Sync",
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = Color.White
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        // 2. Unified Swipeable Debit / Credit Hero Carousel
                                        item {
                                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                // Active Account Header + Interactive Page Dots
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 2.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.clickable {
                                                            val target = if (accountPagerState.currentPage == 0) 1 else 0
                                                            coroutineScope.launch {
                                                                accountPagerState.animateScrollToPage(target)
                                                            }
                                                        }
                                                    ) {
                                                        Text(
                                                            text = if (accountPagerState.currentPage == 0) "Checking Account • " else "Skywards Signature • ",
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = palette.textPrimary
                                                        )
                                                        Text(
                                                            text = if (accountPagerState.currentPage == 0) "DEBIT" else "CREDIT",
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            letterSpacing = 0.8.sp,
                                                            color = if (accountPagerState.currentPage == 0) palette.primaryAccent else palette.amberAccent
                                                        )
                                                    }

                                                    // Smooth Animated Indicator Dots
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        repeat(2) { idx ->
                                                            val isSelected = accountPagerState.currentPage == idx
                                                            val dotWidth by animateDpAsState(
                                                                targetValue = if (isSelected) 18.dp else 7.dp,
                                                                animationSpec = tween(250),
                                                                label = "pager_dot"
                                                            )
                                                            Box(
                                                                modifier = Modifier
                                                                    .height(7.dp)
                                                                    .width(dotWidth)
                                                                    .clip(CircleShape)
                                                                    .background(
                                                                        if (isSelected) palette.primaryAccent
                                                                        else palette.textSecondary.copy(alpha = 0.35f)
                                                                    )
                                                                    .clickable {
                                                                        coroutineScope.launch {
                                                                            accountPagerState.animateScrollToPage(idx)
                                                                        }
                                                                    }
                                                            )
                                                        }
                                                    }
                                                }

                                                // Horizontal Swipe Carousel (Same exact dimensions for both cards)
                                                HorizontalPager(
                                                    state = accountPagerState,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    pageSpacing = 12.dp
                                                ) { page ->
                                                    if (page == 0) {
                                                        // PAGE 0: DEBIT / TRUE USABLE CASH CARD
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .height(216.dp)
                                                                .clip(RoundedCornerShape(24.dp))
                                                                .background(palette.heroGradient)
                                                                .clickable { currentScreen = ActiveScreen.DEBIT_ANALYTICS }
                                                                .padding(22.dp)
                                                        ) {
                                                            Column(
                                                                modifier = Modifier.fillMaxSize(),
                                                                verticalArrangement = Arrangement.SpaceBetween
                                                            ) {
                                                                Column {
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
                                                                        fontSize = 35.sp,
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
                                                                }

                                                                Column {
                                                                    Box(
                                                                        modifier = Modifier
                                                                            .fillMaxWidth()
                                                                            .height(1.dp)
                                                                            .background(Color.White.copy(alpha = 0.16f))
                                                                    )
                                                                    Spacer(modifier = Modifier.height(14.dp))

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
                                                                                fontSize = 18.sp,
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
                                                    } else {
                                                        // PAGE 1: CREDIT CARD / SKYWARDS SIGNATURE 3445 CARD
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .height(216.dp)
                                                                .clip(RoundedCornerShape(24.dp))
                                                                .background(Brush.linearGradient(listOf(PlumTop, VioletBot)))
                                                                .clickable { currentScreen = ActiveScreen.CREDIT_ANALYTICS }
                                                                .padding(22.dp)
                                                        ) {
                                                            Column(
                                                                modifier = Modifier.fillMaxSize(),
                                                                verticalArrangement = Arrangement.SpaceBetween
                                                            ) {
                                                                Column {
                                                                    Row(
                                                                        modifier = Modifier.fillMaxWidth(),
                                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                                        verticalAlignment = Alignment.CenterVertically
                                                                    ) {
                                                                        Text(
                                                                            text = "UNBILLED BALANCE",
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
                                                                                text = "CARD 3445 • $utilizationPct% USED",
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
                                                                        text = "AED ${String.format("%,.2f", unbilledVal)}",
                                                                        fontSize = 35.sp,
                                                                        fontWeight = FontWeight.Bold,
                                                                        letterSpacing = (-1).sp,
                                                                        color = Color.White
                                                                    )

                                                                    Spacer(modifier = Modifier.height(4.dp))

                                                                    Text(
                                                                        text = if (currentStatementDue <= 0.01) {
                                                                            "Statement ($dueDateLabel): Paid ✓"
                                                                        } else {
                                                                            "Billed Due ($dueDateLabel): AED ${String.format("%,.2f", currentStatementDue)}"
                                                                        },
                                                                        fontSize = 12.sp,
                                                                        color = if (currentStatementDue <= 0.01) GoldTop else Color.White.copy(alpha = 0.85f)
                                                                    )
                                                                }

                                                                Column {
                                                                    Box(
                                                                        modifier = Modifier
                                                                            .fillMaxWidth()
                                                                            .height(1.dp)
                                                                            .background(Color.White.copy(alpha = 0.16f))
                                                                    )
                                                                    Spacer(modifier = Modifier.height(14.dp))

                                                                    Row(
                                                                        modifier = Modifier.fillMaxWidth(),
                                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                                        verticalAlignment = Alignment.CenterVertically
                                                                    ) {
                                                                        Column {
                                                                            Text(
                                                                                text = "22nd Liquid Forecast",
                                                                                fontSize = 11.sp,
                                                                                color = Color.White.copy(alpha = 0.75f)
                                                                            )
                                                                            Spacer(modifier = Modifier.height(2.dp))
                                                                            Text(
                                                                                text = "AED ${String.format("%,.0f", projectedNextMonthBudget)}",
                                                                                fontSize = 18.sp,
                                                                                fontWeight = FontWeight.Bold,
                                                                                color = GoldTop
                                                                            )
                                                                        }

                                                                        Column(horizontalAlignment = Alignment.End) {
                                                                            Text(
                                                                                text = "Available Limit",
                                                                                fontSize = 11.sp,
                                                                                color = Color.White.copy(alpha = 0.75f)
                                                                            )
                                                                            Spacer(modifier = Modifier.height(2.dp))
                                                                            Text(
                                                                                text = "AED ${String.format("%,.0f", avlLimit)} / 42k",
                                                                                fontSize = 15.sp,
                                                                                fontWeight = FontWeight.SemiBold,
                                                                                color = Color.White
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        // 3. Vault Ledger Pie Chart Card (Clickable -> Vault Ledger Bar Chart Breakdown)
                                        item {
                                            Surface(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(22.dp))
                                                    .clickable { currentScreen = ActiveScreen.VAULT_LEDGER },
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
                                                            text = "Vault Ledger",
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = palette.textPrimary
                                                        )
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            if (cyclePool > 0.0) {
                                                                Text(
                                                                    text = "Pool AED ${String.format("%,.0f", cyclePool)}",
                                                                    fontSize = 11.sp,
                                                                    color = palette.textSecondary
                                                                )
                                                                Spacer(modifier = Modifier.width(8.dp))
                                                            }
                                                            Text(
                                                                text = "›",
                                                                fontSize = 18.sp,
                                                                fontWeight = FontWeight.SemiBold,
                                                                color = palette.textSecondary
                                                            )
                                                        }
                                                    }

                                                    Spacer(modifier = Modifier.height(18.dp))
                                                    DonutChart(vaults = vaults, modifier = Modifier.size(172.dp))
                                                    Spacer(modifier = Modifier.height(18.dp))
                                                    DonutLegend(vaults = vaults)
                                                }
                                            }
                                        }

                                        // 4. Recent Alerts directly below Vault Ledger
                                        if (recentTxns.isNotEmpty()) {
                                            item {
                                                Text(
                                                    text = "Recent Alerts",
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = palette.textPrimary,
                                                    modifier = Modifier.padding(top = 4.dp)
                                                )
                                            }

                                            items(recentTxns, key = { it.id }) { txn ->
                                                TransactionRowCard(
                                                    txn = txn,
                                                    onClick = { selectedTxnForEdit = txn }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Settings Bottom Sheet
                        if (showSettingsSheet) {
                            SettingsBottomSheet(
                                themeMode = themeMode,
                                widgetStyle = widgetStyle,
                                isSimulationActive = simulatedModeOverride != null,
                                onSelectTheme = { next ->
                                    themeMode = next
                                    prefs.edit().putString("theme_mode", next).commit()
                                    coroutineScope.launch { CockpitWidget.forceUpdateAll(context) }
                                },
                                onSelectWidgetStyle = { nextStyle ->
                                    widgetStyle = nextStyle
                                    prefs.edit().putString("widget_style", nextStyle).commit()
                                    coroutineScope.launch {
                                        CockpitWidget.forceUpdateAll(context)
                                        Toast.makeText(context, "Widget: $nextStyle", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onSimulateAmount = { simAmount ->
                                    coroutineScope.launch {
                                        val alloc = DynamicAllocationEngine.calculateDynamicSplit(simAmount)
                                        simulatedModeOverride = alloc.allocationMode to alloc.modeSubtitle
                                        dao.updateVaultBalances(listOf(
                                            VaultBalanceEntity("RENT", alloc.rentVault, 0.0, alloc.rentVault),
                                            VaultBalanceEntity("FIXED_OVERHEAD", alloc.fixedOverheadVault, 0.0, alloc.fixedOverheadVault),
                                            VaultBalanceEntity("SUSTENANCE_FLEET", alloc.sustenanceFleetVault, 0.0, alloc.sustenanceFleetVault),
                                            VaultBalanceEntity("DISCRETIONARY", alloc.discretionaryVault, 0.0, alloc.discretionaryVault),
                                            VaultBalanceEntity("TRAVEL", alloc.travelVault, 0.0, alloc.travelVault),
                                            VaultBalanceEntity("WEALTH_SURPLUS", alloc.wealthSurplusVault, 0.0, alloc.wealthSurplusVault)
                                        ))
                                        showSettingsSheet = false
                                        Toast.makeText(
                                            context,
                                            "Simulated AED ${String.format("%,.0f", simAmount)} (${alloc.allocationMode})",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                onResetSimulation = {
                                    simulatedModeOverride = null
                                    coroutineScope.launch {
                                        SmsHistoryScanner.recomputeLedgerFromDb(context)
                                        CockpitWidget.forceUpdateAll(context)
                                        showSettingsSheet = false
                                        Toast.makeText(context, "Restored live SMS balances", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onDismiss = { showSettingsSheet = false }
                            )
                        }

                        // Tap-to-Reclassify Bottom Sheet
                        selectedTxnForEdit?.let { txn ->
                            ReclassifyTransactionSheet(
                                txn = txn,
                                onDismiss = { selectedTxnForEdit = null },
                                onSave = { newVault, newSubTag, rememberRule ->
                                    coroutineScope.launch {
                                        if (rememberRule) {
                                            dao.insertMerchantRule(
                                                MerchantRuleEntity(
                                                    merchantKey = txn.merchant.uppercase().trim(),
                                                    displayMerchant = txn.merchant,
                                                    vaultKey = newVault,
                                                    subTag = newSubTag
                                                )
                                            )
                                            dao.updateAllMatchingMerchantTransactions(txn.merchant, newVault, newSubTag)
                                        } else {
                                            dao.updateSingleTransactionClassification(txn.id, newVault, newSubTag)
                                        }
                                        SmsHistoryScanner.recomputeLedgerFromDb(context)
                                        CockpitWidget.forceUpdateAll(context)
                                        selectedTxnForEdit = null
                                        Toast.makeText(
                                            context,
                                            if (rememberRule) "Saved rule & updated ${txn.merchant}" else "Updated transaction",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            )
                        }

                        // Cycle-Close History Bottom Sheet
                        if (showCycleHistorySheet) {
                            CycleHistorySheet(
                                snapshots = cycleSnapshots,
                                customRulesCount = merchantRules.size,
                                onDismiss = { showCycleHistorySheet = false },
                                onCaptureNow = {
                                    coroutineScope.launch {
                                        SmsHistoryScanner.captureCurrentCycleSnapshot(context)
                                        Toast.makeText(context, "Saved current cycle snapshot", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Vault Ledger Breakdown Environment:
 * Opens when tapping the Vault Ledger pie chart card on the Home page.
 * Breaks down Rent, Fixed Overhead, Sustenance & Fleet, Discretionary, Travel & Wealth Surplus with bar charts.
 */
@Composable
fun VaultLedgerScreen(
    vaults: List<VaultBalanceEntity>,
    cyclePool: Double,
    cycleLabel: String,
    activeMode: String,
    onBack: () -> Unit,
    onSelectVault: (String) -> Unit
) {
    val palette = LocalCockpitColors.current
    val totalRemaining = vaults.sumOf { it.currentRemaining }
    val totalSpent = vaults.sumOf { it.spentAmount }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onBack() },
                        shape = RoundedCornerShape(12.dp),
                        color = palette.surface,
                        border = BorderStroke(1.dp, palette.border)
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 11.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "← Back",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Vault Ledger",
                            fontSize = 24.sp,
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
                }

                Surface(
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = palette.surface,
                    border = BorderStroke(1.dp, palette.border)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${vaults.size} Vaults",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.primaryAccent
                        )
                    }
                }
            }
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(listOf(AmethystTop, VioletBot)))
                    .padding(22.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "UNALLOCATED VAULT CASH",
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
                                text = activeMode,
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
                        text = "AED ${String.format("%,.2f", totalRemaining)}",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-1).sp,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Cycle Pool AED ${String.format("%,.0f", cyclePool)} • Cash Spent AED ${String.format("%,.0f", totalSpent)}",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.75f)
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Vault Breakdown",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary
                )
                Text(
                    text = "Tap vault to inspect txns",
                    fontSize = 11.sp,
                    color = palette.textSecondary
                )
            }
        }

        items(vaults, key = { it.vaultKey }) { vault ->
            VaultItemCard(
                vault = vault,
                onClick = { onSelectVault(vault.vaultKey) }
            )
        }
    }
}

/**
 * Shared Analytics Screen for both Checking/Debit (isCreditCard = false) and Credit Card (isCreditCard = true).
 */
@Composable
fun AccountAnalyticsScreen(
    isCreditCard: Boolean,
    title: String,
    cycleLabel: String,
    allTransactions: List<TransactionEntity>,
    primaryMetricLabel: String,
    allTimeMetricLabel: String,
    heroGradient: Brush,
    leftStatLabel: String,
    leftStatValue: String,
    leftStatAccent: Color,
    midStatLabel: String,
    midStatValue: String,
    rightStatLabel: String,
    rightStatValue: String,
    onBack: () -> Unit,
    onSelectTransaction: (TransactionEntity) -> Unit
) {
    val palette = LocalCockpitColors.current
    var scopeFilter by remember { mutableStateOf("CYCLE") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }

    val accountTxns = remember(allTransactions, isCreditCard, scopeFilter) {
        allTransactions.filter {
            it.isCreditCard == isCreditCard && !it.isInternalTransfer && (scopeFilter == "ALL" || it.isCurrentCycle)
        }
    }

    val totalPeriodSpend = remember(accountTxns) { accountTxns.sumOf { it.amount } }

    val categoryBreakdown = remember(accountTxns) {
        accountTxns
            .groupBy { it.subTag }
            .map { (tag, list) ->
                Triple(tag, list.sumOf { it.amount }, list.size)
            }
            .sortedByDescending { it.second }
    }

    val displayedTxns = remember(accountTxns, selectedCategory) {
        if (selectedCategory == null) accountTxns else accountTxns.filter { it.subTag == selectedCategory }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onBack() },
                        shape = RoundedCornerShape(12.dp),
                        color = palette.surface,
                        border = BorderStroke(1.dp, palette.border)
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 11.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "← Back",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = title,
                            fontSize = 24.sp,
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
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("CYCLE" to "Cycle", "ALL" to "All").forEach { (key, label) ->
                        val active = scopeFilter == key
                        Surface(
                            modifier = Modifier
                                .height(36.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    scopeFilter = key
                                    selectedCategory = null
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = if (active) palette.primaryAccent else palette.surface,
                            border = if (active) null else BorderStroke(1.dp, palette.border)
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 11.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (active) Color.White else palette.textSecondary
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(heroGradient)
                    .padding(22.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (scopeFilter == "CYCLE") primaryMetricLabel else allTimeMetricLabel,
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
                                text = "${accountTxns.size} TXNS",
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
                        text = "AED ${String.format("%,.2f", totalPeriodSpend)}",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-1).sp,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "${categoryBreakdown.size} active spending categories",
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
                            Text(leftStatLabel, fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f))
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = leftStatValue,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = leftStatAccent
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(midStatLabel, fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f))
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = midStatValue,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(rightStatLabel, fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f))
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = rightStatValue,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = GoldTop
                            )
                        }
                    }
                }
            }
        }

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
                            text = "Where You Spent",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.textPrimary
                        )
                        if (selectedCategory != null) {
                            Text(
                                text = "Clear filter ✕",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.primaryAccent,
                                modifier = Modifier.clickable { selectedCategory = null }
                            )
                        } else {
                            Text(
                                text = "Tap bar to filter",
                                fontSize = 11.sp,
                                color = palette.textSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Box(modifier = Modifier.size(180.dp), contentAlignment = Alignment.Center) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokeWidth = 22.dp.toPx()
                            val diameter = size.minDimension - strokeWidth
                            val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
                            val arcSize = Size(diameter, diameter)

                            if (totalPeriodSpend <= 0.0) {
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
                                var startAngle = -90f
                                categoryBreakdown.forEach { (tag, amt, _) ->
                                    val sweep = ((amt / totalPeriodSpend) * 360.0).toFloat()
                                    if (sweep > 0f) {
                                        drawArc(
                                            color = getSubTagSolidColor(tag, palette.isDark),
                                            startAngle = startAngle,
                                            sweepAngle = (sweep - 2.5f).coerceAtLeast(1f),
                                            useCenter = false,
                                            topLeft = topLeft,
                                            size = arcSize,
                                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                        )
                                        startAngle += sweep
                                    }
                                }
                            }
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "AED ${String.format("%,.0f", totalPeriodSpend)}",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.textPrimary
                            )
                            Text(
                                text = "${categoryBreakdown.size} categories",
                                fontSize = 11.sp,
                                color = palette.textSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        categoryBreakdown.forEach { (tag, amt, count) ->
                            val share = if (totalPeriodSpend > 0.0) (amt / totalPeriodSpend).toFloat().coerceIn(0f, 1f) else 0f
                            val gradPair = getSubTagPalettePair(tag)
                            val isSelected = selectedCategory == tag

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) palette.surfaceMuted else Color.Transparent)
                                    .clickable {
                                        selectedCategory = if (isSelected) null else tag
                                    }
                                    .padding(vertical = 4.dp, horizontal = if (isSelected) 8.dp else 0.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(9.dp)
                                                .background(
                                                    Brush.linearGradient(listOf(gradPair.first, gradPair.second)),
                                                    CircleShape
                                                )
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = tag,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = palette.textPrimary
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "($count)",
                                            fontSize = 11.sp,
                                            color = palette.textSecondary
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "AED ${String.format("%,.2f", amt)}",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = palette.textPrimary
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "${(share * 100).toInt()}%",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = palette.textSecondary
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(CircleShape)
                                        .background(palette.surfaceMuted)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(share)
                                            .fillMaxHeight()
                                            .clip(CircleShape)
                                            .background(Brush.horizontalGradient(listOf(gradPair.first, gradPair.second)))
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedCategory?.let { "$it Purchases" } ?: "$title Transactions",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary
                )
                Text(
                    text = "Tap any item to re-tag",
                    fontSize = 11.sp,
                    color = palette.textSecondary
                )
            }
        }

        items(displayedTxns, key = { it.id }) { txn ->
            TransactionRowCard(
                txn = txn,
                onClick = { onSelectTransaction(txn) }
            )
        }
    }
}

/**
 * Single Vault Transaction Drill-Down Screen:
 * Shifts the tapped Vault Card up to the top and reveals all current-cycle transactions that affected it.
 */
@Composable
fun VaultDetailScreen(
    vault: VaultBalanceEntity,
    allTransactions: List<TransactionEntity>,
    onBack: () -> Unit,
    onSelectTransaction: (TransactionEntity) -> Unit
) {
    val palette = LocalCockpitColors.current

    val affectingTxns = remember(allTransactions, vault.vaultKey) {
        allTransactions.filter {
            it.isCurrentCycle && !it.isInternalTransfer && it.deducedVault == vault.vaultKey
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onBack() },
                        shape = RoundedCornerShape(12.dp),
                        color = palette.surface,
                        border = BorderStroke(1.dp, palette.border)
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 11.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "← Vaults",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = formatVaultLabel(vault.vaultKey),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                }

                Text(
                    text = "${affectingTxns.size} txns this cycle",
                    fontSize = 11.sp,
                    color = palette.textSecondary
                )
            }
        }

        item {
            VaultItemCard(vault = vault, onClick = { onBack() })
        }

        if (affectingTxns.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = palette.surface,
                    border = BorderStroke(1.dp, palette.border)
                ) {
                    Text(
                        text = "No transactions affected ${formatVaultLabel(vault.vaultKey)} in the current cycle.",
                        fontSize = 13.sp,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            }
        } else {
            items(affectingTxns, key = { it.id }) { txn ->
                TransactionRowCard(
                    txn = txn,
                    onClick = { onSelectTransaction(txn) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    themeMode: String,
    widgetStyle: String,
    isSimulationActive: Boolean,
    onSelectTheme: (String) -> Unit,
    onSelectWidgetStyle: (String) -> Unit,
    onSimulateAmount: (Double) -> Unit,
    onResetSimulation: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalCockpitColors.current
    var customAmountText by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        contentColor = palette.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = "Cockpit Settings",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Appearance Theme",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("SYSTEM" to "◐ Auto", "LIGHT" to "☀ Light", "DARK" to "☾ Dark").forEach { (key, label) ->
                        val selected = themeMode == key
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelectTheme(key) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) palette.primaryAccent else palette.surfaceMuted
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 11.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selected) Color.White else palette.textPrimary
                                )
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Home Widget Style",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("BRAND" to "◈ Brand", "SYSTEM" to "◈ System", "MONO" to "◈ Mono").forEach { (key, label) ->
                        val selected = widgetStyle == key
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelectWidgetStyle(key) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) palette.primaryAccent else palette.surfaceMuted
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 11.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selected) Color.White else palette.textPrimary
                                )
                            }
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.border))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Payday & Mode Simulator",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                    if (isSimulationActive) {
                        Text(
                            text = "Reset to Live",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.amberAccent,
                            modifier = Modifier.clickable { onResetSimulation() }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSimulateAmount(9750.0) },
                        shape = RoundedCornerShape(12.dp),
                        color = palette.surfaceMuted
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 12.dp),
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
                            .clickable { onSimulateAmount(17000.0) },
                        shape = RoundedCornerShape(12.dp),
                        color = palette.primaryAccent
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 12.dp),
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customAmountText,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() || it == '.' }) {
                                customAmountText = input
                            }
                        },
                        placeholder = { Text("Custom Amount (AED)", fontSize = 13.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )

                    Surface(
                        modifier = Modifier
                            .height(54.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                val parsed = customAmountText.toDoubleOrNull()
                                if (parsed != null && parsed >= 0.0) {
                                    onSimulateAmount(parsed)
                                }
                            },
                        shape = RoundedCornerShape(12.dp),
                        color = palette.primaryAccent
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 18.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Test",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReclassifyTransactionSheet(
    txn: TransactionEntity,
    onDismiss: () -> Unit,
    onSave: (vaultKey: String, subTag: String, rememberRule: Boolean) -> Unit
) {
    val palette = LocalCockpitColors.current
    var selectedVault by remember { mutableStateOf(txn.deducedVault.takeIf { it != "INTERNAL_TRANSFER" } ?: "DISCRETIONARY") }
    var selectedSubTag by remember { mutableStateOf(txn.subTag) }
    var rememberRule by remember { mutableStateOf(true) }

    val vaultOptions = listOf(
        "SUSTENANCE_FLEET" to "Sustenance & Fleet",
        "FIXED_OVERHEAD" to "Fixed Overhead",
        "DISCRETIONARY" to "Discretionary",
        "TRAVEL" to "Travel",
        "WEALTH_SURPLUS" to "Wealth Surplus",
        "RENT" to "Rent"
    )

    val subTagOptions = listOf(
        "#Fuel",
        "#Fleet & Parts",
        "#Groceries",
        "#Dining",
        "#Nightlife",
        "#Grooming",
        "#Online & Retail",
        "#Bills & Subs",
        "#Utilities",
        "#Travel",
        "#Auto Loan",
        "#Transfers",
        "#Shopping & Misc"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        contentColor = palette.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = txn.merchant,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary
                )
                Text(
                    text = "${if (txn.isCreditCard) "Card 3445" else "Checking 8691"} • AED ${String.format("%,.2f", txn.amount)}",
                    fontSize = 12.sp,
                    color = palette.textSecondary
                )
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = palette.surfaceMuted
            ) {
                Text(
                    text = txn.rawSms,
                    fontSize = 11.sp,
                    color = palette.textSecondary,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Text(
                text = "Assign Vault",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vaultOptions.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { (key, label) ->
                            val isSelected = selectedVault == key
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { selectedVault = key },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) palette.primaryAccent else palette.surfaceMuted
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSelected) Color.White else palette.textPrimary,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Text(
                text = "Assign #SubTag",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                subTagOptions.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { tag ->
                            val isSelected = selectedSubTag == tag
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { selectedSubTag = tag },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) palette.primaryAccent else palette.surfaceMuted
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 9.dp, horizontal = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = tag,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSelected) Color.White else palette.textPrimary,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        repeat(3 - row.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = palette.surfaceMuted
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Remember for future alerts",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary
                        )
                        Text(
                            text = "Auto-route \"${txn.merchant}\" on every sync",
                            fontSize = 11.sp,
                            color = palette.textSecondary
                        )
                    }
                    Switch(
                        checked = rememberRule,
                        onCheckedChange = { rememberRule = it }
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSave(selectedVault, selectedSubTag, rememberRule) },
                shape = RoundedCornerShape(14.dp),
                color = palette.primaryAccent
            ) {
                Box(
                    modifier = Modifier.padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Apply Classification",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CycleHistorySheet(
    snapshots: List<CycleSnapshotEntity>,
    customRulesCount: Int,
    onDismiss: () -> Unit,
    onCaptureNow: () -> Unit
) {
    val palette = LocalCockpitColors.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        contentColor = palette.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Cycle Snapshots",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                    Text(
                        text = "22nd – 21st archive • $customRulesCount learned merchant rules",
                        fontSize = 11.sp,
                        color = palette.textSecondary
                    )
                }

                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onCaptureNow() },
                    shape = RoundedCornerShape(12.dp),
                    color = palette.primaryAccent
                ) {
                    Text(
                        text = "+ Snapshot Now",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            if (snapshots.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = palette.surfaceMuted
                ) {
                    Text(
                        text = "No archived cycles yet. Closed cycles archive automatically on the 22nd, or tap '+ Snapshot Now' to save a checkpoint.",
                        fontSize = 12.sp,
                        color = palette.textSecondary,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                snapshots.forEach { snap ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = palette.surfaceMuted
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = snap.cycleLabel,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.textPrimary
                                )
                                Text(
                                    text = snap.modeAtSnapshot,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.primaryAccent
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Checking Spend", fontSize = 10.sp, color = palette.textSecondary)
                                    Text(
                                        text = "AED ${String.format("%,.0f", snap.checkingSpend)}",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.textPrimary
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Card 3445 Spend", fontSize = 10.sp, color = palette.textSecondary)
                                    Text(
                                        text = "AED ${String.format("%,.0f", snap.creditSpend)}",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.amberAccent
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Top Category", fontSize = 10.sp, color = palette.textSecondary)
                                    Text(
                                        text = snap.topCategory,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.textPrimary
                                    )
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
fun VaultItemCard(
    vault: VaultBalanceEntity,
    onClick: () -> Unit = {}
) {
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
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() },
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
fun TransactionRowCard(
    txn: TransactionEntity,
    onClick: () -> Unit
) {
    val palette = LocalCockpitColors.current
    val formatter = remember { DateTimeFormatter.ofPattern("dd MMM, HH:mm").withZone(ZoneId.systemDefault()) }
    val formattedDate = formatter.format(Instant.ofEpochMilli(txn.timestamp))
    val tagColor = if (txn.isInternalTransfer) palette.positiveGreen else getSubTagSolidColor(txn.subTag, palette.isDark)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
    }
}
