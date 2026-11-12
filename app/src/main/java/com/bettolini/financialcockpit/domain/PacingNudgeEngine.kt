package com.bettolini.financialcockpit.domain

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bettolini.financialcockpit.MainActivity
import com.bettolini.financialcockpit.data.TransactionEntity

object PacingNudgeEngine {

    private const val CHANNEL_ID = "cockpit_pacing_nudges"
    private const val COOLDOWN_MS = 24L * 60L * 60L * 1000L

    private fun modeRank(mode: String): Int = when (mode) {
        "TACTICAL CRUISE" -> 1
        "STEADY RUNWAY" -> 2
        "CRUISING ALTITUDE" -> 3
        "BRIDGE VELOCITY" -> 4
        "APEX WEALTH MODE" -> 5
        else -> 0
    }

    fun evaluateAndNotify(
        context: Context,
        usableCash: Double,
        safeDaily: Double,
        statementDue: Double,
        dueDateLabel: String,
        activeMode: String,
        modeSubtitle: String,
        transactions: List<TransactionEntity>
    ) {
        val appCtx = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                appCtx,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val prefs = appCtx.getSharedPreferences("cockpit_nudge_prefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        // 1. Operational Mode Level-Up Celebration
        val prevMode = prefs.getString("last_known_mode", null)
        if (prevMode != null && modeRank(activeMode) > modeRank(prevMode)) {
            sendNotification(
                context = appCtx,
                id = 1001,
                title = "Cockpit Mode: $activeMode",
                body = "Usable cash reached AED ${String.format("%,.0f", usableCash)} • $modeSubtitle"
            )
        }
        prefs.edit().putString("last_known_mode", activeMode).apply()

        // 2. Unpaid Credit Card Statement Reminder (once per statement amount)
        if (statementDue > 1.0) {
            val stmtSig = "${String.format("%.2f", statementDue)}_$dueDateLabel"
            val lastStmtSig = prefs.getString("last_notified_stmt", "")
            if (stmtSig != lastStmtSig) {
                prefs.edit().putString("last_notified_stmt", stmtSig).apply()
                sendNotification(
                    context = appCtx,
                    id = 1002,
                    title = "Card 3445 Statement Due ($dueDateLabel)",
                    body = "AED ${String.format("%,.2f", statementDue)} is reserved from your checking balance."
                )
            }
        }

        // 3. 3-Day Rolling Cash Burn Rate Nudge (max once per 24h)
        val threeDaysAgo = now - (3L * 24L * 60L * 60L * 1000L)
        val last3DaysCashSpend = transactions
            .filter { !it.isCreditCard && !it.isInternalTransfer && it.timestamp >= threeDaysAgo }
            .sumOf { it.amount }
        val rollingDailyPace = last3DaysCashSpend / 3.0
        val lastPaceNudge = prefs.getLong("last_pace_nudge_ts", 0L)

        if (safeDaily > 10.0 && rollingDailyPace > safeDaily * 1.25 && (now - lastPaceNudge) > COOLDOWN_MS) {
            prefs.edit().putLong("last_pace_nudge_ts", now).apply()
            sendNotification(
                context = appCtx,
                id = 1003,
                title = "Pacing Check: AED ${String.format("%.0f", rollingDailyPace)}/day",
                body = "Your 3-day cash spend is above your AED ${String.format("%.0f", safeDaily)}/day runway target."
            )
        }
    }

    private fun sendNotification(context: Context, id: Int, title: String, body: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cockpit Pacing & Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(id, notification)
    }
}
