// com/example/dms/config/OpenApiConfig.java
package org.openfilz.dms.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.List;

@Configuration
public class OpenApiConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String keycloakIssuerUri; // e.g., http://localhost:8080/realms/test-realm

    @Bean
    public OpenAPI customOpenAPI(@Value("${openapi.service.title}") String serviceTitle,
                                 @Value("${openapi.service.version}") String serviceVersion,
                                 @Value("${openapi.service.url}") String url) {
        final String securitySchemeName = "keycloak_auth"; // Can be any name

        // Construct the OpenID Connect URL from the issuer URI
        // Usually it's issuerUri + "/.well-known/openid-configuration"
        String openIdConnectUrl = keycloakIssuerUri + "/.well-known/openid-configuration";


        return new OpenAPI()
                .servers(List.of(new Server().url(url)))
                .info(new Info().title(serviceTitle)
                        .version(serviceVersion)
                        .description("API for Document Management System")
                        .license(new License().name("Apache 2.0").url("https://openfilz.org")))
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName, Collections.emptyList()))
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName, new SecurityScheme()
                                .name(securitySchemeName)
                                .type(SecurityScheme.Type.HTTP)
                                .in(SecurityScheme.In.HEADER)
                                .openIdConnectUrl(openIdConnectUrl) // Keycloak's OIDC discovery endpoint
                                .scheme("bearer")
                                .bearerFormat("JWT")
                        )
                );
    }


}