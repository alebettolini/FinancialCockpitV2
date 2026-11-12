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
        var detectedCreditAvlLimit = 33449.55
        var detectedStatementDue = 3620.45
        var detectedDueDate = "19 Nov"

        val creditLimit = 42000.00
        val parsedTransactions = mutableListOf<TransactionEntity>()

        val debitBalanceRegex = Regex("""(?:Avl Balance is|Available Balance:?)\s*(?:AED)?\s*([\d,]+\.?\d*)""", RegexOption.IGNORE_CASE)
        val creditLimitRegex = Regex("""(?:Avl Limit is|Available Limit:?)\s*(?:AED)?\s*([\d,]+\.?\d*)""", RegexOption.IGNORE_CASE)
        val purchaseRegex = Regex("""(?:Purchase of|Debited with|Spent)\s+(?:AED)?\s*([\d,]+\.?\d*)""", RegexOption.IGNORE_CASE)
        val merchantRegex = Regex("""at\s+([^.]+?)\s+on""", RegexOption.IGNORE_CASE)
        val statementRegex = Regex("""(?:statement|total amt due|amount due).*?AED\s*([\d,]+\.?\d*).*?(?:due date:?\s*(\d{1,2}[/-]\d{1,2}[/-]\d{2,4}|\d{1,2}\s+\w+))?""", RegexOption.IGNORE_CASE)
        val ccPaymentRegex = Regex("""Payment of\s+AED\s*([\d,]+\.?\d*)\s+received""", RegexOption.IGNORE_CASE)

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
                            body.contains("Avl Limit", true)

                    // 1. Live Checking Account Balance
                    val debitBalMatch = debitBalanceRegex.find(body)
                    if (debitBalMatch != null && !isCredit) {
                        debitBalMatch.groupValues[1].replace(",", "").toDoubleOrNull()?.let {
                            detectedDebitBalance = it
                        }
                    }

                    // 2. Credit Card Available Limit
                    val creditLimMatch = creditLimitRegex.find(body)
                    if (creditLimMatch != null) {
                        creditLimMatch.groupValues[1].replace(",", "").toDoubleOrNull()?.let {
                            detectedCreditAvlLimit = it
                        }
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

                    // 4. Credit Card Repayment Received (reduces billed due)
                    val paymentMatch = ccPaymentRegex.find(body)
                    if (paymentMatch != null && isCredit) {
                        val paid = paymentMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
                        detectedStatementDue = (detectedStatementDue - paid).coerceAtLeast(0.0)
                    }

                    // 5. Card / Account Spend Transaction
                    val purchaseMatch = purchaseRegex.find(body)
                    if (purchaseMatch != null) {
                        val amount = purchaseMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
                        val merchant = merchantRegex.find(body)?.groupValues?.get(1)?.trim() ?: "Direct Debit"

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

        // Exact CC Debt Accounting: Total Owed = 42,000 - Avl Limit; Unbilled = Total Owed - Billed Statement Due
        val creditTotalOwed = (creditLimit - detectedCreditAvlLimit).coerceAtLeast(0.0)
        val unbilledSpend = (creditTotalOwed - detectedStatementDue).coerceAtLeast(0.0)

        // True Liquid Cash = Checking Cash - Billed Statement Due
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
