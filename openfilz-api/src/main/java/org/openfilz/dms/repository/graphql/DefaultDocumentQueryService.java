package org.openfilz.dms.repository.graphql;

import graphql.schema.DataFetchingEnvironment;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.response.FullDocumentInfo;
import org.openfilz.dms.repository.DocumentQueryService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
public class DefaultDocumentQueryService implements DocumentQueryService {

    private final DocumentDataFetcher documentDataFetcher;
    private final ListFolderDataFetcher listFolderDataFetcher;
    private final ListFolderCountDataFetcher listFolderCountDataFetcher;

    public DefaultDocumentQueryService(DocumentDataFetcher documentDataFetcher, @Qualifier("defaultListFolderDataFetcher") ListFolderDataFetcher listFolderDataFetcher, @Qualifier("defaultListFolderCountDataFetcher") ListFolderCountDataFetcher listFolderCountDataFetcher) {
        this.documentDataFetcher = documentDataFetcher;
        this.listFolderDataFetcher = listFolderDataFetcher;
        this.listFolderCountDataFetcher = listFolderCountDataFetcher;
    }

    @Override
    public Mono<FullDocumentInfo> findById(UUID id, DataFetchingEnvironment environment) {
        return documentDataFetcher.get(id, environment);
    }

    @Override
    public Flux<FullDocumentInfo> findAll(ListFolderRequest request, DataFetchingEnvironment environment) {
        return listFolderDataFetcher.get(request, environment);
    }

    @Override
    public Mono<Long> count(ListFolderRequest request, DataFetchingEnvironment environment) {
        return listFolderCountDataFetcher.get(request, environment);
    }
}
