package org.openfilz.dms.service.signature;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;

import java.util.Collection;
import java.util.List;

/**
 * Reads the identity of the certificate that sealed a signed PDF, so the UI can show who
 * vouches for the document (e.g. "OpenFilz SAS") instead of the internal sealer id.
 * Works for every {@link SignatureSealer}: the name comes from the PDF itself, not from config.
 */
@Slf4j
public final class SealCertificates {

    private static final String DOC_TIMESTAMP = "ETSI.RFC3161";

    private SealCertificates() {
    }

    /**
     * Common name (falling back to organization, then the full subject DN) of the signer
     * certificate of the last signature in {@code signedPdf}; null when it cannot be read.
     * Never throws — a missing name must not fail envelope finalization.
     */
    public static String signerName(byte[] signedPdf) {
        if (signedPdf == null || signedPdf.length == 0) {
            return null;
        }
        try (PDDocument doc = Loader.loadPDF(signedPdf)) {
            // The seal is the last real signature: a PAdES-B-LTA document timestamp after it is
            // signed by the TSA, not by the seal certificate.
            List<PDSignature> sigs = doc.getSignatureDictionaries().stream()
                    .filter(s -> !DOC_TIMESTAMP.equals(s.getSubFilter()))
                    .toList();
            if (sigs.isEmpty()) {
                return null;
            }
            PDSignature sig = sigs.getLast();
            // /Contents is zero-padded and BC rejects trailing bytes: read exactly one ASN.1 object.
            CMSSignedData cms;
            try (ASN1InputStream in = new ASN1InputStream(sig.getContents(signedPdf))) {
                cms = new CMSSignedData(ContentInfo.getInstance(in.readObject()));
            }
            SignerInformation signer = cms.getSignerInfos().getSigners().iterator().next();
            Collection<X509CertificateHolder> certs = cms.getCertificates().getMatches(signer.getSID());
            if (certs.isEmpty()) {
                return null;
            }
            return displayName(certs.iterator().next().getSubject());
        } catch (Exception | LinkageError e) {
            log.debug("[e-sign] could not read the seal certificate: {}", e.toString());
            return null;
        }
    }

    static String displayName(X500Name subject) {
        String cn = first(subject, BCStyle.CN);
        if (cn != null) {
            return cn;
        }
        String o = first(subject, BCStyle.O);
        return o != null ? o : subject.toString();
    }

    private static String first(X500Name name, ASN1ObjectIdentifier attr) {
        RDN[] rdns = name.getRDNs(attr);
        if (rdns.length == 0) {
            return null;
        }
        ASN1Encodable value = rdns[0].getFirst().getValue();
        String s = IETFUtils.valueToString(value);
        return s == null || s.isBlank() ? null : s.trim();
    }
}
