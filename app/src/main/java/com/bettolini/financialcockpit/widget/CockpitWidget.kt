package com.bettolini.financialcockpit.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.bettolini.financialcockpit.MainActivity
import com.bettolini.financialcockpit.data.AccountSnapshotEntity
import com.bettolini.financialcockpit.data.AppDatabase
import com.bettolini.financialcockpit.data.SmsHistoryScanner
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class RefreshCockpitAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        try {
            SmsHistoryScanner.scanAndSyncInbox(context.applicationContext)
        } catch (_: Exception) {
        }
        CockpitWidget().updateAll(context)
    }
}

class CockpitWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = AppDatabase.getInstance(context).financeDao()
        val snapshot = dao.getSnapshotSync()

        provideContent {
            CockpitWidgetContent(snapshot = snapshot)
        }
    }
}

@Composable
private fun CockpitWidgetContent(snapshot: AccountSnapshotEntity?) {
    val today = LocalDate.now()
    val payday = if (today.dayOfMonth < 22) today.withDayOfMonth(22) else today.plusMonths(1).withDayOfMonth(22)
    val daysLeft = ChronoUnit.DAYS.between(today, payday).coerceAtLeast(1)

    val checking = snapshot?.liveDebitBalance ?: 6296.29
    val billedDue = snapshot?.statementDue ?: 3620.45
    val unbilled = snapshot?.unbilledSpend ?: 4894.16
    val usableCash = (checking - billedDue).coerceAtLeast(0.0)
    val dailySafe = (usableCash / daysLeft.toDouble()).coerceAtLeast(0.0)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(Color(0xFF121214)))
            .cornerRadius(18.dp)
            .padding(14.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.Vertical.SpaceBetween
    ) {
        // Top Row: Title + Refresh Button
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TRUE USABLE CASH",
                style = TextStyle(
                    color = ColorProvider(Color(0xFF8E8E93)),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = GlanceModifier.defaultWeight()
            )
            Box(
                modifier = GlanceModifier
                    .background(ColorProvider(Color(0xFF1E1E22)))
                    .cornerRadius(8.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clickable(actionRunCallback<RefreshCockpitAction>()),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "↻ Sync",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF00E676)),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }

        // Center Row: Big Usable Cash + Safe Daily Pill
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = "AED ${String.format("%,.0f", usableCash)}",
                style = TextStyle(
                    color = ColorProvider(Color(0xFF00E676)),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = GlanceModifier.defaultWeight()
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "AED ${String.format("%.0f", dailySafe)}/day",
                    style = TextStyle(
                        color = ColorProvider(Color.White),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = "${daysLeft}d to 22nd",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF8E8E93)),
                        fontSize = 10.sp
                    )
                )
            }
        }

        // Bottom Row: Billed CC Status + Unbilled
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val billText = if (billedDue <= 0.01) "CC Bill: PAID ✓" else "CC Due: AED ${String.format("%,.0f", billedDue)}"
            val billColor = if (billedDue <= 0.01) Color(0xFF00E676) else Color(0xFFFF9F0A)

            Text(
                text = billText,
                style = TextStyle(
                    color = ColorProvider(billColor),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                ),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = "Unbilled: AED ${String.format("%,.0f", unbilled)}",
                style = TextStyle(
                    color = ColorProvider(Color(0xFFFF453A)),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }
    }
}

class CockpitWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CockpitWidget()
}
