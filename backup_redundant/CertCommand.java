package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;

import static com.mkpro.MkPro.*;

/**
 * Command to show mTLS certificate details and rotation status.
 * Usage: /cert
 */
public class CertCommand implements Command {

    @Override
    public String getName() {
        return "cert";
    }

    @Override
    public String getDescription() {
        return "Show mTLS certificate details and rotation status.";
    }

    @Override
    public void execute(String[] args, MkProContext context) {
        System.out.println(ANSI_CYAN + "\n── mTLS Certificate Status ──" + ANSI_RESET);

        if (context.getP2pMessageBus() != null) {
            String thumbprint = context.getP2pMessageBus().getActiveCertThumbprint();
            System.out.println("  Active Thumbprint: " + ANSI_GREEN + thumbprint + ANSI_RESET);

            Object expiration = context.getCentralMemory().getMemory("security.cert.expiration");
            System.out.println("  Expiration Date:   " + (expiration != null ? ANSI_YELLOW + expiration + ANSI_RESET : "N/A"));

            Object phase = context.getCentralMemory().getMemory("mesh.rotation.phase");
            System.out.println("  Rotation Phase:    " + (phase != null ? ANSI_BRIGHT_GREEN + phase + ANSI_RESET : "Phase 0 (Initial)"));
        } else {
            System.out.println(ANSI_RED + "  mTLS Status:       INACTIVE (Network disabled)" + ANSI_RESET);
        }
        
        System.out.println();
    }
}