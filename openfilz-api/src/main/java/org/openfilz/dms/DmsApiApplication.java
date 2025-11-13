// com/example/dms/DmsApiApplication.java
package org.openfilz.dms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DmsApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(DmsApiApplication.class, args);
    }
}