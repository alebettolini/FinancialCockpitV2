package com.bettolini.financialcockpit.data

import android.content.Context
import android.provider.Telephony
import com.bettolini.financialcockpit.domain.DynamicAllocationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SmsHistoryScanner {

    suspend fun scanAndSyncInbox(context: Context): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val dao = AppDatabase.getInstance(context).financeDao()

        val cursor = resolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null,
            null,
            "${Telephony.Sms.DATE} ASC"
        ) ?: return@withContext 0

        var detectedDebitBalance = 6300.00
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
            """Payment of\s+AED\s*([\d,]+\.?\d*)\s+received""",
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
                    val isCredit = body.contains("Credit Card", true) ||
                            body.contains("3445", true) ||
                            body.contains("Limit", true)

                    // 1. Live Checking Account Balance
                    val debitBalMatch = debitBalanceRegex.find(body)
                    if (debitBalMatch != null && !isCredit) {
                        debitBalMatch.groupValues[1].replace(",", "").toDoubleOrNull()?.let {
                            detectedDebitBalance = it
                        }
                    }

                    // 2. Credit Card Available Limit
                    val creditLimMatch = creditLimitRegex.find(body)
                    val parsedLimitInMsg = creditLimMatch?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
                    if (parsedLimitInMsg != null) {
                        detectedCreditAvlLimit = parsedLimitInMsg
                        lastCreditLimitTimestamp = date
                    }

                    // 3. Monthly Statement Generated
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

                    // 4. Credit Card Repayment Received
                    val paymentMatch = ccPaymentRegex.find(body)
                    if (paymentMatch != null && isCredit) {
                        val paid = paymentMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
                        detectedStatementDue = (detectedStatementDue - paid).coerceAtLeast(0.0)
                    }

                    // 5. Card / Account Spend Transaction
                    val isPaymentReceipt = body.contains("received towards", true) || body.contains("credited", true)
                    val purchaseMatch = purchaseRegex.find(body)
                    if (purchaseMatch != null && !isPaymentReceipt) {
                        val amount = purchaseMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
                        val rawMerchant = merchantRegex.find(body)?.groupValues?.get(1)?.trim()
                        val merchant = if (!rawMerchant.isNullOrBlank()) rawMerchant else if (isCredit) "Card Purchase" else "Account Debit"

                        // Fallback deduction if a CC swipe SMS arrived after the last explicit Avl Limit SMS without stating a new limit
                        if (isCredit && parsedLimitInMsg == null && date > lastCreditLimitTimestamp) {
                            detectedCreditAvlLimit = (detectedCreditAvlLimit - amount).coerceAtLeast(0.0)
                        }

                        val targetVault = when {
                            merchant.contains("ENOC", true) || merchant.contains("EPPCO", true) ||
                            merchant.contains("ADNOC", true) || merchant.contains("EMARAT", true) -> "SUSTENANCE_FLEET"

                            merchant.contains("INSTASHOP", true) || merchant.contains("GRANDIOSE", true) ||
                            merchant.contains("LULU", true) || merchant.contains("CARREFOUR", true) -> "SUSTENANCE_FLEET"

                            merchant.contains("ZYARA", true) || merchant.contains("ORTO", true) ||
                            merchant.contains("FLAT12", true) || merchant.contains("PACHA", true) ||
                            merchant.contains("GENTS SALON", true) || merchant.contains("1987", true) -> "DISCRETIONARY"

                            merchant.contains("PL-AC", true) || merchant.contains("LOAN", true) ||
                            merchant.contains("DEWA", true) || merchant.contains("FITNESS", true) -> "FIXED_OVERHEAD"

                            else -> "DISCRETIONARY"
                        }

                        parsedTransactions.add(
                            TransactionEntity(
                                timestamp = date,
                                amount = amount,
                                merchant = merchant,
                                isCreditCard = isCredit,
                                deducedVault = targetVault,
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
        val allocation = DynamicAllocationEngine.calculateDynamicSplit(trueUsableCash)

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
                allocationMode = allocation.allocationMode
            )
        )

        dao.updateVaultBalances(
            listOf(
                VaultBalanceEntity("RENT", allocation.rentVault, allocation.rentVault),
                VaultBalanceEntity("FIXED_OVERHEAD", allocation.fixedOverheadVault, allocation.fixedOverheadVault),
                VaultBalanceEntity("SUSTENANCE_FLEET", allocation.sustenanceFleetVault, allocation.sustenanceFleetVault),
                VaultBalanceEntity("DISCRETIONARY", allocation.discretionaryVault, allocation.discretionaryVault),
                VaultBalanceEntity("TRAVEL", allocation.travelVault, allocation.travelVault),
                VaultBalanceEntity("WEALTH_SURPLUS", allocation.wealthSurplusVault, allocation.wealthSurplusVault)
            )
        )

        dao.clearTransactions()
        dao.insertTransactions(parsedTransactions)

        return@withContext parsedTransactions.size
    }
}
