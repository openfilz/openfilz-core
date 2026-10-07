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
import java.util.Comparator;
import java.util.Optional;

/**
 * Reads the identity of the certificate that sealed a signed PDF, so the UI can show who
 * vouches for the document (e.g. "OpenFilz SAS") instead of the internal sealer id.
 * Works for every {@link SignatureSealer}: the name comes from the PDF itself, not from config.
 *
 * <p><b>Trust:</b> the name is read from the certificate embedded in the signature but the
 * signature itself is <em>not</em> verified here. That is fine for its only caller, which passes
 * the PDF just produced by the configured sealer (in-process, cloud or archiving-api), never a
 * user upload. Do not reuse it to label untrusted documents.
 *
 * <p>Parsing the PDF is CPU work: callers must run it on a blocking-friendly scheduler
 * (see {@code SignatureServiceImpl.finalizeEnvelope}).
 */
@Slf4j
public final class SealCertificates {

    private static final String DOC_TIMESTAMP = "ETSI.RFC3161";
    /** Width of {@code signature_envelope.seal_signer}; a longer subject is cut, never rejected. */
    static final int MAX_LENGTH = 255;

    private SealCertificates() {
    }

    /**
     * Common name (falling back to organization, then the full subject DN) of the signer
     * certificate of the seal in {@code signedPdf}; null when it cannot be read.
     * Never throws: a missing name must not fail envelope finalization.
     *
     * <p>The seal is the signature applied last, i.e. the one whose byte range ends last, not
     * the last AcroForm field, which only tells the order fields were declared in. A PAdES-B-LTA
     * document timestamp appended after the seal is signed by the TSA and skipped.
     */
    public static String signerName(byte[] signedPdf) {
        if (signedPdf == null || signedPdf.length == 0) {
            return null;
        }
        try (PDDocument doc = Loader.loadPDF(signedPdf)) {
            Optional<PDSignature> seal = doc.getSignatureDictionaries().stream()
                    .filter(s -> !DOC_TIMESTAMP.equals(s.getSubFilter()))
                    .max(Comparator.comparingLong(SealCertificates::byteRangeEnd));
            if (seal.isEmpty()) {
                return null;
            }
            // /Contents is zero-padded and BC rejects trailing bytes: read exactly one ASN.1 object.
            CMSSignedData cms;
            try (ASN1InputStream in = new ASN1InputStream(seal.get().getContents(signedPdf))) {
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

    /** End offset of the signed bytes: a signature covers everything before and after its own /Contents. */
    private static long byteRangeEnd(PDSignature sig) {
        int[] range = sig.getByteRange();
        if (range == null || range.length < 4) {
            return -1;
        }
        return (long) range[2] + range[3];
    }

    /** CN, else O, else the full DN, cleaned so it is safe to store and display. */
    static String displayName(X500Name subject) {
        String cn = first(subject, BCStyle.CN);
        if (cn != null) {
            return cn;
        }
        String o = first(subject, BCStyle.O);
        return o != null ? o : clean(subject.toString());
    }

    private static String first(X500Name name, ASN1ObjectIdentifier attr) {
        RDN[] rdns = name.getRDNs(attr);
        if (rdns.length == 0) {
            return null;
        }
        ASN1Encodable value = rdns[0].getFirst().getValue();
        return clean(IETFUtils.valueToString(value));
    }

    /**
     * Strips control characters (a certificate subject is operator-chosen in the BYOC case) and
     * cuts to the column width; null when nothing printable is left.
     */
    static String clean(String s) {
        if (s == null) {
            return null;
        }
        String printable = s.replaceAll("\\p{Cntrl}", "").trim();
        if (printable.isEmpty()) {
            return null;
        }
        if (printable.length() <= MAX_LENGTH) {
            return printable;
        }
        // Keep room for the ellipsis and never split a surrogate pair.
        int end = MAX_LENGTH - 1;
        if (Character.isHighSurrogate(printable.charAt(end - 1))) {
            end--;
        }
        return printable.substring(0, end) + "…";
    }
}
