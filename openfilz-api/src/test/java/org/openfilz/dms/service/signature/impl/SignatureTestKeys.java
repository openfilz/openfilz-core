package org.openfilz.dms.service.signature.impl;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Date;

/** Test helper: RSA-2048 key pair + self-signed X.509 certificate (BouncyCastle). */
final class SignatureTestKeys {

    private SignatureTestKeys() {}

    record Material(KeyPair keyPair, X509Certificate certificate) {}

    static Material generate(String cn) throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        long now = System.currentTimeMillis();
        X500Name name = new X500Name("CN=" + cn + ", O=OpenFilz Tests");
        var builder = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now),
                new Date(now - 60_000L), new Date(now + 365L * 24 * 3600 * 1000), name, kp.getPublic());
        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        return new Material(kp, cert);
    }

    static byte[] fixturePdf() throws Exception {
        try (var in = SignatureTestKeys.class.getResourceAsStream("/pdf-example.pdf")) {
            if (in == null) throw new IllegalStateException("pdf-example.pdf fixture missing");
            return in.readAllBytes();
        }
    }
}
