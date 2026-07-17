package com.mkpro.infra.network.security;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;

import java.math.BigInteger;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * SignerService handles the signing of PKCS10 CSRs using a CA private key.
 * NOTE: This service requires the Bouncy Castle library (bcpkix-jdk18on and bcprov-jdk18on).
 */
public class SignerService {

    private final PrivateKey caPrivateKey;
    private final X509Certificate caCertificate;
    private final SecureRandom secureRandom = new SecureRandom();

    public SignerService(PrivateKey caPrivateKey, X509Certificate caCertificate) {
        // Security Recommendation: Ensure CA private key is never logged. 
        // We do not store it in a way that toString() or similar would expose it.
        this.caPrivateKey = caPrivateKey;
        this.caCertificate = caCertificate;
    }

    /**
     * Signs a PKCS10 CSR and generates a new X509 certificate.
     * 
     * @param csr The PKCS10 CSR to sign.
     * @param validDuration The duration for which the certificate is valid.
     * @return The signed X509 certificate.
     * @throws Exception If signing fails.
     */
    public X509Certificate signCsr(PKCS10CertificationRequest csr, Duration validDuration) throws Exception {
        if (csr == null) {
            throw new IllegalArgumentException("CSR cannot be null");
        }

        // Security Recommendation: Backdate certificate "Not Before" time by 1 hour to account for clock drift.
        Instant now = Instant.now();
        Date notBefore = Date.from(now.minus(Duration.ofHours(1)));
        Date notAfter = Date.from(now.plus(validDuration));

        // Security Recommendation: Use cryptographically secure random numbers for serial numbers.
        byte[] serialBytes = new byte[16];
        secureRandom.nextBytes(serialBytes);
        BigInteger serialNumber = new BigInteger(1, serialBytes);

        X500Name issuer = new X500Name(caCertificate.getSubjectX500Principal().getName());
        
        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                issuer,
                serialNumber,
                notBefore,
                notAfter,
                csr.getSubject(),
                csr.getSubjectPublicKeyInfo()
        );

        // Security Recommendation: Ensure secure signature algorithm (SHA256withRSA is compliant).
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(caPrivateKey);
        X509CertificateHolder holder = certBuilder.build(signer);

        return new JcaX509CertificateConverter().getCertificate(holder);
    }
}