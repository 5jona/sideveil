package pochita.sideveil.sensor

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Handler
import pochita.sideveil.protocol.NetworkContextSnapshot
import pochita.sideveil.protocol.NetworkTransportKind
import java.net.Inet4Address

/**
 * Zero-polling network context tracker backed by [ConnectivityManager.registerDefaultNetworkCallback]
 * and a dedicated VPN network callback.
 */
internal class NetworkContextWatcher(
    private val systemContext: Context,
    private val handler: Handler,
    private val onContextChanged: (NetworkContextSnapshot) -> Unit,
) {
    private val lock = Any()
    private val activeVpnNetworks = LinkedHashSet<Network>()
    private val deduplicator = NetworkContextDeduplicator()

    @Volatile
    var snapshot: NetworkContextSnapshot = NetworkContextSnapshot()
        private set

    private var connectivityManager: ConnectivityManager? = null
    private var defaultCallback: ConnectivityManager.NetworkCallback? = null
    private var vpnCallback: ConnectivityManager.NetworkCallback? = null

    private var currentNetwork: Network? = null
    private var currentCapabilities: NetworkCapabilities? = null
    private var currentLinkProperties: LinkProperties? = null

    fun start() {
        val cm = systemContext.getSystemService(ConnectivityManager::class.java) ?: return
        connectivityManager = cm
        seedInitialState(cm)

        val defaultCb =
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) {
                    updateDefaultNetwork(cm, network, null, null)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    updateDefaultNetwork(cm, network, networkCapabilities, null)
                }

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties,
                ) {
                    updateDefaultNetwork(cm, network, null, linkProperties)
                }

                override fun onLost(network: Network) {
                    clearDefaultNetworkIfCurrent(network)
                }

                override fun onUnavailable() {
                    clearDefaultNetworkIfCurrent(null)
                }
            }

        runCatching {
            cm.registerDefaultNetworkCallback(defaultCb, handler)
            defaultCallback = defaultCb
        }.onFailure { err ->
            SensorLog.warn("Failed to register default network callback", err)
        }

        registerVpnCallback(cm)
    }

    fun stop() {
        val cm = connectivityManager
        defaultCallback?.let { cb ->
            runCatching { cm?.unregisterNetworkCallback(cb) }
                .onFailure { err -> SensorLog.warn("Failed to unregister default network callback", err) }
        }
        vpnCallback?.let { cb ->
            runCatching { cm?.unregisterNetworkCallback(cb) }
                .onFailure { err -> SensorLog.warn("Failed to unregister VPN network callback", err) }
        }
        defaultCallback = null
        vpnCallback = null
        connectivityManager = null
        synchronized(lock) {
            activeVpnNetworks.clear()
            currentNetwork = null
            currentCapabilities = null
            currentLinkProperties = null
        }
    }

    private fun registerVpnCallback(cm: ConnectivityManager) {
        val vpnRequest =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
        val vpnCb =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    synchronized(lock) { activeVpnNetworks.add(network) }
                    recomputeAndEmit(notify = true)
                }

                override fun onLost(network: Network) {
                    synchronized(lock) { activeVpnNetworks.remove(network) }
                    recomputeAndEmit(notify = true)
                }
            }
        runCatching {
            cm.registerNetworkCallback(vpnRequest, vpnCb, handler)
            vpnCallback = vpnCb
        }.onFailure { err ->
            SensorLog.warn("Failed to register VPN network callback", err)
        }
    }

    private fun seedInitialState(cm: ConnectivityManager) {
        runCatching {
            val active = cm.activeNetwork
            synchronized(lock) {
                currentNetwork = active
                currentCapabilities = active?.let(cm::getNetworkCapabilities)
                currentLinkProperties = active?.let(cm::getLinkProperties)
            }
            recomputeAndEmit(notify = false)
        }.onFailure { err ->
            SensorLog.warn("Failed to seed initial network context", err)
        }
    }

    private fun updateDefaultNetwork(
        cm: ConnectivityManager,
        network: Network,
        caps: NetworkCapabilities?,
        props: LinkProperties?,
    ) {
        synchronized(lock) {
            val switched = currentNetwork != network
            currentNetwork = network
            currentCapabilities =
                caps ?: if (switched || currentCapabilities == null) {
                    runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
                } else {
                    currentCapabilities
                }
            currentLinkProperties =
                props ?: if (switched || currentLinkProperties == null) {
                    runCatching { cm.getLinkProperties(network) }.getOrNull()
                } else {
                    currentLinkProperties
                }
        }
        recomputeAndEmit(notify = true)
    }

    private fun clearDefaultNetworkIfCurrent(lost: Network?) {
        val cleared =
            synchronized(lock) {
                if (lost != null && currentNetwork != null && currentNetwork != lost) {
                    false
                } else {
                    currentNetwork = null
                    currentCapabilities = null
                    currentLinkProperties = null
                    true
                }
            }
        if (cleared) {
            recomputeAndEmit(notify = true)
        }
    }

    private fun recomputeAndEmit(notify: Boolean) {
        val updated =
            synchronized(lock) {
                buildSnapshot(
                    caps = currentCapabilities,
                    props = currentLinkProperties,
                    hasActiveVpn = activeVpnNetworks.isNotEmpty(),
                )
            }
        if (deduplicator.accept(updated)) {
            snapshot = updated
            SensorLog.info(
                "Network context: transport=${updated.transport} vpn=${updated.vpnActive} " +
                    "validated=${updated.validated} iface=${updated.interfaceName.orEmpty()}",
            )
            if (notify) {
                onContextChanged(updated)
            }
        }
    }

    companion object {
        private const val UNKNOWN_SSID = "<unknown ssid>"

        internal fun buildSnapshot(
            caps: NetworkCapabilities?,
            props: LinkProperties?,
            hasActiveVpn: Boolean,
        ): NetworkContextSnapshot {
            if (caps == null) {
                return NetworkContextSnapshot(vpnActive = hasActiveVpn)
            }
            val transport = resolveTransport(caps)
            val vpnActive = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || hasActiveVpn
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            val interfaceName = props?.interfaceName?.takeIf { it.isNotBlank() }
            val ssid = sanitizeSsid((caps.transportInfo as? WifiInfo)?.ssid)
            val gateway = extractDefaultIpv4Gateway(props)
            val localIpv4 = extractLocalIpv4(props)
            return NetworkContextSnapshot(
                transport = transport,
                vpnActive = vpnActive,
                validated = validated,
                metered = metered,
                interfaceName = interfaceName,
                ssid = ssid,
                gateway = gateway,
                localIpv4 = localIpv4,
            )
        }

        internal fun resolveTransport(caps: NetworkCapabilities): NetworkTransportKind =
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransportKind.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransportKind.ETHERNET
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransportKind.CELLULAR
                else -> NetworkTransportKind.OTHER
            }

        internal fun sanitizeSsid(raw: String?): String? {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val unquoted =
                if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
                    trimmed.substring(1, trimmed.length - 1)
                } else {
                    trimmed
                }
            if (unquoted.isEmpty() || unquoted.equals(UNKNOWN_SSID, ignoreCase = true)) {
                return null
            }
            return unquoted
        }

        private fun extractDefaultIpv4Gateway(props: LinkProperties?): String? =
            props
                ?.routes
                ?.asSequence()
                ?.filter { it.isDefaultRoute }
                ?.mapNotNull { it.gateway as? Inet4Address }
                ?.firstOrNull { !it.isAnyLocalAddress }
                ?.hostAddress

        private fun extractLocalIpv4(props: LinkProperties?): String? =
            props
                ?.linkAddresses
                ?.asSequence()
                ?.mapNotNull { it.address as? Inet4Address }
                ?.firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }
                ?.hostAddress
    }
}

internal class NetworkContextDeduplicator(
    initial: NetworkContextSnapshot? = null,
) {
    private var lastSnapshot: NetworkContextSnapshot? = initial

    fun accept(next: NetworkContextSnapshot): Boolean {
        if (lastSnapshot == next) return false
        lastSnapshot = next
        return true
    }
}
