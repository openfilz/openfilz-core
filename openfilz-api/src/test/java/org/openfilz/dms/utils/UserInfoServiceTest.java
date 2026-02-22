package org.openfilz.dms.utils;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.security.OnlyOfficeAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserInfoServiceTest {

    // Create a concrete implementation for testing
    private final UserInfoService service = new UserInfoService() {};

    @Test
    void getUserAttribute_withNullAuthentication_returnsAnonymous() {
        assertEquals(UserInfoService.ANONYMOUS_USER, service.getUserAttribute(null, "email"));
    }

    @Test
    void getUserAttribute_withJwtPrincipal_returnsClaimValue() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("email", "user@example.com")
                .build();
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt);

        assertEquals("user@example.com", service.getUserAttribute(auth, "email"));
    }

    @Test
    void getUserAttribute_withUserDetailsPrincipal_returnsUsername() {
        UserDetails userDetails = User.withUsername("testuser")
                .password("pass")
                .authorities("ROLE_USER")
                .build();
        TestingAuthenticationToken auth = new TestingAuthenticationToken(userDetails, null);

        assertEquals("testuser", service.getUserAttribute(auth, "email"));
    }

    @Test
    void getUserAttribute_withUnknownPrincipal_returnsAuthName() {
        TestingAuthenticationToken auth = new TestingAuthenticationToken("simplePrincipal", null);
        auth.setAuthenticated(true);

        assertEquals("simplePrincipal", service.getUserAttribute(auth, "email"));
    }

    @Test
    void getConnectedUserEmail_withJwtInContext_returnsEmail() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("email", "jwt-user@example.com")
                .build();
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt);

        StepVerifier.create(
                        service.getConnectedUserEmail()
                                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                )
                .expectNext("jwt-user@example.com")
                .verifyComplete();
    }

    @Test
    void getConnectedUserEmail_withNoContext_returnsAnonymous() {
        StepVerifier.create(service.getConnectedUserEmail())
                .expectNext(UserInfoService.ANONYMOUS_USER)
                .verifyComplete();
    }

    @Test
    void getConnectedUserEmail_withOnlyOfficeToken_withEmail_returnsEmail() {
        OnlyOfficeAuthenticationToken auth = new OnlyOfficeAuthenticationToken(
                "userId", "userName", "onlyoffice@example.com", UUID.randomUUID(), "raw-token");

        StepVerifier.create(
                        service.getConnectedUserEmail()
                                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                )
                .expectNext("onlyoffice@example.com")
                .verifyComplete();
    }

    @Test
    void getConnectedUserEmail_withOnlyOfficeToken_withNullEmail_returnsUserId() {
        OnlyOfficeAuthenticationToken auth = new OnlyOfficeAuthenticationToken(
                "userId123", "userName", null, UUID.randomUUID(), "raw-token");

        StepVerifier.create(
                        service.getConnectedUserEmail()
                                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                )
                .expectNext("userId123")
                .verifyComplete();
    }

    @Test
    void getAuthenticationMono_withContext_returnsAuthentication() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("email", "test@example.com")
                .build();
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt);

        StepVerifier.create(
                        service.getAuthenticationMono()
                                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                )
                .expectNextMatches(a -> a instanceof JwtAuthenticationToken)
                .verifyComplete();
    }

    @Test
    void getAuthenticationMono_withNoContext_isEmpty() {
        StepVerifier.create(service.getAuthenticationMono())
                .verifyComplete();
    }
}
