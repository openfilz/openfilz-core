package org.openfilz.dms.service.onlyoffice;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Tells the people editing a document that their work was saved as a copy, because the document
 * changed while they were editing. Core: no-op (no notification centre).
 */
public interface EditorConflictNotifier {

    /**
     * @param documentId the document whose current content was left alone
     * @param copyId     the new document holding the editor's work
     * @param copyName   its name
     * @param users      the editor user ids the document server named in the callback
     */
    default Mono<Void> savedAsCopy(UUID documentId, UUID copyId, String copyName, List<String> users) {
        return Mono.empty();
    }
}
