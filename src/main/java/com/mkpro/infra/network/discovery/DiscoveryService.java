package com.mkpro.infra.network.discovery;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import java.io.IOException;
import java.net.InetAddress;

/**
 * Service for local P2P discovery using mDNS (JmDNS).
 * Registers the local instance and listens for other peers on the network.
 */
public class DiscoveryService implements ServiceListener {

    private static final String SERVICE_TYPE = "_mkpro._tcp.local.";
    private JmDNS jmdns;
    private final NetworkPeerRegistry registry = NetworkPeerRegistry.getInstance();

    /**
     * Starts the discovery service, registers the local instance, and begins listening for peers.
     * 
     * @param port The port this instance is listening on for P2P communication.
     * @param instanceId Unique identifier for this instance.
     * @throws IOException If JmDNS fails to initialize.
     */
    public void start(int port, String instanceId) throws IOException {
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
        // When a service is added, we request resolution to get host/port details
        System.out.println("Service added: " + event.getName());
        jmdns.requestServiceInfo(event.getType(), event.getName());
    }

    @Override
    public void serviceRemoved(ServiceEvent event) {
        System.out.println("Service removed: " + event.getName());
        registry.removePeer(event.getName());
    }

    @Override
    public void serviceResolved(ServiceEvent event) {
        System.out.println("Service resolved: " + event.getInfo());
        ServiceInfo info = event.getInfo();
        String[] addresses = info.getHostAddresses();
        
        if (addresses.length > 0) {
            NetworkPeerRegistry.PeerInfo peer = new NetworkPeerRegistry.PeerInfo(
                info.getName(), 
                addresses[0], 
                info.getPort()
            );
            registry.addPeer(peer);
        }
    }
}
