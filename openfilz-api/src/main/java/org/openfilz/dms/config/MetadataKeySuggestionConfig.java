package org.openfilz.dms.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link MetadataKeySuggestionProperties}. */
@Configuration
@EnableConfigurationProperties(MetadataKeySuggestionProperties.class)
public class MetadataKeySuggestionConfig {
}
