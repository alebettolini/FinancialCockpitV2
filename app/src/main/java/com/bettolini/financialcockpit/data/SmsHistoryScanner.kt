package com.bettolini.financialcockpit.data

import android.content.Context
import android.provider.Telephony
import com.bettolini.financialcockpit.domain.ClassificationResult
import com.bettolini.financialcockpit.domain.DynamicAllocationEngine
import com.bettolini.financialcockpit.domain.MerchantClassifier
import com.bettolini.financialcockpit.domain.PacingNudgeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

object SmsHistoryScanner {

    fun getCurrentCycleStart(today: LocalDate = LocalDate.now()): LocalDate {
        return if (today.dayOfMonth >= 22) {
            today.withDayOfMonth(22)
        } else {
            today.minusMonths(1).withDayOfMonth(22)
        }
    }

    fun formatCycleRangeLabel(cycleStart: LocalDate): String {
        val fmt = DateTimeFormatter.ofPattern("dd MMM")
        return "${cycleStart.format(fmt)} – ${cycleStart.plusMonths(1).minusDays(1).format(fmt)}"
    }

    suspend fun scanAndSyncInbox(context: Context): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val dao = AppDatabase.getInstance(context).financeDao()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val cycleStartDate = getCurrentCycleStart(today)
        val cycleStartMillis = cycleStartDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val cycleLabel = "Cycle: ${formatCycleRangeLabel(cycleStartDate)}"

        val userRules = dao.getAllMerchantRulesSync()

