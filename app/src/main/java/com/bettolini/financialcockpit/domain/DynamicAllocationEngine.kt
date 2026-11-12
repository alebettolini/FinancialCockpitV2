package com.bettolini.financialcockpit.domain

data class VaultAllocation(
    val rentVault: Double,
    val fixedOverheadVault: Double,
    val sustenanceFleetVault: Double,
    val discretionaryVault: Double,
    val travelVault: Double,
    val wealthSurplusVault: Double,
    val allocationMode: String
)

object DynamicAllocationEngine {

    const val AUTO_LOAN_PAYMENT = 1594.00
    const val FIXED_OVERHEAD_FULL = 2554.00
    const val SUSTENANCE_FLEET_FULL = 2750.00
    const val DISCRETIONARY_FULL = 1200.00
    const val RENT_TARGET = 5000.00
    const val TRAVEL_TARGET = 1500.00

    fun calculateDynamicSplit(availableCash: Double): VaultAllocation {
        val safeCash = availableCash.coerceAtLeast(0.0)
        return when {
            // DEFCON 1: Under 2,000 AED - Pure Sustenance & Commute Floor
            safeCash <= 2000.0 -> {
                VaultAllocation(
                    rentVault = 0.0,
                    fixedOverheadVault = 0.0,
                    sustenanceFleetVault = safeCash,
                    discretionaryVault = 0.0,
                    travelVault = 0.0,
                    wealthSurplusVault = 0.0,
                    allocationMode = "DEFCON 1: Essential Sustenance Only"
                )
            }

            // DEFCON 2: 2,001 to 4,500 AED - Protect Auto Loan & Keep Wheels Moving
            safeCash <= 4500.0 -> {
                val toOverhead = AUTO_LOAN_PAYMENT.coerceAtMost(safeCash)
                val remaining = (safeCash - toOverhead).coerceAtLeast(0.0)
                VaultAllocation(
                    rentVault = 0.0,
                    fixedOverheadVault = toOverhead,
                    sustenanceFleetVault = remaining,
                    discretionaryVault = 0.0,
                    travelVault = 0.0,
                    wealthSurplusVault = 0.0,
                    allocationMode = "DEFCON 2: Debt Service & Commute"
                )
            }

            // DEFCON 3: 4,501 to 8,000 AED - Normalizing Living
            safeCash <= 8000.0 -> {
                val overhead = FIXED_OVERHEAD_FULL
                val sustenance = 2300.00.coerceAtMost((safeCash - overhead).coerceAtLeast(0.0))
                val pool = (safeCash - overhead - sustenance).coerceAtLeast(0.0)

                VaultAllocation(
                    rentVault = pool * 0.50,
                    fixedOverheadVault = overhead,
                    sustenanceFleetVault = sustenance,
                    discretionaryVault = pool * 0.50,
                    travelVault = 0.0,
                    wealthSurplusVault = 0.0,
                    allocationMode = "DEFCON 3: Sustenance & Baseline Buffer"
                )
            }

            // BRIDGE MODE: 8,001 to 15,499 AED (Pre-raise net salary ~AED 9,750)
            safeCash < 15500.0 -> {
                val overhead = FIXED_OVERHEAD_FULL
                val sustenance = SUSTENANCE_FLEET_FULL
                var remaining = (safeCash - overhead - sustenance).coerceAtLeast(0.0)

                val rent = 2500.00.coerceAtMost(remaining)
                remaining -= rent

                val discretionary = 800.00.coerceAtMost(remaining)
                remaining -= discretionary

                val travel = 500.00.coerceAtMost(remaining)
                remaining -= travel

                VaultAllocation(
                    rentVault = rent,
                    fixedOverheadVault = overhead,
                    sustenanceFleetVault = sustenance,
                    discretionaryVault = discretionary,
                    travelVault = travel,
                    wealthSurplusVault = remaining.coerceAtLeast(0.0),
                    allocationMode = "Bridge Mode (Pre-Raise Baseline)"
                )
            }

            // TARGET ARCHITECTURE: 15,500+ AED (Full AED 17k+ Raise)
            else -> {
                var remaining = (safeCash - FIXED_OVERHEAD_FULL - SUSTENANCE_FLEET_FULL).coerceAtLeast(0.0)

                val rent = RENT_TARGET.coerceAtMost(remaining)
                remaining -= rent

                val discretionary = DISCRETIONARY_FULL.coerceAtMost(remaining)
                remaining -= discretionary

                val travel = TRAVEL_TARGET.coerceAtMost(remaining)
                remaining -= travel

                VaultAllocation(
                    rentVault = rent,
                    fixedOverheadVault = FIXED_OVERHEAD_FULL,
                    sustenanceFleetVault = SUSTENANCE_FLEET_FULL,
                    discretionaryVault = discretionary,
                    travelVault = travel,
                    wealthSurplusVault = remaining.coerceAtLeast(0.0),
                    allocationMode = "Target Wealth & Growth Architecture"
                )
            }
        }
    }
}
