package org.openfilz.dms.repository;

import graphql.schema.DataFetchingEnvironment;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.response.FullDocumentInfo;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ListAllFolderDataFetcher extends ListAllFields {

    Flux<FullDocumentInfo> get(ListFolderRequest request, DataFetchingEnvironment environment);

    Mono<Long> position(ListFolderRequest request, UUID documentId, DataFetchingEnvironment environment);

}
