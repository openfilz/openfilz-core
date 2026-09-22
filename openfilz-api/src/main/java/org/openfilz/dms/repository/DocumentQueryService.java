package org.openfilz.dms.repository;

import graphql.schema.DataFetchingEnvironment;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.response.FullDocumentInfo;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface DocumentQueryService {
    Mono<FullDocumentInfo> findById(UUID id, DataFetchingEnvironment environment);

    Flux<FullDocumentInfo> findAll(ListFolderRequest request, DataFetchingEnvironment environment);

    Mono<Long> count(ListFolderRequest request, DataFetchingEnvironment environment);

    /** 0-based index of a document in the listing {@code request} describes; empty when it is not in it. */
    Mono<Long> position(ListFolderRequest request, UUID documentId, DataFetchingEnvironment environment);
}
