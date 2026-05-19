package com.wangxiuwen.coursebox.core.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * LocalSend v2 peer discovery, dual-track:
 *   - UDP multicast on 224.0.0.167:53317 — announce + listen, periodic
 *     (every 5s) plus a burst of 3 on startup. Falls back to subnet
 *     broadcast for routers that don't pass multicast.
 *   - mDNS `_localsend._tcp.` via Android NsdManager — register self,
 *     browse for peers, TXT records carry the InfoDto.
 *
 * Whichever channel sees a peer first fires [onPeer] with the discovered
 * host/port/InfoDto. The ShareScreen aggregates by fingerprint so a peer
 * that announces on both channels still shows up once.
 *
 * Ported from 599player's LocalSendDiscovery; trimmed (no LocalSendClient
 * register call on announce — coursebox uses HTTP not HTTPS and the
 * sender's prepare-upload doubles as a "I exist" register).
 */
private const val TAG = "LocalSendDiscovery"
private const val MDNS_SERVICE = "_localsend._tcp."

class LocalSendDiscovery(
    private val ctx: Context,
    private val selfInfo: () -> InfoDto,
    private val onPeer: (host: String, port: Int, info: InfoDto) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: DatagramSocket? = null
    private var listenJob: Job? = null
    private var announceJob: Job? = null
    private var nsdMgr: NsdManager? = null
    private var nsdRegListener: NsdManager.RegistrationListener? = null
    private var nsdDiscListener: NsdManager.DiscoveryListener? = null

    fun start() {
        startUdp()
        startMdns()
    }

    fun stop() {
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        listenJob?.cancel(); listenJob = null
        announceJob?.cancel(); announceJob = null
        scope.coroutineContext.cancelChildren()
        try {
            nsdRegListener?.let { nsdMgr?.unregisterService(it) }
            nsdDiscListener?.let { nsdMgr?.stopServiceDiscovery(it) }
        } catch (_: Throwable) {}
        nsdRegListener = null
        nsdDiscListener = null
    }

    // ============================== UDP ==============================

    private fun startUdp() {
        if (socket != null) return
        try {
            val ds = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(LocalSend.MULTICAST_PORT))
            }
            socket = ds
            listenJob = scope.launch { udpListenLoop(ds) }
            announceJob = scope.launch {
                repeat(3) {
                    sendAnnounce(ds, true)
                    delay(800)
                }
                while (isActive) {
                    sendAnnounce(ds, true)
                    delay(5000)
                }
            }
            Log.i(TAG, "udp started :${LocalSend.MULTICAST_PORT}")
        } catch (e: Throwable) {
            Log.w(TAG, "udp start failed: ${e.message}")
            stop()
        }
    }

    private fun sendAnnounce(ds: DatagramSocket, announceFlag: Boolean) {
        try {
            val payload = selfInfo()
                .copy(announce = announceFlag)
                .toJson()
                .apply { put("announcement", announceFlag) }   // v1 compat
                .toString().toByteArray(Charsets.UTF_8)
            try {
                ds.send(
                    DatagramPacket(
                        payload, payload.size,
                        InetAddress.getByName(LocalSend.MULTICAST_GROUP),
                        LocalSend.MULTICAST_PORT,
                    ),
                )
            } catch (e: Throwable) {
                Log.w(TAG, "mcast send: ${e.message}")
            }
            for (nic in activeIfaces()) {
                for (ia in nic.interfaceAddresses) {
                    val baddr = ia.broadcast ?: continue
                    try {
                        ds.send(DatagramPacket(payload, payload.size, baddr, LocalSend.MULTICAST_PORT))
                    } catch (_: Throwable) {}
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "sendAnnounce: ${e.message}")
        }
    }

    private fun udpListenLoop(ds: DatagramSocket) {
        val buf = ByteArray(4096)
        while (scope.isActive && !ds.isClosed) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                ds.receive(pkt)
                val raw = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                val obj = try { JSONObject(raw) } catch (_: Throwable) { continue }
                val info = InfoDto.parse(obj)
                if (info.fingerprint.isEmpty()) continue
                if (info.fingerprint == selfInfo().fingerprint) continue  // own echo
                val host = pkt.address?.hostAddress ?: continue
                onPeer(host, info.port, info)
                if (info.announce == true) {
                    sendAnnounce(ds, false)
                }
            } catch (e: Throwable) {
                if (!ds.isClosed) Log.w(TAG, "udp listen: ${e.message}")
            }
        }
    }

    private fun activeIfaces(): List<NetworkInterface> = try {
        NetworkInterface.getNetworkInterfaces().toList().filter {
            try {
                it.isUp && !it.isLoopback && it.supportsMulticast() &&
                    it.inetAddresses.toList().any { a ->
                        !a.isLoopbackAddress && a is java.net.Inet4Address
                    }
            } catch (_: Throwable) { false }
        }
    } catch (_: Throwable) { emptyList() }

    // ============================== mDNS ==============================

    private fun startMdns() {
        try {
            val mgr = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            nsdMgr = mgr
            val self = selfInfo()
            val info = NsdServiceInfo().apply {
                serviceName = self.alias.ifBlank { "coursebox" }.take(63)
                serviceType = MDNS_SERVICE
                port = self.port
                setAttribute("alias", self.alias)
                setAttribute("version", self.version)
                setAttribute("fingerprint", self.fingerprint)
                setAttribute("deviceModel", self.deviceModel ?: "")
                setAttribute("deviceType", self.deviceType.wire)
                setAttribute("download", if (self.download) "1" else "0")
                setAttribute("protocol", self.protocol)
            }
            val regL = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(p0: NsdServiceInfo?, code: Int) {
                    Log.w(TAG, "mdns register failed: code=$code")
                }
                override fun onUnregistrationFailed(p0: NsdServiceInfo?, code: Int) {}
                override fun onServiceRegistered(p0: NsdServiceInfo?) {
                    Log.i(TAG, "mdns registered: ${p0?.serviceName}")
                }
                override fun onServiceUnregistered(p0: NsdServiceInfo?) {}
            }
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, regL)
            nsdRegListener = regL

            val discL = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(p0: String?, code: Int) {
                    Log.w(TAG, "mdns discovery start failed: code=$code")
                }
                override fun onStopDiscoveryFailed(p0: String?, code: Int) {}
                override fun onDiscoveryStarted(p0: String?) { Log.i(TAG, "mdns discovery started") }
                override fun onDiscoveryStopped(p0: String?) {}
                override fun onServiceFound(svc: NsdServiceInfo) {
                    if (svc.serviceType.trim('.') != MDNS_SERVICE.trim('.')) return
                    mgr.resolveService(svc, makeResolveListener())
                }
                override fun onServiceLost(p0: NsdServiceInfo?) {}
            }
            mgr.discoverServices(MDNS_SERVICE, NsdManager.PROTOCOL_DNS_SD, discL)
            nsdDiscListener = discL
        } catch (e: Throwable) {
            Log.w(TAG, "mdns start failed: ${e.message}")
        }
    }

    private fun makeResolveListener(): NsdManager.ResolveListener =
        object : NsdManager.ResolveListener {
            override fun onResolveFailed(p0: NsdServiceInfo?, code: Int) {
                Log.w(TAG, "mdns resolve failed: code=$code")
            }
            override fun onServiceResolved(svc: NsdServiceInfo) {
                val host = svc.host?.hostAddress ?: return
                val port = svc.port
                val attrs = svc.attributes
                fun txt(k: String): String? = attrs[k]?.let { String(it, Charsets.UTF_8) }
                val fp = txt("fingerprint") ?: ""
                if (fp.isEmpty()) return
                if (fp == selfInfo().fingerprint) return
                val info = InfoDto(
                    alias = txt("alias") ?: svc.serviceName ?: "?",
                    version = txt("version") ?: LocalSend.VERSION,
                    deviceModel = txt("deviceModel"),
                    deviceType = DeviceType.parse(txt("deviceType")),
                    fingerprint = fp,
                    port = port,
                    protocol = txt("protocol") ?: "http",
                    download = txt("download") == "1",
                )
                onPeer(host, port, info)
            }
        }
}
