package org.openfilz.dms.config;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.service.signature.SignatureMailer;
import org.openfilz.dms.service.signature.SignatureSealer;
import org.openfilz.dms.service.signature.impl.CloudSignatureSealer;
import org.openfilz.dms.service.signature.impl.InProcessSignatureSealer;
import org.openfilz.dms.service.signature.impl.LoggingSignatureMailer;
import org.openfilz.dms.service.signature.impl.SmtpSignatureMailer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Runtime wiring for e-Sign. Both the sealer and the mailer are chosen from properties read at
 * runtime inside the factory methods (never a bean condition), so one native image serves every
 * deployment — see {@code StorageConfig} for the pattern. The sealer implementations are plain
 * classes (not components) so the context holds exactly one core {@link SignatureSealer}.
 *
 * <p>Enterprise overrides either bean with its own {@code @Primary} definition.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(SignatureProperties.class)
public class SignatureConfig {

    public static final String CORE_SEALER = "coreSignatureSealer";

    /**
     * The core sealer. Named so the enterprise archiving sealer can inject it explicitly as its
     * fallback while still being the {@code @Primary} {@link SignatureSealer}.
     */
    @Bean(CORE_SEALER)
    public SignatureSealer coreSignatureSealer(SignatureProperties props, WebClient.Builder webClientBuilder) {
        String provider = props.getSeal().getProvider();
        if (CloudSignatureSealer.ID.equalsIgnoreCase(provider)) {
            log.info("[e-sign] seal provider: openfilz-cloud ({})", props.getSeal().getCloud().getUrl());
            return new CloudSignatureSealer(props, webClientBuilder);
        }
        log.info("[e-sign] seal provider: {} (in-process PAdES-B-B)", provider);
        return new InProcessSignatureSealer(props).init();
    }

    @Bean
    public SignatureMailer signatureMailer(@Value("${spring.mail.host:}") String mailHost,
                                           ObjectProvider<JavaMailSender> mailSender,
                                           SignatureProperties props) {
        JavaMailSender sender = mailHost == null || mailHost.isBlank() ? null : mailSender.getIfAvailable();
        if (sender == null) {
            log.warn("[e-sign] spring.mail.host is not set — signing links will only be LOGGED, not emailed");
            return new LoggingSignatureMailer();
        }
        return new SmtpSignatureMailer(sender, props);
    }
}
