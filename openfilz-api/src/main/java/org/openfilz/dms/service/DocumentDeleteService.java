package org.openfilz.dms.service;

import org.openfilz.dms.dto.request.DeleteRequest;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface DocumentDeleteService {
    Mono<Void> deleteFiles(DeleteRequest request);

    Mono<Void> deleteFolderRecursive(UUID folderId);
}
