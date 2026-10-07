package org.openfilz.dms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Data
@Configuration
@ConfigurationProperties(prefix = "openfilz.audit.chain")
public class AuditChainProperties {

    private boolean enabled = true;

    /**
     * How long a chained audit insert may wait for the global chain lock
     * ({@code pg_advisory_xact_lock}) before giving up with a clear error, instead of piling
     * up behind a transaction that holds the lock across slow work. Applied with
     * {@code SET LOCAL lock_timeout} around the lock acquisition only, then reset, so the
     * caller's transaction keeps its own lock settings. {@code 0} = wait forever (previous behaviour).
     */
    private Duration lockTimeout = Duration.ofSeconds(5);

    private String algorithm = "SHA-256";

    private String verificationCron = "0 0 3 * * ?";

    private boolean verificationEnabled = true;
}
