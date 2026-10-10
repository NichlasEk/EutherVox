package se.euther.eutherbeam.nec

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address

internal object NecNetworkDiscovery {
    fun activeSubnet(context: Context): Ipv4Subnet? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return null
        val addresses = manager.getLinkProperties(network)?.linkAddresses.orEmpty()
        return addresses.asSequence()
            .filter { it.address is Inet4Address && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress }
            .map { Ipv4Subnet.fromAddress(it.address as Inet4Address, it.prefixLength) }
            .firstOrNull()
    }
}
