package org.openfilz.dms;

import org.openfilz.dms.config.AiMigrationRuntimeHints;
import org.openfilz.dms.config.AnthropicSdkRuntimeHints;
import org.openfilz.dms.config.TransformersRuntimeHints;
import org.openfilz.dms.config.CaffeineRuntimeHints;
import org.openfilz.dms.config.McpRuntimeHints;
import org.openfilz.dms.config.PoiOoxmlRuntimeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ImportRuntimeHints({PoiOoxmlRuntimeHints.class, AnthropicSdkRuntimeHints.class, AiMigrationRuntimeHints.class,
        CaffeineRuntimeHints.class, McpRuntimeHints.class, TransformersRuntimeHints.class})
public class DmsApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(DmsApiApplication.class, args);
    }
}