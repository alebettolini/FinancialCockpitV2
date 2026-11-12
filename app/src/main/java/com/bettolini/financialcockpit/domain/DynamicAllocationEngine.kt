package com.bettolini.financialcockpit.domain

data class VaultAllocation(
    val rentVault: Double,
    val fixedOverheadVault: Double,
    val sustenanceFleetVault: Double,
    val discretionaryVault: Double,
    val travelVault: Double,
    val wealthSurplusVault: Double,
    val allocationMode: String,
    val modeSubtitle: String
)

object DynamicAllocationEngine {

    const val AUTO_LOAN_PAYMENT = 1594.00
    const val FIXED_OVERHEAD_FULL = 2554.00
    const val SUSTENANCE_FLEET_FULL = 2750.00
    const val DISCRETIONARY_FULL = 1200.00
    const val RENT_TARGET = 5000.00
    const val TRAVEL_TARGET = 1500.00

    fun getLiveModeBadge(usableCash: Double): Pair<String, String> {
        val safe = usableCash.coerceAtLeast(0.0)
        return when {
            safe < 1500.0 -> "TACTICAL CRUISE" to "Essentials & Commute Only"
            safe < 4500.0 -> "STEADY RUNWAY" to "Bills Covered • Lean Daily Pace"
            safe < 8000.0 -> "CRUISING ALTITUDE" to "Full Sustenance & Buffer"
            safe < 15500.0 -> "BRIDGE VELOCITY" to "Pre-Raise Surplus Mode"
            else -> "APEX WEALTH MODE" to "Full Architecture + Rent Vault"
        }
    }

    fun calculateDynamicSplit(availableCash: Double): VaultAllocation {
        val safeCash = availableCash.coerceAtLeast(0.0)
        return when {
            // Tier 1: Under 1,500 AED - Pure Sustenance & Commute
            safeCash < 1500.0 -> {
                VaultAllocation(
                    rentVault = 0.0,
                    fixedOverheadVault = 0.0,
                    sustenanceFleetVault = safeCash,
                    discretionaryVault = 0.0,
                    travelVault = 0.0,
                    wealthSurplusVault = 0.0,
                    allocationMode = "TACTICAL CRUISE",
                    modeSubtitle = "Essentials & Commute Only"
                )
            }

            // Tier 2: 1,500 to 4,499 AED - Debt Service & Commute Runway (No Rent Lock)
            safeCash < 4500.0 -> {
                val toOverhead = AUTO_LOAN_PAYMENT.coerceAtMost(safeCash)
                val remaining = (safeCash - toOverhead).coerceAtLeast(0.0)
                VaultAllocation(
                    rentVault = 0.0,
                    fixedOverheadVault = toOverhead,
                    sustenanceFleetVault = remaining,
                    discretionaryVault = 0.0,
                    travelVault = 0.0,
                    wealthSurplusVault = 0.0,
                    allocationMode = "STEADY RUNWAY",
                    modeSubtitle = "Bills Covered • Lean Daily Pace"
                )
            }

            // Tier 3: 4,500 to 7,999 AED - Full Overhead + Sustenance & Discretionary (No Rent Lock)
            safeCash < 8000.0 -> {
                val overhead = FIXED_OVERHEAD_FULL.coerceAtMost(safeCash)
                val afterOverhead = (safeCash - overhead).coerceAtLeast(0.0)
                val sustenance = (afterOverhead * 0.72).coerceAtMost(SUSTENANCE_FLEET_FULL)
                val discretionary = (afterOverhead - sustenance).coerceAtLeast(0.0)

                VaultAllocation(
                    rentVault = 0.0,
                    fixedOverheadVault = overhead,
                    sustenanceFleetVault = sustenance,
                    discretionaryVault = discretionary,
                    travelVault = 0.0,
                    wealthSurplusVault = 0.0,
                    allocationMode = "CRUISING ALTITUDE",
                    modeSubtitle = "Full Sustenance & Buffer"
                )
            }

            // Tier 4: 8,000 to 15,499 AED - Bridge Velocity (Pre-Raise ~AED 9,750 Salary, No Rent Lock)
            safeCash < 15500.0 -> {
                val overhead = FIXED_OVERHEAD_FULL
                val sustenance = SUSTENANCE_FLEET_FULL
                var remaining = (safeCash - overhead - sustenance).coerceAtLeast(0.0)

                val discretionary = DISCRETIONARY_FULL.coerceAtMost(remaining)
                remaining -= discretionary

                val travel = 1000.00.coerceAtMost(remaining)
                remaining -= travel

                VaultAllocation(
                    rentVault = 0.0,
                    fixedOverheadVault = overhead,
                    sustenanceFleetVault = sustenance,
                    discretionaryVault = discretionary,
                    travelVault = travel,
                    wealthSurplusVault = remaining.coerceAtLeast(0.0),
                    allocationMode = "BRIDGE VELOCITY",
                    modeSubtitle = "Pre-Raise Surplus Mode"
                )
            }

            // Tier 5: 15,500+ AED - Apex Wealth Mode (Post-Raise AED 17k+ Salary, Rent Vault Active)
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
                    allocationMode = "APEX WEALTH MODE",
                    modeSubtitle = "Full Architecture + Rent Vault"
                )
            }
        }
    }
}
