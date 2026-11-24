package dev.netscope

import android.app.Application
import android.content.Context
import androidx.room.Room
import dev.netscope.data.AppDatabase
import dev.netscope.data.DeviceRepository
import dev.netscope.data.ScanController
import dev.netscope.data.SettingsRepository
import dev.netscope.net.NetworkInfoProvider
import dev.netscope.net.OuiDatabase
import dev.netscope.net.PortScanner
import dev.netscope.net.WifiScanner
import dev.netscope.work.Notifications

class NetScopeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
    }
}

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val database: AppDatabase = Room.databaseBuilder(appContext, AppDatabase::class.java, "netscope.db")
        .fallbackToDestructiveMigration()
        .build()
    val settings = SettingsRepository(appContext)
    val oui = OuiDatabase(appContext)
    val networkInfo = NetworkInfoProvider(appContext, oui)
    val wifiScanner = WifiScanner(appContext, oui)
    val deviceRepository = DeviceRepository(database)
    val scanController = ScanController(appContext, networkInfo, deviceRepository, oui)
    val portScanner = PortScanner()
}

val Context.container: AppContainer
    get() = (applicationContext as NetScopeApplication).container
