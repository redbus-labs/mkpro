package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.infra.network.discovery.NetworkPeerRegistry;

import java.util.List;
import java.util.Set;

import static com.mkpro.MkPro.*;

/**
 * Network management command.
 * Usage:
 *   /network              - Show network status and peers
 *   /network connect <ip:port> - Manually connect to a peer
 *   /network disconnect <uri>  - Disconnect from a peer
 *   /network peers        - List discovered peers
 */
public class NetworkCommand implements Command {
    @Override
    public String getName() {
        return "network";
    }

    @Override
    public String getDescription() {
        return "Manage mesh networking. Usage: /network [connect <ip:port> | disconnect <uri> | peers]";
    }

    @Override
    public void execute(String[] args, MkProContext context) {
        if (args.length == 0) {
            showStatus(context);
            return;
        }

        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "connect":
                if (args.length < 2) {
                    System.out.println(ANSI_YELLOW + "Usage: /network connect <ip:port>" + ANSI_RESET);
                    System.out.println("  Example: /network connect 192.168.1.50:9000");
                    return;
                }
                connectToPeer(args[1], context);
                break;
            case "disconnect":
                if (args.length < 2) {
                    System.out.println(ANSI_YELLOW + "Usage: /network disconnect <ws://ip:port>" + ANSI_RESET);
                    return;
                }
                disconnectPeer(args[1], context);
                break;
            case "peers":
                showPeers();
                break;
            default:
                showStatus(context);
        }
    }

    private void showStatus(MkProContext context) {
        System.out.println(ANSI_CYAN + "\n── Network Status ──" + ANSI_RESET);
        System.out.println("  Instance ID: " + context.getInstanceName());

        if (context.getP2pMessageBus() != null) {
            System.out.println("  P2P Port:    " + context.getP2pMessageBus().getPort());

            Set<String> connected = context.getP2pMessageBus().getConnectedPeerUris();
            System.out.println("  Connected:   " + connected.size() + " peer(s)");
            for (String uri : connected) {
                System.out.println(ANSI_GREEN + "    ● " + uri + ANSI_RESET);
            }
        } else {
            System.out.println(ANSI_RED + "  P2P:         OFFLINE" + ANSI_RESET);
        }

        System.out.println(ANSI_CYAN + "\n── Discovered Peers (mDNS) ──" + ANSI_RESET);
        showPeers();
        
        System.out.println("\n  Manual connect: /network connect <ip:port>");
        System.out.println();
    }

    private void showPeers() {
        List<NetworkPeerRegistry.PeerInfo> peers = NetworkPeerRegistry.getInstance().listPeers();
        if (peers.isEmpty()) {
            System.out.println("  No peers discovered yet.");
        } else {
            for (NetworkPeerRegistry.PeerInfo peer : peers) {
                System.out.println(String.format("  %s%s%s → %s:%d",
                    ANSI_GREEN, peer.getPeerId(), ANSI_RESET,
                    peer.getIp(), peer.getPort()));
            }
        }
    }

    private void connectToPeer(String target, MkProContext context) {
        if (context.getP2pMessageBus() == null) {
            System.out.println(ANSI_RED + "P2P Message Bus is not running." + ANSI_RESET);
            return;
        }

        // Normalize: add ws:// if not present
        String uri = target;
        if (!uri.startsWith("ws://") && !uri.startsWith("wss://")) {
            uri = "ws://" + uri;
        }

        System.out.println(ANSI_BLUE + "Connecting to " + uri + "..." + ANSI_RESET);
        context.getP2pMessageBus().connectToPeer(uri);
        System.out.println(ANSI_GREEN + "Connection initiated. Check /network for status." + ANSI_RESET);
    }

    private void disconnectPeer(String target, MkProContext context) {
        if (context.getP2pMessageBus() == null) {
            System.out.println(ANSI_RED + "P2P Message Bus is not running." + ANSI_RESET);
            return;
        }

        String uri = target;
        if (!uri.startsWith("ws://") && !uri.startsWith("wss://")) {
            uri = "ws://" + uri;
        }

        context.getP2pMessageBus().disconnectPeer(uri);
        System.out.println(ANSI_GREEN + "Disconnected from " + uri + ANSI_RESET);
    }
}
