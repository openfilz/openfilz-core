package org.openfilz.dms.config;

import org.openfilz.dms.service.branding.BrandingProvider;
import org.openfilz.dms.service.branding.PropertiesBrandingProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The default {@link BrandingProvider}: the deployment properties. An extension layer replaces it
 * with its own {@code @Primary} bean; every mailer asks the provider, never the properties.
 */
@Configuration
@EnableConfigurationProperties({BrandingProperties.class, SignatureProperties.class, WorkflowProperties.class})
public class BrandingConfig {

    @Bean
    public BrandingProvider propertiesBrandingProvider(BrandingProperties branding, SignatureProperties signature,
                                                       WorkflowProperties workflows) {
        return new PropertiesBrandingProvider(branding, signature, workflows);
    }
}
