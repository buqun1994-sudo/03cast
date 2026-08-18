package com.tcrrry.desktopcast.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

class NsdServiceManager(context: Context) {
    private val appContext = context.applicationContext
    private val backend = AndroidNsdBackend(
        appContext.getSystemService(Context.NSD_SERVICE) as NsdManager,
    )
    private val registrar = SequentialMdnsRegistrar(backend)
    private var multicastLock: WifiManager.MulticastLock? = null

    fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("03cast-airplay").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    fun registerAirPlayPair(
        raopName: String,
        airplayName: String,
        port: Int,
        raopTxt: Map<String, String>,
        airplayTxt: Map<String, String>,
        onComplete: (MdnsRegistrationResult) -> Unit,
    ) {
        registrar.start(
            raop = MdnsServiceSpec(raopName, RAOP_TYPE, port, raopTxt),
            airplay = MdnsServiceSpec(airplayName, AIRPLAY_TYPE, port, airplayTxt),
            onComplete = onComplete,
        )
    }

    fun release() {
        registrar.stop()
        multicastLock?.let { lock -> if (lock.isHeld) lock.release() }
        multicastLock = null
    }

    private class AndroidNsdBackend(
        private val manager: NsdManager,
    ) : MdnsRegistrationBackend {
        private class AndroidHandle(
            val listener: NsdManager.RegistrationListener,
        ) : MdnsRegistrationBackend.Handle

        override fun register(
            spec: MdnsServiceSpec,
            callback: MdnsRegistrationBackend.Callback,
        ): MdnsRegistrationBackend.Handle {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = spec.name
                serviceType = spec.type
                port = spec.port
                spec.attributes.forEach { (key, value) -> setAttribute(key, value) }
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.i(TAG, "mDNS registered ${spec.type}: ${info.serviceName}")
                    callback.registered()
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "mDNS registration failed ${spec.type}: $errorCode")
                    callback.failed(errorCode)
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    Log.i(TAG, "mDNS unregistered ${spec.type}")
                }

                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS unregister failed ${spec.type}: $errorCode")
                }
            }
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            return AndroidHandle(listener)
        }

        override fun unregister(handle: MdnsRegistrationBackend.Handle) {
            val listener = (handle as? AndroidHandle)?.listener ?: return
            runCatching { manager.unregisterService(listener) }
                .onFailure { Log.w(TAG, "mDNS unregister threw: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "NsdServiceManager"
        private const val RAOP_TYPE = "_raop._tcp"
        private const val AIRPLAY_TYPE = "_airplay._tcp"
    }
}
