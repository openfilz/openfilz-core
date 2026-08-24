package org.openfilz.dms.service.signature.impl;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.entity.SignatureRecipient;
import org.openfilz.dms.service.signature.SignatureMailer;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.web.util.HtmlUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Locale;

/**
 * JavaMail implementation of {@link SignatureMailer}. Every send is fire-and-forget on the
 * bounded-elastic scheduler; failures are logged and never propagate to the signing flow.
 * HTML is assembled in Java (no template engine) from the localised bundle — see
 * {@link SignatureMailTexts}.
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
                p(t(loc, "request.body", initiator(env), esc(env.getTitle()), esc(documentName)))
                        + messageBlock(loc, env)
                        + button(link, t(loc, "request.button"))
                        + small(t(loc, "request.expires", env.getExpiresAt() == null ? "-" : env.getExpiresAt().toLocalDate()))
                        + small(t(loc, "request.linkFallback") + "<br><a href=\"" + link + "\">" + link + "</a>"));
        send(r.getRecipientEmail(), subject, body, null, null);
    }

    @Override
    public void sendReminder(SignatureEnvelope env, SignatureRecipient r, String documentName, String link) {
        Locale loc = locale(env, r);
        String subject = t(loc, "reminder.subject", env.getTitle());
        String body = layout(loc, t(loc, "reminder.title"),
                p(t(loc, "reminder.body", initiator(env), esc(env.getTitle()), esc(documentName)))
                        + button(link, t(loc, "request.button"))
                        + small(t(loc, "request.expires", env.getExpiresAt() == null ? "-" : env.getExpiresAt().toLocalDate())));
        send(r.getRecipientEmail(), subject, body, null, null);
    }

    @Override
    public void sendOtp(SignatureEnvelope env, SignatureRecipient r, String code, int validMinutes) {
        Locale loc = locale(env, r);
        String subject = t(loc, "otp.subject", code);
        String body = layout(loc, t(loc, "otp.title"),
                p(t(loc, "otp.body", esc(env.getTitle())))
                        + "<p style=\"font-size:28px;letter-spacing:6px;font-weight:700;text-align:center\">" + esc(code) + "</p>"
                        + small(t(loc, "otp.valid", validMinutes)));
        send(r.getRecipientEmail(), subject, body, null, null);
    }

    @Override
    public void sendCompleted(SignatureEnvelope env, String toEmail, String toName, String localeCode,
                              byte[] signedPdf, String fileName) {
        Locale loc = SignatureMailTexts.localeOf(localeCode != null ? localeCode : env.getLocale());
        String subject = t(loc, "completed.subject", env.getTitle());
        String body = layout(loc, t(loc, "completed.title"),
                p(t(loc, "completed.body", esc(env.getTitle())))
                        + small(t(loc, "completed.attachment", esc(fileName))));
        send(toEmail, subject, body, signedPdf, fileName);
    }

    @Override
    public void sendDeclined(SignatureEnvelope env, SignatureRecipient decliner) {
        Locale loc = SignatureMailTexts.localeOf(env.getLocale());
        String subject = t(loc, "declined.subject", env.getTitle());
        String reason = decliner.getDeclineReason() == null || decliner.getDeclineReason().isBlank()
                ? t(loc, "declined.noReason") : esc(decliner.getDeclineReason());
        String body = layout(loc, t(loc, "declined.title"),
                p(t(loc, "declined.body", esc(decliner.getRecipientEmail()), esc(env.getTitle())))
                        + p("<i>" + reason + "</i>"));
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
        return s == null ? "" : HtmlUtils.htmlEscape(s);
    }

    private static String p(String inner) {
        return "<p style=\"margin:0 0 14px\">" + inner + "</p>";
    }

    private static String small(String inner) {
        return "<p style=\"margin:10px 0 0;font-size:12px;color:#667085\">" + inner + "</p>";
    }

    private static String button(String href, String label) {
        return "<p style=\"margin:22px 0\"><a href=\"" + href + "\" style=\"background:#1f6feb;color:#fff;"
                + "padding:12px 22px;border-radius:8px;text-decoration:none;font-weight:600;display:inline-block\">"
                + label + "</a></p>";
    }

    private String messageBlock(Locale loc, SignatureEnvelope env) {
        if (env.getMessage() == null || env.getMessage().isBlank()) return "";
        return "<blockquote style=\"margin:0 0 14px;padding:10px 14px;border-left:3px solid #d0d5dd;color:#344054\">"
                + esc(env.getMessage()).replace("\n", "<br>") + "</blockquote>";
    }

    private String layout(Locale loc, String title, String content) {
        String dir = SignatureMailTexts.isRtl(loc) ? "rtl" : "ltr";
        String logo = props.getMail().getLogoUrl() == null || props.getMail().getLogoUrl().isBlank() ? ""
                : "<img src=\"" + props.getMail().getLogoUrl() + "\" alt=\"\" style=\"max-height:40px;margin-bottom:16px\">";
        return "<!doctype html><html dir=\"" + dir + "\"><body style=\"margin:0;background:#f4f6f8;font-family:Segoe UI,Helvetica,Arial,sans-serif;color:#101828\">"
                + "<div style=\"max-width:560px;margin:24px auto;background:#fff;border-radius:12px;padding:28px;border:1px solid #e4e7ec\">"
                + logo
                + "<h2 style=\"margin:0 0 16px;font-size:20px\">" + title + "</h2>"
                + content
                + "<hr style=\"border:none;border-top:1px solid #e4e7ec;margin:24px 0 12px\">"
                + small(t(loc, "footer", esc(props.getMail().getProductName())))
                + "</div></body></html>";
    }
}
