package org.openfilz.dms.repository;

import graphql.schema.DataFetchingEnvironment;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.response.FullDocumentInfo;
import reactor.core.publisher.Flux;

public interface ListAllFolderDataFetcher extends ListAllFields {

    Flux<FullDocumentInfo> get(ListFolderRequest request, DataFetchingEnvironment environment);

}
