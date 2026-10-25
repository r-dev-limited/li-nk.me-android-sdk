package me.link.sdk

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener

/** Service readiness is asynchronous; the blocking Binder read runs on an I/O worker. */
internal interface ReferrerConnection {
    fun connect(ready: (Boolean) -> Unit)
    fun read(): String?
    fun close()
}

internal class AndroidReferrerConnection(context: Context) : ReferrerConnection {
    private val client = InstallReferrerClient.newBuilder(context).build()
    override fun connect(ready: (Boolean) -> Unit) {
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                ready(responseCode == InstallReferrerClient.InstallReferrerResponse.OK)
            }
            override fun onInstallReferrerServiceDisconnected() { ready(false) }
        })
    }
    override fun read(): String? = client.installReferrer.installReferrer
    override fun close() { client.endConnection() }
}
