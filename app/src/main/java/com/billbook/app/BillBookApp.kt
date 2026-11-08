package com.billbook.app

import android.app.Application
import com.billbook.app.bank.BankAlertRepository
import com.billbook.app.data.AppDatabase
import com.billbook.app.data.AppSettings

class BillBookApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.build(this) { settings.currency } }
    val settings: AppSettings by lazy { AppSettings(this) }
    val bankAlerts: BankAlertRepository by lazy { BankAlertRepository(this) }
}
