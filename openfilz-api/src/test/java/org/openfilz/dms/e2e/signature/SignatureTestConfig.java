package org.openfilz.dms.e2e.signature;

import org.openfilz.dms.service.signature.SignatureMailer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class SignatureTestConfig {

    @Bean
    @Primary
    public SignatureMailer capturingSignatureMailer() {
        return new CapturingSignatureMailer();
    }
}
