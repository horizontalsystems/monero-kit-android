package io.horizontalsystems.monerokit.sample

import android.app.Application
import io.horizontalsystems.monerokit.MoneroKit
import io.horizontalsystems.monerokit.Seed
import io.horizontalsystems.monerokit.data.DefaultNodes
import timber.log.Timber

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        initKit()
        instance = this
    }

    private fun initKit() {
//        if (BuildConfig.DEBUG) {
        Timber.plant(Timber.DebugTree())
//        }
//        val walletId = "wallet-${stellarWallet.javaClass.simpleName}"
////        val walletId = UUID.randomUUID().toString()
//
//        val network = Network.MainNet
//        kit = StellarKit.getInstance(
//            stellarWallet,
//            network,
//            this,
//            walletId
//        )


        kit = MoneroKit.getInstance(
            context = this,
            seed = Seed.Bip39("".split(" "), ""),
            restoreDateOrHeight = "3435800",
            walletId = walletId,
            node = DefaultNodes.BOLDSUCK.uri,
            trustNode = true
        )
    }

    companion object {
        //        val stellarWallet = StellarWallet.WatchOnly("GADCIJ2UKQRWG6WHHPFKKLX7BYAWL7HDL54RUZO7M7UIHNQZL63C2I4Z")
        lateinit var instance: Application
            private set

        lateinit var kit: MoneroKit

        const val walletId = "wallet_id_111"
    }
}