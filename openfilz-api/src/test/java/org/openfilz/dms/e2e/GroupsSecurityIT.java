package org.openfilz.dms.e2e;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.openfilz.dms.enums.RoleTokenLookup.GROUPS;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
public class GroupsSecurityIT extends SecurityIT {

    public GroupsSecurityIT(WebTestClient webTestClient) {
        super(webTestClient);
    }

    @DynamicPropertySource
    static void registerResourceServerIssuerProperty(DynamicPropertyRegistry registry) {

        registry.add("spring.security.role-token-lookup", () -> GROUPS);
        registry.add("spring.security.root-group", () -> "GED");
    }
}
