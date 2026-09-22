package org.openfilz.dms.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link UnzipProperties}. */
@Configuration
@EnableConfigurationProperties(UnzipProperties.class)
public class UnzipConfig {
}
