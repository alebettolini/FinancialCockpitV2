package com.bettolini.financialcockpit.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.bettolini.financialcockpit.data.SmsHistoryScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BankSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (sms in messages) {
                val sender = sms.displayOriginatingAddress ?: ""
                if (sender.contains("ENBD", true) || sender.contains("EmiratesNBD", true)) {
                    CoroutineScope(Dispatchers.IO).launch {
                        SmsHistoryScanner.scanAndSyncInbox(context.applicationContext)
                    }
                }
            }
        }
    }
}
