package org.openfilz.dms.service;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.request.SortInput;
import org.openfilz.dms.enums.SortOrder;
import org.opensearch.client.opensearch.core.SearchRequest;

import static org.junit.jupiter.api.Assertions.*;

/** {@link OpenSearchService#addSorting}: only fields the index maps, sorted on the right (sub-)field. */
class OpenSearchServiceSortTest {

    private final OpenSearchService service = new OpenSearchService() { };

    private String sortOf(String field) {
        SearchRequest.Builder builder = new SearchRequest.Builder().index("i");
        service.addSorting(new SortInput(field, SortOrder.ASC), builder);
        return OpenSearchQueryServiceTest.json(builder.build());
    }

    @Test
    void name_sortsOnItsKeywordSubField() {
        String request = sortOf("name");
        assertTrue(request.contains("\"name.keyword\""), request);
    }

    @Test
    void keywordFields_areSortedAsTheyAre() {
        assertFalse(sortOf("createdBy").contains("createdBy.keyword"));
        assertTrue(sortOf("createdBy").contains("\"createdBy\""));
        assertTrue(sortOf("type").contains("\"extension\""));
        assertTrue(sortOf("size").contains("\"size\""));
    }

    @Test
    void unknownFieldOrNoSort_addsNoSort() {
        assertFalse(sortOf("notAField").contains("\"sort\""));
        SearchRequest.Builder builder = new SearchRequest.Builder().index("i");
        service.addSorting(null, builder);
        assertFalse(OpenSearchQueryServiceTest.json(builder.build()).contains("\"sort\""));
    }
}
