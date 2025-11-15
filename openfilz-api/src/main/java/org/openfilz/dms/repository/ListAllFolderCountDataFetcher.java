package org.openfilz.dms.repository;

import graphql.schema.DataFetchingEnvironment;
import org.openfilz.dms.dto.request.ListFolderRequest;
import reactor.core.publisher.Mono;

public interface ListAllFolderCountDataFetcher extends ListAllFields {

    Mono<Long> get(ListFolderRequest request, DataFetchingEnvironment environment);

}
