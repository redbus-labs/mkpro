package com.mkpro.infra.network.discovery;

import com.mkpro.infra.network.messaging.P2PMessageBus;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import java.io.IOException;
import java.net.InetAddress;

/**
 * Service for local P2P discovery using mDNS (JmDNS).
 * Registers the local instance and listens for other peers on the network.
 * 
 * When a peer is discovered and resolved, automatically connects via P2PMessageBus.
 */
public class DiscoveryService implements ServiceListener {

    private static final String SERVICE_TYPE = "_mkpro._tcp.local.";
    private JmDNS jmdns;
    private final NetworkPeerRegistry registry = NetworkPeerRegistry.getInstance();
    private P2PMessageBus messageBus;
    private String localInstanceId;

    /**
     * Starts the discovery service, registers the local instance, and begins listening for peers.
     * 
     * @param port The port this instance is listening on for P2P communication.
     * @param instanceId Unique identifier for this instance.
     */
    public void start(int port, String instanceId) throws IOException {
        this.localInstanceId = instanceId;
        
        // Initialize JmDNS on the local default address
        jmdns = JmDNS.create(InetAddress.getLocalHost());

        // Register the local service
        ServiceInfo serviceInfo = ServiceInfo.create(SERVICE_TYPE, instanceId, port, "MkPro P2P Node");
        jmdns.registerService(serviceInfo);

        // Add listener for the specific service type
        jmdns.addServiceListener(SERVICE_TYPE, this);
        
        System.out.println("Discovery Service started for instance: " + instanceId + " on port: " + port);
    }

    /**
     * Sets the P2PMessageBus for auto-connecting discovered peers.
     */
    public void setMessageBus(P2PMessageBus messageBus) {
        this.messageBus = messageBus;
    }

    /**
     * Stops the discovery service and unregisters the local instance.
     */
    public void stop() {
        if (jmdns != null) {
            jmdns.unregisterAllServices();
            try {
                jmdns.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void serviceAdded(ServiceEvent event) {
        // When a service is added, request resolution to get host/port details
        System.out.println("P2P Discovery: Found peer: " + event.getName());
        jmdns.requestServiceInfo(event.getType(), event.getName());
    }

    @Override
    public void serviceRemoved(ServiceEvent event) {
        System.out.println("P2P Discovery: Peer left: " + event.getName());
        registry.removePeer(event.getName());
    }

    @Override
    public void serviceResolved(ServiceEvent event) {
        ServiceInfo info = event.getInfo();
        String[] addresses = info.getHostAddresses();
        
        if (addresses.length == 0) return;
        
        // Don't connect to ourselves
        if (info.getName().equals(localInstanceId)) return;

        String peerIp = addresses[0];
        int peerPort = info.getPort();

        NetworkPeerRegistry.PeerInfo peer = new NetworkPeerRegistry.PeerInfo(
            info.getName(), 
            peerIp, 
            peerPort
        );
        registry.addPeer(peer);
        
        System.out.println("P2P Discovery: Resolved peer: " + info.getName() + " at " + peerIp + ":" + peerPort);

        // Auto-connect via P2PMessageBus
        if (messageBus != null) {
            String peerUri = "ws://" + peerIp + ":" + peerPort;
            messageBus.connectToPeer(peerUri);
        }
    }
}
