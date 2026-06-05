package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.infra.network.discovery.NetworkPeerRegistry;

import java.util.List;

import static com.mkpro.MkPro.*;

public class NetworkCommand implements Command {
    @Override
    public String getName() {
        return "network";
    }

    @Override
    public String getDescription() {
        return "Shows network status and discovered peers.";
    }

    @Override
    public void execute(String[] args, MkProContext context) {
        System.out.println(ANSI_CYAN + "\n=== Network Status ===" + ANSI_RESET);
        System.out.println(ANSI_YELLOW + "Instance ID: " + ANSI_RESET + context.getInstanceName());
        
        if (context.getP2pMessageBus() != null) {
            System.out.println(ANSI_YELLOW + "P2P Port:    " + ANSI_RESET + context.getP2pMessageBus().getPort());
        } else {
            System.out.println(ANSI_YELLOW + "P2P Port:    " + ANSI_RED + "OFFLINE" + ANSI_RESET);
        }

        System.out.println(ANSI_CYAN + "\n--- Discovered Peers ---" + ANSI_RESET);
        List<NetworkPeerRegistry.PeerInfo> peers = NetworkPeerRegistry.getInstance().listPeers();
        if (peers.isEmpty()) {
            System.out.println("No peers discovered yet.");
        } else {
            for (NetworkPeerRegistry.PeerInfo peer : peers) {
                System.out.println(String.format("%s %s:%d", 
                    ANSI_GREEN + peer.getPeerId() + ANSI_RESET, 
                    peer.getIp(), 
                    peer.getPort()));
            }
        }
        System.out.println();
    }
}
