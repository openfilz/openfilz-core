package org.openfilz.dms.exception;

import graphql.GraphQLError;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.ResultPath;
import graphql.language.Field;
import graphql.language.SourceLocation;
import graphql.schema.DataFetchingEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomExceptionResolverTest {

    private CustomExceptionResolver resolver;

    @Mock
    private DataFetchingEnvironment env;

    @Mock
    private ExecutionStepInfo executionStepInfo;

    @BeforeEach
    void setUp() {
        resolver = new CustomExceptionResolver();

        when(env.getExecutionStepInfo()).thenReturn(executionStepInfo);
        when(executionStepInfo.getPath()).thenReturn(ResultPath.rootPath());

        Field field = Field.newField("testField")
                .sourceLocation(new SourceLocation(1, 1))
                .build();
        when(env.getField()).thenReturn(field);
    }

    @Test
    void resolveIllegalArgumentException_returnsBadRequest() {
        StepVerifier.create(resolver.resolveException(
                        new IllegalArgumentException("invalid argument"), env))
                .expectNextMatches(errors -> {
                    assertEquals(1, errors.size());
                    GraphQLError error = errors.get(0);
                    assertEquals(ErrorType.BAD_REQUEST, error.getErrorType());
                    assertEquals("invalid argument", error.getMessage());
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void resolveWebExchangeBindException_returnsBadRequest() {
        WebExchangeBindException ex = mock(WebExchangeBindException.class);
        when(ex.getMessage()).thenReturn("validation error");

        StepVerifier.create(resolver.resolveException(ex, env))
                .expectNextMatches(errors -> {
                    assertEquals(1, errors.size());
                    assertEquals(ErrorType.BAD_REQUEST, errors.get(0).getErrorType());
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void resolveAuditException_returnsBadRequest() {
        StepVerifier.create(resolver.resolveException(
                        new AuditException("audit error", new RuntimeException()), env))
                .expectNextMatches(errors -> {
                    assertEquals(1, errors.size());
                    assertEquals(ErrorType.BAD_REQUEST, errors.get(0).getErrorType());
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void resolveVirusFoundException_returnsBadRequest() {
        StepVerifier.create(resolver.resolveException(
                        new VirusFoundException(List.of("EICAR-Test")), env))
                .expectNextMatches(errors -> {
                    assertEquals(1, errors.size());
                    assertEquals(ErrorType.BAD_REQUEST, errors.get(0).getErrorType());
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void resolveDocumentNotFoundException_returnsNotFound() {
        StepVerifier.create(resolver.resolveException(
                        new DocumentNotFoundException(UUID.randomUUID()), env))
                .expectNextMatches(errors -> {
                    assertEquals(1, errors.size());
                    assertEquals(ErrorType.NOT_FOUND, errors.get(0).getErrorType());
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void resolveOperationForbiddenException_returnsForbidden() {
        StepVerifier.create(resolver.resolveException(
                        new OperationForbiddenException("not allowed"), env))
                .expectNextMatches(errors -> {
                    assertEquals(1, errors.size());
                    assertEquals(ErrorType.FORBIDDEN, errors.get(0).getErrorType());
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void resolveAccessDeniedException_returnsForbidden() {
        StepVerifier.create(resolver.resolveException(
                        new AccessDeniedException("access denied"), env))
                .expectNextMatches(errors -> {
                    assertEquals(1, errors.size());
                    assertEquals(ErrorType.FORBIDDEN, errors.get(0).getErrorType());
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void resolveUnknownException_returnsInternalError() {
        StepVerifier.create(resolver.resolveException(
                        new RuntimeException("unexpected error"), env))
                .expectNextMatches(errors -> {
                    assertEquals(1, errors.size());
                    assertEquals(ErrorType.INTERNAL_ERROR, errors.get(0).getErrorType());
                    return true;
                })
                .verifyComplete();
    }
}
