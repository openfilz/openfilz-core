package org.openfilz.dms.e2e.signature;

import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.entity.SignatureRecipient;
import org.openfilz.dms.service.signature.SignatureMailer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test double for the mail seam. The raw signing token only ever leaves the system inside the
 * invitation e-mail (the DB stores its SHA-256), so the ITs capture the links here instead of
 * standing up an SMTP round-trip. Everything else in the flow is exercised through the REST API.
 */
public class CapturingSignatureMailer implements SignatureMailer {

    public record Sent(String kind, UUID envelopeId, String to, String link, String code, String fileName, byte[] pdf) {}

    public final List<Sent> sent = new CopyOnWriteArrayList<>();
    /** recipient email → latest signing link per envelope. */
    public final Map<String, String> latestLink = new ConcurrentHashMap<>();
    /** recipient email → latest OTP code. */
    public final Map<String, String> latestOtp = new ConcurrentHashMap<>();

    @Override
    public void sendRequest(SignatureEnvelope env, SignatureRecipient r, String documentName, String link) {
        sent.add(new Sent("request", env.getId(), r.getRecipientEmail(), link, null, null, null));
        latestLink.put(key(env, r.getRecipientEmail()), link);
    }

    @Override
    public void sendReminder(SignatureEnvelope env, SignatureRecipient r, String documentName, String link) {
        sent.add(new Sent("reminder", env.getId(), r.getRecipientEmail(), link, null, null, null));
        latestLink.put(key(env, r.getRecipientEmail()), link);
    }

    @Override
    public void sendOtp(SignatureEnvelope env, SignatureRecipient r, String code, int validMinutes) {
        sent.add(new Sent("otp", env.getId(), r.getRecipientEmail(), null, code, null, null));
        latestOtp.put(key(env, r.getRecipientEmail()), code);
    }

    @Override
    public void sendCompleted(SignatureEnvelope env, String toEmail, String toName, String locale, byte[] signedPdf, String fileName) {
        sent.add(new Sent("completed", env.getId(), toEmail, null, null, fileName, signedPdf));
    }

    @Override
    public void sendDeclined(SignatureEnvelope env, SignatureRecipient decliner) {
        sent.add(new Sent("declined", env.getId(), env.getInitiatorEmail(), null, null, null, null));
    }

    public String tokenFor(UUID envelopeId, String email) {
        String link = latestLink.get(envelopeId + "|" + email.toLowerCase());
        if (link == null) throw new IllegalStateException("No signing link captured for " + email);
        return link.substring(link.indexOf("token=") + 6);
    }

    public String otpFor(UUID envelopeId, String email) {
        String code = latestOtp.get(envelopeId + "|" + email.toLowerCase());
        if (code == null) throw new IllegalStateException("No OTP captured for " + email);
        return code;
    }

    public List<Sent> ofKind(String kind) {
        return sent.stream().filter(s -> s.kind().equals(kind)).toList();
    }

    public void clear() {
        sent.clear();
        latestLink.clear();
        latestOtp.clear();
    }

    private static String key(SignatureEnvelope env, String email) {
        return env.getId() + "|" + email.toLowerCase();
    }
}
