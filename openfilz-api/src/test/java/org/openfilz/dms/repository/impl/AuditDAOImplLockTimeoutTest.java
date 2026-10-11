package org.openfilz.dms.repository.impl;

import io.r2dbc.spi.R2dbcNonTransientResourceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openfilz.dms.config.AuditChainProperties;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.service.AuditChainService;
import org.openfilz.dms.utils.JsonUtils;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The global audit-chain advisory lock is taken under a bounded wait
 * ({@code openfilz.audit.chain.lock-timeout}) that is set right before and reset right after
 * the acquisition, so a stuck holder produces a clear error while the caller's transaction
 * keeps its own lock settings.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditDAOImplLockTimeoutTest {

    @Mock DatabaseClient databaseClient;
    @Mock DatabaseClient.GenericExecuteSpec spec;
    @Mock RowsFetchSpec<String> rows;
    @Mock ObjectMapper objectMapper;
    @Mock JsonUtils jsonUtils;
    @Mock AuditChainService chainService;
    @Mock TransactionalOperator tx;

    private final AuditChainProperties props = new AuditChainProperties();
    private final List<String> statements = new ArrayList<>();
    private AuditDAOImpl dao;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        dao = new AuditDAOImpl(databaseClient, objectMapper, jsonUtils, props, chainService, tx);
        when(databaseClient.sql(anyString())).thenAnswer(inv -> {
            statements.add(inv.getArgument(0));
            return spec;
        });
        when(spec.then()).thenReturn(Mono.empty());
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.bindNull(anyString(), any())).thenReturn(spec);
        when(spec.map(any(Function.class))).thenReturn(rows);
        when(rows.one()).thenReturn(Mono.just("prev-hash"));
        when(chainService.auditTimestamp()).thenReturn(OffsetDateTime.now());
        when(chainService.computeHash(any(), any(), any(), any(), any(), any(), any())).thenReturn("new-hash");
        when(chainService.computeGenesisHash()).thenReturn("genesis");
        when(tx.transactional(any(Mono.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void lockTimeout_isSetBeforeTheAdvisoryLock_andResetRightAfter() {
        props.setLockTimeout(Duration.ofSeconds(5));

        dao.logAction(AuditAction.UPLOAD_DOCUMENT, DocumentType.FILE, UUID.randomUUID(), null).block();

        assertThat(statements).containsSubsequence(
                "SET LOCAL lock_timeout = '5000ms'",
                AuditDAOImpl.ADVISORY_LOCK_SQL,
                AuditDAOImpl.RESET_LOCK_TIMEOUT_SQL);
        int lock = statements.indexOf(AuditDAOImpl.ADVISORY_LOCK_SQL);
        int insert = indexOfPrefix("INSERT INTO audit_logs");
        assertThat(insert).as("the chained insert comes after the lock").isGreaterThan(lock);
        assertThat(statements.indexOf(AuditDAOImpl.RESET_LOCK_TIMEOUT_SQL)).isLessThan(insert);
    }

    @Test
    void zeroTimeout_keepsTheUnboundedWait() {
        props.setLockTimeout(Duration.ZERO);

        dao.logAction(AuditAction.UPLOAD_DOCUMENT, DocumentType.FILE, UUID.randomUUID(), null).block();

        assertThat(statements).contains(AuditDAOImpl.ADVISORY_LOCK_SQL);
        assertThat(statements).noneMatch(s -> s.contains("lock_timeout"));
    }

    @Test
    void chainDisabled_takesNoLockAtAll() {
        props.setEnabled(false);

        dao.logAction(AuditAction.UPLOAD_DOCUMENT, DocumentType.FILE, UUID.randomUUID(), null).block();

        assertThat(statements).noneMatch(s -> s.contains("pg_advisory") || s.contains("lock_timeout"));
        assertThat(indexOfPrefix("INSERT INTO audit_logs")).isNotNegative();
    }

    @Test
    void lockTimeoutSql_isAPlainNumber() {
        assertThat(AuditDAOImpl.lockTimeoutSql(250)).isEqualTo("SET LOCAL lock_timeout = '250ms'");
        props.setLockTimeout(null);
        assertThat(dao.lockTimeoutMillis()).isZero();
        props.setLockTimeout(Duration.ofMillis(-1));
        assertThat(dao.lockTimeoutMillis()).isZero();
    }

    @Test
    void aLockTimeoutError_isRecognised_andDoesNotFailTheCaller() {
        props.setLockTimeout(Duration.ofSeconds(1));
        when(spec.then()).thenReturn(Mono.error(
                new R2dbcNonTransientResourceException("canceling statement due to lock timeout", "55P03", 0)));

        // the DAO logs and swallows, like every other audit write failure
        dao.logAction(AuditAction.UPLOAD_DOCUMENT, DocumentType.FILE, UUID.randomUUID(), null).block();

        assertThat(AuditDAOImpl.isLockTimeout(new R2dbcNonTransientResourceException("x", "55P03", 0))).isTrue();
        assertThat(AuditDAOImpl.isLockTimeout(new RuntimeException("wrapped",
                new R2dbcNonTransientResourceException("canceling statement due to lock timeout", "55P03", 0)))).isTrue();
        assertThat(AuditDAOImpl.isLockTimeout(new IllegalStateException("something else"))).isFalse();
    }

    private int indexOfPrefix(String prefix) {
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).startsWith(prefix)) return i;
        }
        return -1;
    }
}
