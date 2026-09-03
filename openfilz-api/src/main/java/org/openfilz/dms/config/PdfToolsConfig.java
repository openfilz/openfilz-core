package org.openfilz.dms.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link PdfToolsProperties}. The feature itself has no conditional bean: the controller,
 * service and AI tools are always present and consult {@code openfilz.pdf-tools.active} at runtime.
 */
@Configuration
@EnableConfigurationProperties(PdfToolsProperties.class)
public class PdfToolsConfig {
}
