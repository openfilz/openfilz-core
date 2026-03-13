package org.openfilz.dms;

import org.openfilz.dms.config.PoiOoxmlRuntimeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ImportRuntimeHints(PoiOoxmlRuntimeHints.class)
public class DmsApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(DmsApiApplication.class, args);
    }
}