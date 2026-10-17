package org.openfilz.dms.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.MetadataKeySuggestionProperties;
import org.openfilz.dms.dto.response.MetadataKeySuggestion;
import org.openfilz.dms.repository.DocumentDAO;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The metadata keys suggested by the search filter: the most frequent keys of the documents the
 * caller can see ({@link DocumentDAO#listMetadataKeys}).
 * <p>
 * One aggregation per cache scope ({@link DocumentDAO#metadataKeysCacheScope()}: the whole
 * library, or the caller when documents are visible per user) per TTL window, under a statement
 * timeout: a library too large to list in time degrades to "no suggestions" rather than to a
 * slow database. Keys created meanwhile appear when the entry expires — fine for suggestions.
 */
@Slf4j
@Service
public class MetadataKeySuggestionService {

    /** SQLSTATE of a statement cancelled by {@code statement_timeout}. */
    private static final String QUERY_CANCELED = "57014";

    private final DocumentDAO documentDAO;
    private final DatabaseClient databaseClient;
    private final TransactionalOperator tx;
    private final MetadataKeySuggestionProperties properties;
    private final Cache<String, List<MetadataKeySuggestion>> cache;
    private final AtomicBoolean timeoutLogged = new AtomicBoolean();

    public MetadataKeySuggestionService(DocumentDAO documentDAO, DatabaseClient databaseClient, TransactionalOperator tx,
                                        MetadataKeySuggestionProperties properties) {
        this.documentDAO = documentDAO;
        this.databaseClient = databaseClient;
        this.tx = tx;
        this.properties = properties;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.getCacheTtl())
                .maximumSize(properties.getCacheMaxEntries())
                .build();
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /** The suggested keys, most frequent first; empty when the listing timed out. */
    public Mono<List<MetadataKeySuggestion>> getSuggestions() {
        return documentDAO.metadataKeysCacheScope()
                .flatMap(scope -> {
                    List<MetadataKeySuggestion> cached = cache.getIfPresent(scope);
                    if (cached != null) {
                        return Mono.just(cached);
                    }
                    return load().doOnNext(keys -> cache.put(scope, keys));
                });
    }

    private Mono<List<MetadataKeySuggestion>> load() {
        // SET does not take bind parameters: the value is a number of milliseconds, never user input
        long timeoutMillis = Math.max(1, properties.getStatementTimeout().toMillis());
        return tx.transactional(databaseClient.sql("SET LOCAL statement_timeout = " + timeoutMillis)
                        .then()
                        .thenMany(documentDAO.listMetadataKeys(Math.max(1, properties.getMaxKeys()), properties.getExcludedKeys())))
                .collectList()
                .onErrorResume(MetadataKeySuggestionService::isStatementTimeout, e -> {
                    if (timeoutLogged.compareAndSet(false, true)) {
                        log.warn("Listing the metadata keys exceeded {} — key suggestions are empty "
                                + "(raise openfilz.metadata.key-suggestions.statement-timeout to allow it)",
                                properties.getStatementTimeout());
                    }
                    return Mono.just(List.of());
                });
    }

    static boolean isStatementTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof io.r2dbc.spi.R2dbcException r2dbc && QUERY_CANCELED.equals(r2dbc.getSqlState())) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
