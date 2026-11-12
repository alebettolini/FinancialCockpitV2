package com.bettolini.financialcockpit.data

import android.content.Context
import android.provider.Telephony
import com.bettolini.financialcockpit.domain.ClassificationResult
import com.bettolini.financialcockpit.domain.DynamicAllocationEngine
import com.bettolini.financialcockpit.domain.MerchantClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object SmsHistoryScanner {

    fun getCurrentCycleStart(today: LocalDate = LocalDate.now()): LocalDate {
        return if (today.dayOfMonth >= 22) {
            today.withDayOfMonth(22)
        } else {
            today.minusMonths(1).withDayOfMonth(22)
        }
    }

    suspend fun scanAndSyncInbox(context: Context): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val dao = AppDatabase.getInstance(context).financeDao()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val cycleStartDate = getCurrentCycleStart(today)
        val cycleStartMillis = cycleStartDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val cycleLabel = "Cycle: ${cycleStartDate.format(DateTimeFormatter.ofPattern("dd MMM"))} – ${cycleStartDate.plusMonths(1).minusDays(1).format(DateTimeFormatter.ofPattern("dd MMM"))}"

        val cursor = resolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null,
            null,
            "${Telephony.Sms.DATE} ASC"
        ) ?: return@withContext 0

        var detectedDebitBalance = 6296.29
        var detectedCreditAvlLimit = 33485.39
        var lastCreditLimitTimestamp = 0L
        var detectedStatementDue = 3620.45
        var detectedDueDate = "19 Nov"

        val creditLimit = 42000.00
        val parsedTransactions = mutableListOf<TransactionEntity>()

        val debitBalanceRegex = Regex(
            """(?:Avl\.?\s*Balance|Available\s*Balance)\s*(?:is|:)?\s*(?:AED)?\s*([\d,]+\.?\d*)""",
            RegexOption.IGNORE_CASE
        )
        val creditLimitRegex = Regex(
            """(?:Avl\.?\s*(?:Cr\.?\s*)?Limit|Available\s*(?:Cr\.?\s*)?Limit)\s*(?:is|:)?\s*(?:AED)?\s*([\d,]+\.?\d*)""",
            RegexOption.IGNORE_CASE
        )
        val purchaseRegex = Regex(
            """(?:Purchase of|Debited with|Spent|Payment of)\s+(?:AED)?\s*([\d,]+\.?\d*)""",
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

        cursor.use { c ->
            val addressIndex = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIndex = c.getColumnIndex(Telephony.Sms.BODY)
            val dateIndex = c.getColumnIndex(Telephony.Sms.DATE)

            while (c.moveToNext()) {
                val address = c.getString(addressIndex) ?: ""
                val body = c.getString(bodyIndex) ?: ""
                val date = c.getLong(dateIndex)

                if (address.contains("ENBD", true) || address.contains("EmiratesNBD", true)) {
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
                        }
                        val rawDate = statementMatch.groupValues.getOrNull(2)
                        if (!rawDate.isNullOrBlank()) {
                            detectedDueDate = rawDate
                        }
                    }

                    val paymentMatch = ccPaymentRegex.find(body)
                    if (paymentMatch != null && isCredit) {
                        val paid = paymentMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
                        detectedStatementDue = (detectedStatementDue - paid).coerceAtLeast(0.0)
                    }

                    val isPaymentReceipt = body.contains("received towards", true) || body.contains("credited", true)
                    val isCcBillTransfer = body.contains("Credit Card payment", true) || body.contains("Card Payment", true)
                    val purchaseMatch = purchaseRegex.find(body)

                    if (purchaseMatch != null && !isPaymentReceipt) {
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
                            MerchantClassifier.classify(rawMerchant, body, isCredit)
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

        val creditTotalOwed = (creditLimit - detectedCreditAvlLimit).coerceAtLeast(0.0)
        val unbilledSpend = (creditTotalOwed - detectedStatementDue).coerceAtLeast(0.0)
        val trueUsableCash = (detectedDebitBalance - detectedStatementDue).coerceAtLeast(0.0)

        // Reconstruct cycle starting pool from CHECKING debits only (never inflate with unbilled CC debt)
        val cycleAllSpendTxns = parsedTransactions.filter { it.isCurrentCycle && !it.isInternalTransfer }
        val cycleCheckingSpendOnly = cycleAllSpendTxns.filter { !it.isCreditCard }.sumOf { it.amount }
        val reconstructedCycleStartPool = trueUsableCash + cycleCheckingSpendOnly

        val baselineAlloc = DynamicAllocationEngine.calculateDynamicSplit(reconstructedCycleStartPool)
        val vaultTargets = mapOf(
            "RENT" to baselineAlloc.rentVault,
            "FIXED_OVERHEAD" to baselineAlloc.fixedOverheadVault,
            "SUSTENANCE_FLEET" to baselineAlloc.sustenanceFleetVault,
            "DISCRETIONARY" to baselineAlloc.discretionaryVault,
            "TRAVEL" to baselineAlloc.travelVault,
            "WEALTH_SURPLUS" to baselineAlloc.wealthSurplusVault
        )

        val updatedVaults = vaultTargets.map { (key, target) ->
            val vaultTxns = cycleAllSpendTxns.filter { it.deducedVault == key }
            val spent = vaultTxns.sumOf { it.amount }
            val remaining = target - spent

            val tagSummary = vaultTxns
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
                cycleStartingPool = reconstructedCycleStartPool,
                cycleLabel = cycleLabel,
                allocationMode = baselineAlloc.allocationMode
            )
        )

        dao.updateVaultBalances(updatedVaults)
        dao.clearTransactions()
        dao.insertTransactions(parsedTransactions)

        return@withContext parsedTransactions.size
    }
}
