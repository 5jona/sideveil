package pochita.sideveil.sensor.system

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
import pochita.sideveil.sensor.SensorLog
import java.net.Inet4Address

/**
 * Zero-polling network context tracker backed by [ConnectivityManager.registerDefaultNetworkCallback]
 * and a dedicated VPN network callback.
 */
class NetworkContextWatcher(
    private val systemContext: Context,
    private val handler: Handler,
    private val onContextChanged: (NetworkContextSnapshot) -> Unit,
) {
    private val lock = Any()
    private val activeVpnNetworks = LinkedHashSet<Network>()
    private val deduplicator = NetworkContextDeduplicator()

    /** Latest deduplicated network context snapshot. */
    @Volatile
    var snapshot: NetworkContextSnapshot = NetworkContextSnapshot()
        private set

    private var connectivityManager: ConnectivityManager? = null
    private var defaultCallback: ConnectivityManager.NetworkCallback? = null
    private var vpnCallback: ConnectivityManager.NetworkCallback? = null

    private var currentNetwork: Network? = null
    private var currentCapabilities: NetworkCapabilities? = null
    private var currentLinkProperties: LinkProperties? = null

    /** Seeds initial connectivity state and registers default + VPN network callbacks. */
    fun start() {
        val cm = systemContext.getSystemService(ConnectivityManager::class.java) ?: return
        connectivityManager = cm
        seedInitialState(cm)

        val defaultCb =
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) = updateDefaultNetwork(cm, network)

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) = updateDefaultNetwork(cm, network, caps = networkCapabilities)

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties,
                ) = updateDefaultNetwork(cm, network, props = linkProperties)

                override fun onLost(network: Network) = clearDefaultNetworkIfCurrent(network)

                override fun onUnavailable() = clearDefaultNetworkIfCurrent(null)
            }

        runCatching {
            cm.registerDefaultNetworkCallback(defaultCb, handler)
            defaultCallback = defaultCb
        }.onFailure { SensorLog.warn("Failed to register default network callback", it) }

        registerVpnCallback(cm)
    }

    /** Unregisters all network callbacks and clears tracked state. */
    fun stop() {
        val cm = connectivityManager
        defaultCallback?.let { cb ->
            runCatching { cm?.unregisterNetworkCallback(cb) }
                .onFailure { SensorLog.warn("Failed to unregister default network callback", it) }
        }
        vpnCallback?.let { cb ->
            runCatching { cm?.unregisterNetworkCallback(cb) }
                .onFailure { SensorLog.warn("Failed to unregister VPN network callback", it) }
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
        }.onFailure { SensorLog.warn("Failed to register VPN network callback", it) }
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
        }.onFailure { SensorLog.warn("Failed to seed initial network context", it) }
    }

    private fun updateDefaultNetwork(
        cm: ConnectivityManager,
        network: Network,
        caps: NetworkCapabilities? = null,
        props: LinkProperties? = null,
    ) {
        synchronized(lock) {
            val switched = currentNetwork != network
            currentNetwork = network
            if (caps != null) {
                currentCapabilities = caps
            } else if (switched || currentCapabilities == null) {
                currentCapabilities = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
            }
            if (props != null) {
                currentLinkProperties = props
            } else if (switched || currentLinkProperties == null) {
                currentLinkProperties = runCatching { cm.getLinkProperties(network) }.getOrNull()
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
        if (cleared) recomputeAndEmit(notify = true)
    }

    private fun recomputeAndEmit(notify: Boolean) {
        val updated =
            synchronized(lock) {
                buildSnapshot(currentCapabilities, currentLinkProperties, activeVpnNetworks.isNotEmpty())
            }
        if (deduplicator.accept(updated)) {
            snapshot = updated
            SensorLog.info(
                "Network context: transport=${updated.transport} vpn=${updated.vpnActive} " +
                    "validated=${updated.validated} iface=${updated.interfaceName.orEmpty()}",
            )
            if (notify) onContextChanged(updated)
        }
    }

    companion object {
        private const val UNKNOWN_SSID = "<unknown ssid>"

        internal fun buildSnapshot(
            caps: NetworkCapabilities?,
            props: LinkProperties?,
            hasActiveVpn: Boolean,
        ): NetworkContextSnapshot {
            if (caps == null) return NetworkContextSnapshot(vpnActive = hasActiveVpn)
            return NetworkContextSnapshot(
                transport = resolveTransport(caps),
                vpnActive = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || hasActiveVpn,
                validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                interfaceName = props?.interfaceName?.takeIf { it.isNotBlank() },
                ssid = sanitizeSsid((caps.transportInfo as? WifiInfo)?.ssid),
                gateway = extractDefaultIpv4Gateway(props),
                localIpv4 = extractLocalIpv4(props),
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
            return unquoted.takeUnless { it.isEmpty() || it.equals(UNKNOWN_SSID, ignoreCase = true) }
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

/** Suppresses duplicate consecutive [NetworkContextSnapshot] emissions. */
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
