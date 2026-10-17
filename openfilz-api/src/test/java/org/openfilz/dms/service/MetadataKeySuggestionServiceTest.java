package org.openfilz.dms.service;

import io.r2dbc.spi.R2dbcTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.MetadataKeySuggestionProperties;
import org.openfilz.dms.dto.response.MetadataKeySuggestion;
import org.openfilz.dms.repository.DocumentDAO;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MetadataKeySuggestionServiceTest {

    private DocumentDAO dao;
    private MetadataKeySuggestionService service;
    private String scope;

    @BeforeEach
    void setUp() {
        dao = mock(DocumentDAO.class);
        DatabaseClient databaseClient = mock(DatabaseClient.class, RETURNS_DEEP_STUBS);
        when(databaseClient.sql(anyString()).then()).thenReturn(Mono.empty());
        TransactionalOperator tx = mock(TransactionalOperator.class);
        when(tx.transactional(any(Flux.class))).thenAnswer(invocation -> invocation.getArgument(0));
        scope = "alice";
        when(dao.metadataKeysCacheScope()).thenAnswer(invocation -> Mono.just(scope));
        service = new MetadataKeySuggestionService(dao, databaseClient, tx, new MetadataKeySuggestionProperties());
    }

    @Test
    void theListIsCachedPerScope() {
        when(dao.listMetadataKeys(anyInt(), anyList()))
                .thenReturn(Flux.just(new MetadataKeySuggestion("project", 3)));

        assertEquals(List.of(new MetadataKeySuggestion("project", 3)), service.getSuggestions().block());
        service.getSuggestions().block();
        verify(dao, times(1)).listMetadataKeys(anyInt(), anyList());

        scope = "bob";
        service.getSuggestions().block();
        verify(dao, times(2)).listMetadataKeys(anyInt(), anyList());
    }

    @Test
    void theDefaultsCapAndExcludeThePlatformKeys() {
        when(dao.listMetadataKeys(anyInt(), anyList())).thenReturn(Flux.empty());

        service.getSuggestions().block();

        verify(dao).listMetadataKeys(200, List.of("sha256"));
    }

    @Test
    void aStatementTimeout_answersAnEmptyList() {
        when(dao.listMetadataKeys(anyInt(), anyList()))
                .thenReturn(Flux.error(new R2dbcTimeoutException("canceling statement due to statement timeout", "57014")));

        assertTrue(service.getSuggestions().block().isEmpty());
    }

    @Test
    void anotherError_isNotHidden() {
        when(dao.listMetadataKeys(anyInt(), anyList())).thenReturn(Flux.error(new IllegalStateException("boom")));

        reactor.test.StepVerifier.create(service.getSuggestions()).expectError(IllegalStateException.class).verify();
    }
}
