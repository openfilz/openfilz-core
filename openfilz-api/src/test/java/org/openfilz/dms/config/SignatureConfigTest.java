package org.openfilz.dms.config;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.service.signature.SignatureMailer;
import org.openfilz.dms.service.signature.SignatureSealer;
import org.openfilz.dms.service.signature.impl.CloudSignatureSealer;
import org.openfilz.dms.service.signature.impl.InProcessSignatureSealer;
import org.openfilz.dms.service.signature.impl.LoggingSignatureMailer;
import org.openfilz.dms.service.signature.impl.SmtpSignatureMailer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SignatureConfigTest {

    private final SignatureConfig config = new SignatureConfig();

    private static SignatureProperties props(String provider) {
        SignatureProperties p = new SignatureProperties();
        p.getSeal().setProvider(provider);
        return p;
    }

    @Test
    void coreSignatureSealer_selfSignedDev_isInProcess() {
        SignatureSealer sealer = config.coreSignatureSealer(props("self-signed-dev"), WebClient.builder());
        assertThat(sealer).isInstanceOf(InProcessSignatureSealer.class);
        assertThat(sealer.id()).isEqualTo("self-signed-dev");
        assertThat(((InProcessSignatureSealer) sealer).certificate()).as("init() was called").isNotNull();
    }

    @Test
    void coreSignatureSealer_pkcs12WithoutKeystore_isInProcessFallingBackToEphemeral() {
        SignatureSealer sealer = config.coreSignatureSealer(props("pkcs12"), WebClient.builder());
        assertThat(sealer).isInstanceOf(InProcessSignatureSealer.class);
        // no keystore path configured → ephemeral material, id reflects what is actually used
        assertThat(sealer.id()).isEqualTo("self-signed-dev");
    }

    @Test
    void coreSignatureSealer_unknownProvider_defaultsToInProcess() {
        assertThat(config.coreSignatureSealer(props("whatever"), WebClient.builder())).isInstanceOf(InProcessSignatureSealer.class);
    }

    @Test
    void coreSignatureSealer_openfilzCloud_isCloud_caseInsensitive() {
        SignatureProperties p = props("OpenFilz-Cloud");
        p.getSeal().getCloud().setApiKey("k");
        SignatureSealer sealer = config.coreSignatureSealer(p, WebClient.builder());
        assertThat(sealer).isInstanceOf(CloudSignatureSealer.class);
        assertThat(sealer.id()).isEqualTo(CloudSignatureSealer.ID);
    }

    @Test
    void signatureConstants() {
        assertThat(SignatureConfig.CORE_SEALER).isEqualTo("coreSignatureSealer");
    }

    @Test
    @SuppressWarnings("unchecked")
    void signatureMailer_blankHost_isLogging_andNeverResolvesSender() {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        assertThat(config.signatureMailer("", provider, new SignatureProperties())).isInstanceOf(LoggingSignatureMailer.class);
        assertThat(config.signatureMailer(null, provider, new SignatureProperties())).isInstanceOf(LoggingSignatureMailer.class);
        assertThat(config.signatureMailer("   ", provider, new SignatureProperties())).isInstanceOf(LoggingSignatureMailer.class);
        verify(provider, never()).getIfAvailable();
    }

    @Test
    @SuppressWarnings("unchecked")
    void signatureMailer_hostButNoSenderBean_isLogging() {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        assertThat(config.signatureMailer("smtp.example.com", provider, new SignatureProperties())).isInstanceOf(LoggingSignatureMailer.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void signatureMailer_hostAndSender_isSmtp() {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(JavaMailSender.class));
        SignatureMailer mailer = config.signatureMailer("smtp.example.com", provider, new SignatureProperties());
        assertThat(mailer).isInstanceOf(SmtpSignatureMailer.class);
    }

    @Test
    void signatureProperties_defaults() {
        SignatureProperties p = new SignatureProperties();
        assertThat(p.isActive()).isFalse();
        assertThat(p.getDefaultExpiryDays()).isEqualTo(30);
        assertThat(p.getWebBaseUrl()).isEmpty();
        assertThat(p.getMaxImageBytes()).isEqualTo(512 * 1024);
        assertThat(p.getOtp().getLength()).isEqualTo(6);
        assertThat(p.getOtp().getValidMinutes()).isEqualTo(10);
        assertThat(p.getOtp().getMaxAttempts()).isEqualTo(5);
        assertThat(p.getSeal().getProvider()).isEqualTo("self-signed-dev");
        assertThat(p.getSeal().getKeystoreAlias()).isEqualTo("openfilz-seal");
        assertThat(p.getSeal().getName()).isEqualTo("OpenFilz e-Sign Seal");
        assertThat(p.getSeal().getCloud().getUrl()).isEqualTo("https://sign.openfilz.com");
        assertThat(p.getSeal().getCloud().getTimeout().toSeconds()).isEqualTo(15);
        assertThat(p.getMail().getFrom()).isEqualTo("no-reply@openfilz.com");
        assertThat(p.getMail().getProductName()).isEqualTo("OpenFilz");
    }
}
