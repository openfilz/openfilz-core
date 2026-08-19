package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.service.IndexNameProvider;
import org.openfilz.dms.service.OpenSearchQueryService;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Guard-clause coverage for the OpenSearch search & suggestion services — the early-return
 * branches that the full happy-path integration tests skip past.
 */
@ExtendWith(MockitoExtension.class)
class OpenSearchSearchSuggestionGuardTest {

    @Mock
    private IndexNameProvider indexNameProvider;
    @Mock
    private OpenSearchQueryService openSearchQueryService;
    @Mock
    private OpenSearchAsyncClient client;

    @Test
    void search_pageLessThanOne_throwsIllegalArgument() {
        OpenSearchDocumentSearchService service =
                new OpenSearchDocumentSearchService(indexNameProvider, openSearchQueryService, client);

        assertThrows(IllegalArgumentException.class,
                () -> service.search("q", null, null, 0, 10, null));
        verifyNoInteractions(client);
    }

    @Test
    void getSuggestions_nullQuery_returnsEmpty() {
        OpenSearchDocumentSuggestionService service =
                new OpenSearchDocumentSuggestionService(indexNameProvider, openSearchQueryService, client);

        StepVerifier.create(service.getSuggestions(null, null, null)).verifyComplete();
        verifyNoInteractions(client);
    }

    @Test
    void getSuggestions_blankQuery_returnsEmpty() {
        OpenSearchDocumentSuggestionService service =
                new OpenSearchDocumentSuggestionService(indexNameProvider, openSearchQueryService, client);

        StepVerifier.create(service.getSuggestions("   ", null, null)).verifyComplete();
        verifyNoInteractions(client);
    }
}
