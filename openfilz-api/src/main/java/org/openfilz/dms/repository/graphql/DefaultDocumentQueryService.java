package org.openfilz.dms.repository.graphql;

import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.response.FullDocumentInfo;
import org.openfilz.dms.repository.DocumentQueryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
public class DefaultDocumentQueryService implements DocumentQueryService {

    private final DocumentDataFetcher documentDataFetcher;
    private final ListFolderDataFetcher listFolderDataFetcher;
    private final ListFolderCountDataFetcher listFolderCountDataFetcher;


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
