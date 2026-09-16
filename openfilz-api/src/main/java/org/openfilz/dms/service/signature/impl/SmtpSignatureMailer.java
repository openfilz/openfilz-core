package org.openfilz.dms.service.signature.impl;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.entity.SignatureRecipient;
import org.openfilz.dms.service.signature.SignatureMailer;
import org.openfilz.dms.utils.EmailLayout;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Locale;

/**
 * JavaMail implementation of {@link SignatureMailer}. Every send is fire-and-forget on the
 * bounded-elastic scheduler; failures are logged and never propagate to the signing flow.
 * HTML is assembled in Java (no template engine) from the localised bundle — see
 * {@link SignatureMailTexts} — inside the shared branded shell {@link EmailLayout}.
 */
@Slf4j
public class SmtpSignatureMailer implements SignatureMailer {

    private final JavaMailSender sender;
    private final SignatureProperties props;

    public SmtpSignatureMailer(JavaMailSender sender, SignatureProperties props) {
        this.sender = sender;
        this.props = props;
    }

    @Override
    public void sendRequest(SignatureEnvelope env, SignatureRecipient r, String documentName, String link) {
        Locale loc = locale(env, r);
        String subject = t(loc, "request.subject", initiator(env), env.getTitle());
        String body = layout(loc, t(loc, "request.title"),
                EmailLayout.lead(t(loc, "request.body", initiator(env), esc(env.getTitle()), esc(documentName)))
                        + messageBlock(env)
                        + EmailLayout.button(link, t(loc, "request.button"))
                        + expires(loc, env)
                        + EmailLayout.linkFallback(t(loc, "request.linkFallback"), link));
        send(r.getRecipientEmail(), subject, body, null, null);
    }

    @Override
    public void sendReminder(SignatureEnvelope env, SignatureRecipient r, String documentName, String link) {
        Locale loc = locale(env, r);
        String subject = t(loc, "reminder.subject", env.getTitle());
        String body = layout(loc, t(loc, "reminder.title"),
                EmailLayout.lead(t(loc, "reminder.body", initiator(env), esc(env.getTitle()), esc(documentName)))
                        + EmailLayout.button(link, t(loc, "request.button"))
                        + expires(loc, env));
        send(r.getRecipientEmail(), subject, body, null, null);
    }

    @Override
    public void sendOtp(SignatureEnvelope env, SignatureRecipient r, String code, int validMinutes) {
        Locale loc = locale(env, r);
        String subject = t(loc, "otp.subject", code);
        String body = layout(loc, t(loc, "otp.title"),
                EmailLayout.paragraph(t(loc, "otp.body", esc(env.getTitle())))
                        + EmailLayout.code(code)
                        + EmailLayout.note(t(loc, "otp.valid", validMinutes)));
        send(r.getRecipientEmail(), subject, body, null, null);
    }

    @Override
    public void sendCompleted(SignatureEnvelope env, String toEmail, String toName, String localeCode,
                              byte[] signedPdf, String fileName) {
        Locale loc = SignatureMailTexts.localeOf(localeCode != null ? localeCode : env.getLocale());
        String subject = t(loc, "completed.subject", env.getTitle());
        String body = layout(loc, t(loc, "completed.title"),
                EmailLayout.lead(t(loc, "completed.body", esc(env.getTitle())))
                        + EmailLayout.callout("&#128206; " + t(loc, "completed.attachment", esc(fileName)), false));
        send(toEmail, subject, body, signedPdf, fileName);
    }

    @Override
    public void sendDeclined(SignatureEnvelope env, SignatureRecipient decliner) {
        Locale loc = SignatureMailTexts.localeOf(env.getLocale());
        String subject = t(loc, "declined.subject", env.getTitle());
        String reason = decliner.getDeclineReason() == null || decliner.getDeclineReason().isBlank()
                ? t(loc, "declined.noReason") : esc(decliner.getDeclineReason());
        String body = layout(loc, t(loc, "declined.title"),
                EmailLayout.lead(t(loc, "declined.body", esc(decliner.getRecipientEmail()), esc(env.getTitle())))
                        + EmailLayout.quote(reason));
        send(env.getInitiatorEmail(), subject, body, null, null);
    }

    // ─────────────────────────────────────────────────────────────────────

    private void send(String to, String subject, String html, byte[] attachment, String fileName) {
        Mono.fromRunnable(() -> {
            try {
                MimeMessage message = sender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, attachment != null, "UTF-8");
                helper.setFrom(props.getMail().getFrom(), props.getMail().getFromName());
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(html, true);
                if (attachment != null) {
                    helper.addAttachment(fileName, new ByteArrayResource(attachment), "application/pdf");
                }
                sender.send(message);
                log.info("[e-sign] mail '{}' sent to {}", subject, to);
            } catch (Exception e) {
                log.error("[e-sign] failed to send mail '{}' to {}: {}", subject, to, e.toString());
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    private Locale locale(SignatureEnvelope env, SignatureRecipient r) {
        return SignatureMailTexts.localeOf(r.getLocale() != null ? r.getLocale() : env.getLocale());
    }

    private String t(Locale loc, String key, Object... args) {
        return SignatureMailTexts.text(loc, key, args);
    }

    private static String initiator(SignatureEnvelope env) {
        return esc(env.getInitiatorEmail());
    }

    private static String esc(String s) {
        return EmailLayout.esc(s);
    }

    private String expires(Locale loc, SignatureEnvelope env) {
        return EmailLayout.note("&#9200; " + t(loc, "request.expires",
                env.getExpiresAt() == null ? "-" : env.getExpiresAt().toLocalDate()));
    }

    private static String messageBlock(SignatureEnvelope env) {
        if (env.getMessage() == null || env.getMessage().isBlank()) return "";
        return EmailLayout.quote(esc(env.getMessage()).replace("\n", "<br>"));
    }

    private String layout(Locale loc, String title, String content) {
        SignatureProperties.Mail mail = props.getMail();
        return EmailLayout.page(loc.getLanguage(), SignatureMailTexts.isRtl(loc), title, content,
                t(loc, "footer", esc(mail.getProductName())), mail.getProductName(), mail.getLogoUrl());
    }
}
