package org.openfilz.dms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "openfilz.audit.chain")
public class AuditChainProperties {

    private boolean enabled = true;

    private String algorithm = "SHA-256";

    private String verificationCron = "0 0 3 * * ?";

    private boolean verificationEnabled = true;
}
