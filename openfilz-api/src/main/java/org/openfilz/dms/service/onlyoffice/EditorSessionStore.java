package org.openfilz.dms.service.onlyoffice;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * What each OnlyOffice editor session started from, so a save callback can tell whether the
 * document's content moved underneath it. Written through {@link DatabaseClient} with explicit SQL:
 * an opening must not reset a session already running under the same key.
 */
@Component
@RequiredArgsConstructor
public class EditorSessionStore {

    /** One editor session. {@code conflictCopy} is null until the session has diverged. */
    public record EditorSession(String key, UUID documentId, String baseMarker, UUID conflictCopy) {
    }

    private final DatabaseClient databaseClient;

    /**
     * Records a session being opened. A session running under this key stays as it is: everyone who
     * joins it edits the same content. A closed one starts again from the current content, since
     * nothing of the old session can come back.
     */
    public Mono<Void> opened(String key, UUID documentId, String baseMarker) {
        return databaseClient.sql("""
                        INSERT INTO onlyoffice_editor_session (document_key, document_id, base_marker)
                        VALUES (:key, :documentId, :baseMarker)
                        ON CONFLICT (document_key) DO UPDATE
                           SET base_marker = EXCLUDED.base_marker, conflict_copy = NULL,
                               closed_at = NULL, last_seen_at = now()
                         WHERE onlyoffice_editor_session.closed_at IS NOT NULL""")
                .bind("key", key)
                .bind("documentId", documentId)
                .bind("baseMarker", baseMarker)
                .then();
    }

    /** The session under this key, if it belongs to this document. */
    public Mono<EditorSession> find(String key, UUID documentId) {
        return databaseClient.sql("""
                        SELECT document_key, document_id, base_marker, conflict_copy
                          FROM onlyoffice_editor_session
                         WHERE document_key = :key AND document_id = :documentId""")
                .bind("key", key)
                .bind("documentId", documentId)
                .map(row -> new EditorSession(
                        row.get("document_key", String.class),
                        row.get("document_id", UUID.class),
                        row.get("base_marker", String.class),
                        row.get("conflict_copy", UUID.class)))
                .one();
    }

    /** The session saved: its next save is based on what it just wrote. */
    public Mono<Void> moveBase(String key, String baseMarker) {
        return databaseClient.sql("""
                        UPDATE onlyoffice_editor_session
                           SET base_marker = :baseMarker, last_seen_at = now()
                         WHERE document_key = :key""")
                .bind("key", key)
                .bind("baseMarker", baseMarker)
                .then();
    }

    /** The session diverged: its later saves go to this copy. */
    public Mono<Void> conflictCopy(String key, UUID copyId) {
        return databaseClient.sql("""
                        UPDATE onlyoffice_editor_session
                           SET conflict_copy = :copyId, last_seen_at = now()
                         WHERE document_key = :key""")
                .bind("key", key)
                .bind("copyId", copyId)
                .then();
    }

    /** Every editor left the session. */
    public Mono<Void> closed(String key) {
        return databaseClient.sql("""
                        UPDATE onlyoffice_editor_session
                           SET closed_at = now(), last_seen_at = now()
                         WHERE document_key = :key""")
                .bind("key", key)
                .then();
    }

    /** Removes the sessions closed before {@code closedBefore} or silent since {@code seenBefore}. */
    public Mono<Long> deleteStale(OffsetDateTime closedBefore, OffsetDateTime seenBefore) {
        return databaseClient.sql("""
                        DELETE FROM onlyoffice_editor_session
                         WHERE closed_at < :closedBefore OR last_seen_at < :seenBefore""")
                .bind("closedBefore", closedBefore)
                .bind("seenBefore", seenBefore)
                .fetch().rowsUpdated();
    }
}
