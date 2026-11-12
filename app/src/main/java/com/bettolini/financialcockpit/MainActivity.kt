package com.bettolini.financialcockpit

import android.Manifest
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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

        setContent {
            val context = LocalContext.current
            val coroutineScope = rememberCoroutineScope()
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
                        Toast.makeText(context, "Synced $count ENBD alerts & updated widget!", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F0F11)
                ) {
                    val vaults by dao.getAllVaults().collectAsState(initial = emptyList())
                    val snapshot by dao.getSnapshot().collectAsState(initial = null)
                    val recentTxns by dao.getRecentTransactions().collectAsState(initial = emptyList())

                    val today = LocalDate.now()
                    val payday = if (today.dayOfMonth < 22) today.withDayOfMonth(22) else today.plusMonths(1).withDayOfMonth(22)
                    val daysUntilPayday = ChronoUnit.DAYS.between(today, payday).coerceAtLeast(1)

                    // Core Accounting Math
                    val checkingCash = snapshot?.liveDebitBalance ?: 6296.29
                    val currentStatementDue = snapshot?.statementDue ?: 3620.45
                    val dueDateLabel = snapshot?.statementDueDate ?: "19 Nov"
                    val unbilledVal = snapshot?.unbilledSpend ?: 4894.16
                    val totalCcOwed = snapshot?.creditTotalOwed ?: (currentStatementDue + unbilledVal)
                    val avlLimit = snapshot?.creditAvailableLimit ?: (42000.00 - totalCcOwed)
                    val cycleLabel = snapshot?.cycleLabel ?: "Cycle: 22 Oct – 21 Nov"
                    val cyclePool = snapshot?.cycleStartingPool ?: 0.0

                    val usableCashToday = (checkingCash - currentStatementDue).coerceAtLeast(0.0)
                    val safeDaily = (usableCashToday / daysUntilPayday.toDouble()).coerceAtLeast(0.0)

                    // Forward Projection (Next Payday on 22nd)
                    val expectedSalary = 9750.00
                    val autoLoanEmi = 1594.00
                    val nextMonthCcCommitment = if (unbilledVal > 1000.0) 147.00 else unbilledVal
                    val projectedNextMonthBudget = (usableCashToday + expectedSalary - autoLoanEmi - nextMonthCcCommitment).coerceAtLeast(0.0)

                    // Cross-vault overage calculation
                    val totalVaultOverage = vaults.filter { it.currentRemaining < 0.0 }.sumOf { -it.currentRemaining }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(vertical = 20.dp)
                    ) {
                        item {
                            // Header
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "FINANCIAL COCKPIT",
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 1.sp,
                                        color = Color(0xFF00E676)
                                    )
                                    Text(
                                        text = cycleLabel,
                                        fontSize = 11.sp,
                                        color = Color(0xFF8E8E93)
                                    )
                                }

                                FilledTonalButton(
                                    onClick = {
                                        if (hasSmsPermission) {
                                            coroutineScope.launch {
                                                val count = SmsHistoryScanner.scanAndSyncInbox(context)
                                                CockpitWidget().updateAll(context)
                                                Toast.makeText(context, "Synced $count ENBD alerts", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            permissionLauncher.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = Color(0xFF1C1C1E),
                                        contentColor = Color(0xFF00E676)
                                    ),
                                    modifier = Modifier.border(1.dp, Color(0xFF2C2C2E), RoundedCornerShape(12.dp))
                                ) {
                                    Text("⚡ Scan SMS", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Spacer(modifier = Modifier.height(18.dp))

                            // 1. True Usable Liquid Runway Card
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF161618)),
                                border = BorderStroke(1.dp, Color(0xFF242426))
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                                    Text(
                                        text = if (currentStatementDue <= 0.01) "TRUE USABLE CASH (CC BILL SETTLED)" else "TRUE USABLE CASH (NET OF $dueDateLabel CC BILL)",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp,
                                        color = Color(0xFF8E8E93)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "AED ${String.format("%,.2f", usableCashToday)}",
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Black,
                                        color = Color(0xFF00E676)
                                    )
                                    Text(
                                        text = "Checking AED ${String.format("%,.2f", checkingCash)} − Statement Due AED ${String.format("%,.2f", currentStatementDue)}",
                                        fontSize = 11.sp,
                                        color = Color(0xFF8E8E93)
                                    )

                                    Spacer(modifier = Modifier.height(14.dp))
                                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF242426)))
                                    Spacer(modifier = Modifier.height(14.dp))

                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column {
                                            Text("Safe Daily Spend", fontSize = 11.sp, color = Color(0xFF8E8E93))
                                            Text("AED ${String.format("%.0f", safeDaily)} / day", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E676))
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text("Runway Until Payday", fontSize = 11.sp, color = Color(0xFF8E8E93))
                                            Text("$daysUntilPayday days left (22nd)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // 2. Credit Card Liability & Forward Projection Card
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF161618)),
                                border = BorderStroke(1.dp, Color(0xFF382323))
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(
                                            text = "SKYWARDS SIGNATURE (CARD 3445)",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp,
                                            color = Color(0xFFFF453A)
                                        )
                                        Text(
                                            text = "AVL LIMIT: AED ${String.format("%,.0f", avlLimit)}",
                                            fontSize = 10.sp,
                                            color = Color(0xFF8E8E93)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column {
                                            Text(
                                                text = if (currentStatementDue <= 0.01) "AED 0.00 ✓" else "AED ${String.format("%,.2f", currentStatementDue)}",
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (currentStatementDue <= 0.01) Color(0xFF00E676) else Color.White
                                            )
                                            Text(
                                                text = if (currentStatementDue <= 0.01) "Statement Paid" else "Billed Due ($dueDateLabel)",
                                                fontSize = 11.sp,
                                                color = if (currentStatementDue <= 0.01) Color(0xFF00E676) else Color(0xFFFF9F0A)
                                            )
                                        }

                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                text = "AED ${String.format("%,.2f", unbilledVal)}",
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFFF453A)
                                            )
                                            Text("Unbilled (EPP Est: ~147/mo)", fontSize = 11.sp, color = Color(0xFF8E8E93))
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))
                                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF242426)))
                                    Spacer(modifier = Modifier.height(12.dp))

                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column {
                                            Text("Post-Payday Liquid Forecast", fontSize = 11.sp, color = Color(0xFF8E8E93))
                                            Text("AED ${String.format("%,.0f", projectedNextMonthBudget)}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF388AF6))
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text("Total Card Debt (42k − Avl)", fontSize = 11.sp, color = Color(0xFF8E8E93))
                                            Text("AED ${String.format("%,.2f", totalCcOwed)}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFD1D1D6))
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // 3. Payday-Anchored Donut Chart Card
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF161618)),
                                border = BorderStroke(1.dp, Color(0xFF242426))
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "PAYDAY-ANCHORED VAULT LEDGER",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.5.sp,
                                        color = Color(0xFF8E8E93)
                                    )
                                    if (cyclePool > 0.0) {
                                        Text(
                                            text = "Cycle Starting Pool: AED ${String.format("%,.0f", cyclePool)}",
                                            fontSize = 11.sp,
                                            color = Color(0xFF636366)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                    DonutChart(vaults = vaults, modifier = Modifier.size(180.dp))
                                    Spacer(modifier = Modifier.height(18.dp))
                                    DonutLegend(vaults = vaults)

                                    if (totalVaultOverage > 0.0) {
                                        Spacer(modifier = Modifier.height(14.dp))
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = Color(0xFF2C1618),
                                            border = BorderStroke(1.dp, Color(0xFF5C2326))
                                        ) {
                                            Text(
                                                text = "⚠ Vault Overage: AED ${String.format("%,.2f", totalVaultOverage)} cannibalized from other vaults",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFFFF6961),
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Quick Salary Simulator Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            val alloc = DynamicAllocationEngine.calculateDynamicSplit(9750.0)
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
                                    modifier = Modifier.weight(1f).height(44.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF242426),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Text("Sim: 9,750 Payday", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }

                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            val alloc = DynamicAllocationEngine.calculateDynamicSplit(17000.0)
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
                                    modifier = Modifier.weight(1f).height(44.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF00E676),
                                        contentColor = Color(0xFF0A0A0B)
                                    )
                                ) {
                                    Text("Sim: 17,000 Raise", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            Text(
                                text = "VAULT LEDGER & #SUBTAGS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = Color(0xFF8E8E93)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        items(vaults) { vault ->
                            VaultItemCard(vault)
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        if (recentTxns.isNotEmpty()) {
                            item {
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "RECENT PARSED ENBD ALERTS",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    color = Color(0xFF8E8E93)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                            items(recentTxns) { txn ->
                                TransactionRowCard(txn)
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

fun getVaultColor(key: String): Color {
    return when (key) {
        "RENT" -> Color(0xFF388AF6)
        "FIXED_OVERHEAD" -> Color(0xFFFF9500)
        "SUSTENANCE_FLEET" -> Color(0xFF30D158)
        "DISCRETIONARY" -> Color(0xFFAF52DE)
        "TRAVEL" -> Color(0xFF5AC8FA)
        "WEALTH_SURPLUS" -> Color(0xFFFFD60A)
        else -> Color(0xFF8E8E93)
    }
}

@Composable
fun DonutChart(vaults: List<VaultBalanceEntity>, modifier: Modifier = Modifier) {
    val positiveVaults = vaults.filter { it.currentRemaining > 0.0 }
    val totalPositive = positiveVaults.sumOf { it.currentRemaining }.toFloat()
    val netVaultTotal = vaults.sumOf { it.currentRemaining }.toFloat()

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 22.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
            val arcSize = Size(diameter, diameter)

            if (totalPositive <= 0f) {
                drawArc(
                    color = Color(0xFF2C2C2E),
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
                            color = getVaultColor(vault.vaultKey),
                            startAngle = currentAngle,
                            sweepAngle = (sweep - 2f).coerceAtLeast(1f),
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
                text = "AED ${String.format("%.0f", netVaultTotal)}",
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                color = Color.White
            )
            Text(
                text = "NET VAULTS",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF8E8E93),
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
fun DonutLegend(vaults: List<VaultBalanceEntity>) {
    val chunked = vaults.chunked(3)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chunked.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                row.forEach { vault ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(getVaultColor(vault.vaultKey), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = vault.vaultKey.replace("_", " "),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFD1D1D6)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VaultItemCard(vault: VaultBalanceEntity) {
    val isOverspent = vault.currentRemaining < 0.0
    val progress = if (vault.allocatedAmount > 0) (vault.currentRemaining / vault.allocatedAmount).toFloat() else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(600),
        label = "vault_progress"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = if (isOverspent) Color(0xFF1E1416) else Color(0xFF161618)),
        border = BorderStroke(1.dp, if (isOverspent) Color(0xFF4A1E22) else Color(0xFF242426))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(getVaultColor(vault.vaultKey), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = vault.vaultKey.replace("_", " "),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Text(
                    text = if (isOverspent) "-AED ${String.format("%,.2f", -vault.currentRemaining)}" else "AED ${String.format("%,.2f", vault.currentRemaining)}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    color = if (isOverspent) Color(0xFFFF453A) else if (vault.currentRemaining > 0) Color.White else Color(0xFF8E8E93)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = if (isOverspent) Color(0xFFFF453A) else getVaultColor(vault.vaultKey),
                trackColor = Color(0xFF242426),
                strokeCap = StrokeCap.Round
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Cycle Target: AED ${String.format("%.0f", vault.allocatedAmount)}  •  Spent: AED ${String.format("%.0f", vault.spentAmount)}",
                    fontSize = 11.sp,
                    color = Color(0xFF8E8E93)
                )
                Text(
                    text = if (isOverspent) "OVER" else "${(progress * 100).toInt()}% left",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isOverspent) Color(0xFFFF453A) else Color(0xFFD1D1D6)
                )
            }

            if (vault.topSubTagsSummary.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = vault.topSubTagsSummary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = getVaultColor(vault.vaultKey)
                )
            }
        }
    }
}

@Composable
fun TransactionRowCard(txn: TransactionEntity) {
    val formatter = remember { DateTimeFormatter.ofPattern("dd MMM, HH:mm").withZone(ZoneId.systemDefault()) }
    val formattedDate = formatter.format(Instant.ofEpochMilli(txn.timestamp))
    val tagColor = if (txn.isInternalTransfer) Color(0xFF00E676) else getVaultColor(txn.deducedVault)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161618)),
        border = BorderStroke(1.dp, Color(0xFF242426))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = txn.merchant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${if (txn.isCreditCard) "CC 3445" else "DEBIT 8691"} • $formattedDate",
                        fontSize = 11.sp,
                        color = if (txn.isCreditCard) Color(0xFFFF9F0A) else Color(0xFF388AF6)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = tagColor.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = txn.subTag,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = tagColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Text(
                text = "AED ${String.format("%,.2f", txn.amount)}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (txn.isInternalTransfer) Color(0xFF00E676) else Color.White
            )
        }
    }
}
