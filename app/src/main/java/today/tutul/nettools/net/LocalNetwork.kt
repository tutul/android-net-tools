package today.tutul.nettools.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.NetworkInterface

data class NetworkSummary(
    val transport: String,
    val interfaceName: String?,
    val addresses: List<String>,
    val gateways: List<String>,
    val dnsServers: List<String>,
    val isDefault: Boolean,
)

data class InterfaceSummary(val name: String, val addresses: List<String>)

/** Local addresses as the OS sees them. Nothing here leaves the device. */
object LocalNetwork {

    fun networks(context: Context): List<NetworkSummary> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val active = cm.activeNetwork
        @Suppress("DEPRECATION") // allNetworks is the simplest one-shot snapshot.
        return cm.allNetworks.mapNotNull { network ->
            val lp = cm.getLinkProperties(network) ?: return@mapNotNull null
            NetworkSummary(
                transport = transportName(cm.getNetworkCapabilities(network)),
                interfaceName = lp.interfaceName,
                addresses = lp.linkAddresses.map { it.toString() },
                gateways = lp.routes
                    .filter { it.isDefaultRoute && it.hasGateway() }
                    .mapNotNull { it.gateway?.hostAddress }
                    .distinct(),
                dnsServers = lp.dnsServers.mapNotNull { it.hostAddress },
                isDefault = network == active,
            )
        }.sortedByDescending { it.isDefault }
    }

    fun interfaces(): List<InterfaceSummary> =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .map { nif ->
                InterfaceSummary(
                    name = nif.name,
                    addresses = nif.interfaceAddresses.map {
                        "${it.address.hostAddress}/${it.networkPrefixLength}"
                    },
                )
            }
            .filter { it.addresses.isNotEmpty() }
            .sortedBy { it.name }

    private fun transportName(caps: NetworkCapabilities?): String = when {
        caps == null -> "Unknown"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Other"
    }
}