        val cursor = resolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null,
            null,
            "${Telephony.Sms.DATE} ASC"
        ) ?: return@withContext 0

        var detectedDebitBalance = 2737.93
        var lastDebitBalanceTimestamp = 0L

        var detectedCreditAvlLimit = 37012.55
        var lastCreditLimitTimestamp = 0L

        var detectedStatementDue = 0.0
        var lastStatementTimestamp = 0L
        var detectedDueDate = "19 Nov"
        var paymentAppliedThisCycle = false

        val creditLimit = 42000.00
        val parsedTransactions = mutableListOf<TransactionEntity>()
        val recordedCcPayments = mutableListOf<Pair<Long, Double>>()

        val debitBalanceRegex = Regex(
            """(?:Avl\.?\s*Balance|Available\s*Balance)\s*(?:is|:)?\s*(?:AED)?\s*([\d,]+\.?\d*)""",
            RegexOption.IGNORE_CASE
        )
        val creditLimitRegex = Regex(
            """(?:Avl\.?\s*(?:Cr\.?\s*)?Limit|Available\s*(?:Cr\.?\s*)?Limit)\s*(?:is|:)?\s*(?:AED)?\s*([\d,]+\.?\d*)""",
            RegexOption.IGNORE_CASE
        )
        val purchaseRegex = Regex(
            """(?:Purchase of|Debited with|Spent|Payment of|Installment of)\s+(?:AED)?\s*([\d,]+\.?\d*)""",
            RegexOption.IGNORE_CASE
        )
        val prefixDebitRegex = Regex(
            """AED\s*([\d,]+\.?\d*)\s+(?:has been\s+)?debited""",
            RegexOption.IGNORE_CASE
        )
        val merchantRegex = Regex(
            """(?:at|to)\s+([^.,\n]+?)(?:\s+on|\s+using|,|\.|$)""",
            RegexOption.IGNORE_CASE
        )
        val statementRegex = Regex(
            """(?:statement|total amt due|amount due).*?AED\s*([\d,]+\.?\d*).*?(?:due date:?\s*(\d{1,2}[/-]\d{1,2}[/-]\d{2,4}|\d{1,2}\s+\w+))?""",
            RegexOption.IGNORE_CASE
        )
        val ccPaymentRegex = Regex(
            """Payment of\s+AED\s*([\d,]+\.?\d*)\s+(?:has been\s+)?received""",
            RegexOption.IGNORE_CASE
        )
        val ccTransferRegex = Regex(
            """AED\s*([\d,]+\.?\d*)\s+has been deducted from your account\s+([A-Z0-9]+)\s+towards payment of your Credit Card ending\s+(\d{4})""",
            RegexOption.IGNORE_CASE
        )
        val ccCreditRefundRegex = Regex(
            """(?:credited|refund|reversal).*?AED\s*([\d,]+\.?\d*)|AED\s*([\d,]+\.?\d*)\s+(?:has been\s+)?(?:credited|refunded)""",
            RegexOption.IGNORE_CASE
        )

        cursor.use { c ->
            val addressIndex = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIndex = c.getColumnIndex(Telephony.Sms.BODY)
            val dateIndex = c.getColumnIndex(Telephony.Sms.DATE)

            while (c.moveToNext()) {
                val address = c.getString(addressIndex) ?: ""
                val body = c.getString(bodyIndex) ?: ""
                val date = c.getLong(dateIndex)

                if (address.contains("ENBD", true) || address.contains("EmiratesNBD", true)) {
                    val ccTransferMatch = ccTransferRegex.find(body)
                    if (ccTransferMatch != null) {
                        val paid = ccTransferMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
                        val cardLast4 = ccTransferMatch.groupValues[3]

                        if (paid > 0.0 && date > lastDebitBalanceTimestamp) {
                            detectedDebitBalance = (detectedDebitBalance - paid).coerceAtLeast(0.0)
                        }

                        val alreadyCounted = recordedCcPayments.any {
                            abs(it.second - paid) < 0.01 && abs(date - it.first) < 86_400_000L
                        }
                        if (!alreadyCounted && paid > 0.0) {
                            recordedCcPayments.add(date to paid)
                            detectedStatementDue = (detectedStatementDue - paid).coerceAtLeast(0.0)
                            paymentAppliedThisCycle = true
                            if (date > lastCreditLimitTimestamp) {
                                detectedCreditAvlLimit = (detectedCreditAvlLimit + paid).coerceAtMost(creditLimit)
                            }
                        }

                        parsedTransactions.add(
                            TransactionEntity(
                                timestamp = date,
                                amount = paid,
                                merchant = "ENBD Card *$cardLast4 Repayment",
                                isCreditCard = false,
                                isInternalTransfer = true,
                                isCurrentCycle = date >= cycleStartMillis,
                                deducedVault = "INTERNAL_TRANSFER",
                                subTag = "#CC Bill Paid",
                                rawSms = body
                            )
                        )
                        continue
                    }

                    val isDebitAccountMsg = body.contains("A/C", true) ||
                            body.contains("Account", true) ||
                            body.contains("8691", true) ||
                            body.contains("Avl Balance", true) ||
                            body.contains("Available Balance", true)

                    val isCredit = !isDebitAccountMsg && (
                            body.contains("Credit Card", true) ||
                            body.contains("3445", true) ||
                            body.contains("Limit", true)
                    )

                    val debitBalMatch = debitBalanceRegex.find(body)
                    if (debitBalMatch != null && !isCredit) {
                        debitBalMatch.groupValues[1].replace(",", "").toDoubleOrNull()?.let {
                            detectedDebitBalance = it
                            lastDebitBalanceTimestamp = date
                        }
                    }

                    val creditLimMatch = creditLimitRegex.find(body)
                    val parsedLimitInMsg = creditLimMatch?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
                    if (parsedLimitInMsg != null) {
                        detectedCreditAvlLimit = parsedLimitInMsg
                        lastCreditLimitTimestamp = date
                    }

                    val statementMatch = statementRegex.find(body)
                    if (statementMatch != null && isCredit) {
                        statementMatch.groupValues[1].replace(",", "").toDoubleOrNull()?.let {
                            detectedStatementDue = it
                            lastStatementTimestamp = date
                            paymentAppliedThisCycle = false
                        }
                        val rawDate = statementMatch.groupValues.getOrNull(2)
                        if (!rawDate.isNullOrBlank()) {
                            detectedDueDate = rawDate
                        }
                    }

                    val paymentMatch = ccPaymentRegex.find(body)
                    if (paymentMatch != null && isCredit) {
                        val paid = paymentMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
                        val alreadyCounted = recordedCcPayments.any {
                            abs(it.second - paid) < 0.01 && abs(date - it.first) < 86_400_000L
                        }
                        if (!alreadyCounted && paid > 0.0) {
                            recordedCcPayments.add(date to paid)
                            detectedStatementDue = (detectedStatementDue - paid).coerceAtLeast(0.0)
                            paymentAppliedThisCycle = true
                            if (parsedLimitInMsg == null && date > lastCreditLimitTimestamp) {
                                detectedCreditAvlLimit = (detectedCreditAvlLimit + paid).coerceAtMost(creditLimit)
                            }
                        }
                        continue
                    }

                    val isPaymentReceipt = body.contains("received towards", true) || body.contains("Payment of", true)
                    if (isCredit && !isPaymentReceipt) {
                        val refundMatch = ccCreditRefundRegex.find(body)
                        if (refundMatch != null) {
                            val rawCredit = refundMatch.groupValues[1].ifBlank { refundMatch.groupValues[2] }
                            val creditAmt = rawCredit.replace(",", "").toDoubleOrNull() ?: 0.0
                            if (creditAmt > 0.0) {
                                if (date >= lastStatementTimestamp) {
                                    detectedStatementDue = (detectedStatementDue - creditAmt).coerceAtLeast(0.0)
                                }
                                if (parsedLimitInMsg == null && date > lastCreditLimitTimestamp) {
                                    detectedCreditAvlLimit = (detectedCreditAvlLimit + creditAmt).coerceAtMost(creditLimit)
                                }
                            }
                            continue
                        }
                    }

                    val isCcBillTransfer = body.contains("Credit Card payment", true) ||
                            body.contains("Card Payment", true) ||
                            body.contains("towards payment of your Credit Card", true)
                    val purchaseMatch = purchaseRegex.find(body) ?: prefixDebitRegex.find(body)

                    if (purchaseMatch != null && !isPaymentReceipt && !body.contains("credited", true)) {
                        val amount = purchaseMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
                        val rawMerchant = merchantRegex.find(body)?.groupValues?.get(1)?.trim() ?: ""

                        if (isCredit && parsedLimitInMsg == null && date > lastCreditLimitTimestamp) {
                            detectedCreditAvlLimit = (detectedCreditAvlLimit - amount).coerceAtLeast(0.0)
                        }

                        val classification = if (isCcBillTransfer) {
                            ClassificationResult(
                                vaultKey = "INTERNAL_TRANSFER",
                                subTag = "#CC Bill Paid",
                                cleanMerchant = "ENBD Card 3445 Repayment"
                            )
                        } else {
                            val base = MerchantClassifier.classify(rawMerchant, body, isCredit)
                            val upperMerchant = base.cleanMerchant.uppercase()
                            val matchedRule = userRules.firstOrNull { rule ->
                                upperMerchant == rule.merchantKey ||
                                        upperMerchant.contains(rule.merchantKey) ||
                                        body.uppercase().contains(rule.merchantKey)
                            }
                            if (matchedRule != null) {
                                base.copy(vaultKey = matchedRule.vaultKey, subTag = matchedRule.subTag)
                            } else {
                                base
                            }
                        }

                        parsedTransactions.add(
                            TransactionEntity(
                                timestamp = date,
                                amount = amount,
                                merchant = classification.cleanMerchant,
                                isCreditCard = isCredit,
                                isInternalTransfer = isCcBillTransfer,
                                isCurrentCycle = date >= cycleStartMillis,
                                deducedVault = classification.vaultKey,
                                subTag = classification.subTag,
                                rawSms = body
                            )
                        )
                    }
                }
            }
        }

        if (paymentAppliedThisCycle && detectedStatementDue < 100.0) {
            detectedStatementDue = 0.0
        }

        dao.clearTransactions()
        dao.insertTransactions(parsedTransactions)

        // Archive any closed historical cycles automatically
        archiveClosedCycles(dao, parsedTransactions, cycleStartDate, zone)

        // Reconcile vaults & snapshot
        reconcileAndPersistState(
            context = context,
            dao = dao,
            allTransactions = parsedTransactions,
            detectedDebitBalance = detectedDebitBalance,
            creditLimit = creditLimit,
            detectedCreditAvlLimit = detectedCreditAvlLimit,
            detectedStatementDue = detectedStatementDue,
            detectedDueDate = detectedDueDate,
            cycleLabel = cycleLabel,
            evaluateNudges = true
        )

        return@withContext parsedTransactions.size
    }

    suspend fun recomputeLedgerFromDb(context: Context) = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(context).financeDao()
        val snap = dao.getSnapshotSync() ?: return@withContext
        val allTxns = dao.getAllTransactionsSync()

        reconcileAndPersistState(
            context = context,
            dao = dao,
            allTransactions = allTxns,
            detectedDebitBalance = snap.liveDebitBalance,
            creditLimit = snap.creditCardLimit,
            detectedCreditAvlLimit = snap.creditAvailableLimit,
            detectedStatementDue = snap.statementDue,
            detectedDueDate = snap.statementDueDate,
            cycleLabel = snap.cycleLabel,
            evaluateNudges = false
        )
    }

    suspend fun captureCurrentCycleSnapshot(context: Context) = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(context).financeDao()
        val snap = dao.getSnapshotSync() ?: return@withContext
        val allTxns = dao.getAllTransactionsSync()
        val cycleStart = getCurrentCycleStart()
        val cycleKey = cycleStart.toString()

        val cycleTxns = allTxns.filter { it.isCurrentCycle && !it.isInternalTransfer }
        val checkingSpend = cycleTxns.filter { !it.isCreditCard }.sumOf { it.amount }
        val creditSpend = cycleTxns.filter { it.isCreditCard }.sumOf { it.amount }
        val topCategory = cycleTxns
            .groupBy { it.subTag }
            .maxByOrNull { entry -> entry.value.sumOf { it.amount } }
            ?.key ?: "#General"
        val usableCash = (snap.liveDebitBalance - snap.statementDue).coerceAtLeast(0.0)

        dao.insertCycleSnapshot(
            CycleSnapshotEntity(
                cycleKey = cycleKey,
                cycleLabel = formatCycleRangeLabel(cycleStart),
                checkingSpend = checkingSpend,
                creditSpend = creditSpend,
                usableCashAtSnapshot = usableCash,
                modeAtSnapshot = snap.allocationMode,
                topCategory = topCategory,
                capturedAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun archiveClosedCycles(
        dao: FinanceDao,
        allTransactions: List<TransactionEntity>,
        currentCycleStart: LocalDate,
        zone: ZoneId
    ) {
        val closedByCycle = allTransactions
            .filter { !it.isInternalTransfer }
            .groupBy { txn ->
                val d = Instant.ofEpochMilli(txn.timestamp).atZone(zone).toLocalDate()
                getCurrentCycleStart(d)
            }
            .filterKeys { it.isBefore(currentCycleStart) }

        if (closedByCycle.isEmpty()) return

        val snapshots = closedByCycle.map { (cStart, txns) ->
            val checkingSpend = txns.filter { !it.isCreditCard }.sumOf { it.amount }
            val creditSpend = txns.filter { it.isCreditCard }.sumOf { it.amount }
            val topCat = txns
                .groupBy { it.subTag }
                .maxByOrNull { entry -> entry.value.sumOf { it.amount } }
                ?.key ?: "#General"

            CycleSnapshotEntity(
                cycleKey = cStart.toString(),
                cycleLabel = formatCycleRangeLabel(cStart),
                checkingSpend = checkingSpend,
                creditSpend = creditSpend,
                usableCashAtSnapshot = 0.0,
                modeAtSnapshot = "CLOSED CYCLE",
                topCategory = topCat,
                capturedAt = System.currentTimeMillis()
            )
        }
        dao.insertCycleSnapshotsIgnoreExisting(snapshots)
    }

    private suspend fun reconcileAndPersistState(
        context: Context,
        dao: FinanceDao,
        allTransactions: List<TransactionEntity>,
        detectedDebitBalance: Double,
        creditLimit: Double,
        detectedCreditAvlLimit: Double,
        detectedStatementDue: Double,
        detectedDueDate: String,
        cycleLabel: String,
        evaluateNudges: Boolean
    ) {
        val creditTotalOwed = (creditLimit - detectedCreditAvlLimit).coerceAtLeast(0.0)
        val unbilledSpend = (creditTotalOwed - detectedStatementDue).coerceAtLeast(0.0)
        val trueUsableCash = (detectedDebitBalance - detectedStatementDue).coerceAtLeast(0.0)

        val cycleAllSpendTxns = allTransactions.filter { it.isCurrentCycle && !it.isInternalTransfer }
        val cycleCheckingTxns = cycleAllSpendTxns.filter { !it.isCreditCard }
        val cycleCreditTxns = cycleAllSpendTxns.filter { it.isCreditCard }

        val ccTagSummary = cycleCreditTxns
            .groupBy { it.subTag }
            .mapValues { entry -> entry.value.sumOf { it.amount } }
            .entries
            .sortedByDescending { it.value }
            .take(4)
            .joinToString("  •  ") { "${it.key}: AED ${String.format("%.0f", it.value)}" }

        val cycleCheckingSpendTotal = cycleCheckingTxns.sumOf { it.amount }
        val reconstructedCycleStartPool = trueUsableCash + cycleCheckingSpendTotal

        val baselineAlloc = DynamicAllocationEngine.calculateDynamicSplit(reconstructedCycleStartPool)
        val vaultKeys = listOf("RENT", "FIXED_OVERHEAD", "SUSTENANCE_FLEET", "DISCRETIONARY", "TRAVEL", "WEALTH_SURPLUS")
        val vaultTargets = mapOf(
            "RENT" to baselineAlloc.rentVault,
            "FIXED_OVERHEAD" to baselineAlloc.fixedOverheadVault,
            "SUSTENANCE_FLEET" to baselineAlloc.sustenanceFleetVault,
            "DISCRETIONARY" to baselineAlloc.discretionaryVault,
            "TRAVEL" to baselineAlloc.travelVault,
            "WEALTH_SURPLUS" to baselineAlloc.wealthSurplusVault
        )

        val rawRemaining = mutableMapOf<String, Double>()
        for (key in vaultKeys) {
            val target = vaultTargets[key] ?: 0.0
            val spent = cycleCheckingTxns.filter { it.deducedVault == key }.sumOf { it.amount }
            rawRemaining[key] = target - spent
        }

        val negativeTotal = rawRemaining.values.filter { it < 0.0 }.sumOf { -it }
        if (negativeTotal > 0.0) {
            for (key in vaultKeys) {
                if ((rawRemaining[key] ?: 0.0) < 0.0) {
                    rawRemaining[key] = 0.0
                }
            }
            var toAbsorb = negativeTotal
            for (key in vaultKeys.reversed()) {
                val avail = rawRemaining[key] ?: 0.0
                if (avail > 0.0 && toAbsorb > 0.0) {
                    val absorb = minOf(avail, toAbsorb)
                    rawRemaining[key] = avail - absorb
                    toAbsorb -= absorb
                }
            }
        }

        val updatedVaults = vaultKeys.map { key ->
            val target = vaultTargets[key] ?: 0.0
            val vaultCashTxns = cycleCheckingTxns.filter { it.deducedVault == key }
            val spent = vaultCashTxns.sumOf { it.amount }
            val remaining = (rawRemaining[key] ?: 0.0).coerceAtLeast(0.0)

            val tagSummary = vaultCashTxns
                .groupBy { it.subTag }
                .mapValues { entry -> entry.value.sumOf { it.amount } }
                .entries
                .sortedByDescending { it.value }
                .take(3)
                .joinToString("  •  ") { "${it.key}: AED ${String.format("%.0f", it.value)}" }

            VaultBalanceEntity(
                vaultKey = key,
                allocatedAmount = target,
                spentAmount = spent,
                currentRemaining = remaining,
                topSubTagsSummary = tagSummary
            )
        }

        dao.updateSnapshot(
            AccountSnapshotEntity(
                id = 1,
                liveDebitBalance = detectedDebitBalance,
                creditCardLimit = creditLimit,
                creditAvailableLimit = detectedCreditAvlLimit,
                creditTotalOwed = creditTotalOwed,
                statementDue = detectedStatementDue,
                statementDueDate = detectedDueDate,
                unbilledSpend = unbilledSpend,
                ccSubTagsSummary = ccTagSummary,
                cycleStartingPool = reconstructedCycleStartPool,
                cycleLabel = cycleLabel,
                allocationMode = baselineAlloc.allocationMode
            )
        )

        dao.updateVaultBalances(updatedVaults)

        if (evaluateNudges) {
            val today = LocalDate.now()
            val payday = if (today.dayOfMonth < 22) today.withDayOfMonth(22) else today.plusMonths(1).withDayOfMonth(22)
            val daysLeft = ChronoUnit.DAYS.between(today, payday).coerceAtLeast(1)
            val safeDaily = (trueUsableCash / daysLeft.toDouble()).coerceAtLeast(0.0)
            val liveMode = DynamicAllocationEngine.getLiveModeBadge(trueUsableCash)

            PacingNudgeEngine.evaluateAndNotify(
                context = context,
                usableCash = trueUsableCash,
                safeDaily = safeDaily,
                statementDue = detectedStatementDue,
                dueDateLabel = detectedDueDate,
                activeMode = liveMode.first,
                modeSubtitle = liveMode.second,
                transactions = allTransactions
            )
        }
    }
}
