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
import reactor.core.publisher.Sinks;
import reactor.util.context.Context;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
    void aStatementTimeout_answersAnEmptyList_andIsCachedLikeAnyResult() {
        when(dao.listMetadataKeys(anyInt(), anyList()))
                .thenReturn(Flux.error(new R2dbcTimeoutException("canceling statement due to statement timeout", "57014")));

        assertTrue(service.getSuggestions().block().isEmpty());
        assertTrue(service.getSuggestions().block().isEmpty());

        // a library that cannot be listed in time is scanned once per TTL, not once per request
        verify(dao, times(1)).listMetadataKeys(anyInt(), anyList());
    }

    @Test
    void concurrentCallersOfAScope_shareOneLoad() {
        Sinks.Many<MetadataKeySuggestion> pending = Sinks.many().replay().all();
        when(dao.listMetadataKeys(anyInt(), anyList())).thenReturn(pending.asFlux());
        AtomicReference<List<MetadataKeySuggestion>> first = new AtomicReference<>();
        AtomicReference<List<MetadataKeySuggestion>> second = new AtomicReference<>();

        service.getSuggestions().subscribe(first::set);
        service.getSuggestions().subscribe(second::set);
        pending.tryEmitNext(new MetadataKeySuggestion("project", 3));
        pending.tryEmitComplete();

        verify(dao, times(1)).listMetadataKeys(anyInt(), anyList());
        assertEquals(List.of(new MetadataKeySuggestion("project", 3)), first.get());
        assertEquals(List.of(new MetadataKeySuggestion("project", 3)), second.get());
    }

    @Test
    void theLoadRunsUnderTheCallersReactorContext() {
        // the enterprise access predicate reads the caller's security context from the Reactor context
        when(dao.listMetadataKeys(anyInt(), anyList())).thenReturn(
                Mono.deferContextual(ctx -> Mono.just(new MetadataKeySuggestion(ctx.get("user"), 1))).flux());

        List<MetadataKeySuggestion> keys = service.getSuggestions().contextWrite(Context.of("user", "alice")).block();

        assertEquals(List.of(new MetadataKeySuggestion("alice", 1)), keys);
    }

    @Test
    void anotherError_isNotHidden_andIsNotCached() {
        when(dao.listMetadataKeys(anyInt(), anyList()))
                .thenReturn(Flux.error(new IllegalStateException("boom")))
                .thenReturn(Flux.just(new MetadataKeySuggestion("project", 3)));

        reactor.test.StepVerifier.create(service.getSuggestions()).expectError(IllegalStateException.class).verify();

        assertEquals(List.of(new MetadataKeySuggestion("project", 3)), service.getSuggestions().block());
    }
}
