package com.bettolini.financialcockpit.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val amount: Double,
    val merchant: String,
    val isCreditCard: Boolean,
    val isInternalTransfer: Boolean = false,
    val isCurrentCycle: Boolean = true,
    val deducedVault: String,
    val subTag: String,
    val rawSms: String
)

@Entity(tableName = "vault_balances")
data class VaultBalanceEntity(
    @PrimaryKey val vaultKey: String,
    val allocatedAmount: Double,
    val spentAmount: Double,
    val currentRemaining: Double,
    val topSubTagsSummary: String = ""
)

@Entity(tableName = "account_snapshot")
data class AccountSnapshotEntity(
    @PrimaryKey val id: Int = 1,
    val liveDebitBalance: Double,
    val creditCardLimit: Double = 42000.0,
    val creditAvailableLimit: Double,
    val creditTotalOwed: Double,
    val statementDue: Double,
    val statementDueDate: String,
    val unbilledSpend: Double,
    val ccSubTagsSummary: String = "",
    val cycleStartingPool: Double,
    val cycleLabel: String,
    val allocationMode: String
)

@Entity(tableName = "merchant_rules")
data class MerchantRuleEntity(
    @PrimaryKey val merchantKey: String,
    val displayMerchant: String,
    val vaultKey: String,
    val subTag: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "cycle_snapshots")
data class CycleSnapshotEntity(
    @PrimaryKey val cycleKey: String,
    val cycleLabel: String,
    val checkingSpend: Double,
    val creditSpend: Double,
    val usableCashAtSnapshot: Double,
    val modeAtSnapshot: String,
    val topCategory: String,
    val capturedAt: Long = System.currentTimeMillis()
)

@Dao
interface FinanceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransactions(txns: List<TransactionEntity>)

    @Query("DELETE FROM transactions")
    suspend fun clearTransactions()

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC LIMIT 120")
    fun getRecentTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    suspend fun getAllTransactionsSync(): List<TransactionEntity>

    @Query("UPDATE transactions SET deducedVault = :vaultKey, subTag = :subTag WHERE id = :txnId")
    suspend fun updateSingleTransactionClassification(txnId: Long, vaultKey: String, subTag: String)

    @Query("UPDATE transactions SET deducedVault = :vaultKey, subTag = :subTag WHERE UPPER(merchant) = UPPER(:merchantName) AND isInternalTransfer = 0")
    suspend fun updateAllMatchingMerchantTransactions(merchantName: String, vaultKey: String, subTag: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateVaultBalances(balances: List<VaultBalanceEntity>)

    @Query("SELECT * FROM vault_balances")
    fun getAllVaults(): Flow<List<VaultBalanceEntity>>

    @Query("SELECT * FROM vault_balances")
    suspend fun getAllVaultsSync(): List<VaultBalanceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateSnapshot(snapshot: AccountSnapshotEntity)

    @Query("SELECT * FROM account_snapshot WHERE id = 1")
    fun getSnapshot(): Flow<AccountSnapshotEntity?>

    @Query("SELECT * FROM account_snapshot WHERE id = 1")
    suspend fun getSnapshotSync(): AccountSnapshotEntity?

    // Self-Learning Merchant Rules
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMerchantRule(rule: MerchantRuleEntity)

    @Query("SELECT * FROM merchant_rules ORDER BY updatedAt DESC")
    fun getAllMerchantRules(): Flow<List<MerchantRuleEntity>>

    @Query("SELECT * FROM merchant_rules")
    suspend fun getAllMerchantRulesSync(): List<MerchantRuleEntity>

    @Query("DELETE FROM merchant_rules WHERE merchantKey = :key")
    suspend fun deleteMerchantRule(key: String)

    // Cycle-Close History Snapshots
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCycleSnapshot(snapshot: CycleSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCycleSnapshotsIgnoreExisting(snapshots: List<CycleSnapshotEntity>)

    @Query("SELECT * FROM cycle_snapshots ORDER BY cycleKey DESC")
    fun getAllCycleSnapshots(): Flow<List<CycleSnapshotEntity>>
}

@Database(
    entities = [
        TransactionEntity::class,
        VaultBalanceEntity::class,
        AccountSnapshotEntity::class,
        MerchantRuleEntity::class,
        CycleSnapshotEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun financeDao(): FinanceDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "financial_cockpit_v2_db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
