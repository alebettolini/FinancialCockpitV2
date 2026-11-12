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
    val deducedVault: String,
    val rawSms: String
)

@Entity(tableName = "vault_balances")
data class VaultBalanceEntity(
    @PrimaryKey val vaultKey: String,
    val allocatedAmount: Double,
    val currentRemaining: Double
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
    val allocationMode: String
)

@Dao
interface FinanceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransactions(txns: List<TransactionEntity>)

    @Query("DELETE FROM transactions")
    suspend fun clearTransactions()

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC LIMIT 25")
    fun getRecentTransactions(): Flow<List<TransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateVaultBalances(balances: List<VaultBalanceEntity>)

    @Query("SELECT * FROM vault_balances")
    fun getAllVaults(): Flow<List<VaultBalanceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateSnapshot(snapshot: AccountSnapshotEntity)

    @Query("SELECT * FROM account_snapshot WHERE id = 1")
    fun getSnapshot(): Flow<AccountSnapshotEntity?>
}

@Database(
    entities = [TransactionEntity::class, VaultBalanceEntity::class, AccountSnapshotEntity::class],
    version = 1,
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
