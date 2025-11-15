package org.openfilz.dms.service;

import org.openfilz.dms.dto.request.SortInput;
import org.openfilz.dms.enums.OpenSearchDocumentKey;
import org.openfilz.dms.enums.SortOrder;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.springframework.util.StringUtils;

public interface OpenSearchService {

    String SUGGEST_ID = OpenSearchDocumentKey.id.toString();
    String SUGGEST_EXT = OpenSearchDocumentKey.extension.toString();
    String[] SUGGEST_OTHERS = { "name_suggest._2gram", "name_suggest._3gram" };
    int SUGGEST_RESULTS_MAX_SIZE = 10;
    String EM = "<em>";
    String EM1 = "</em>";
    String EM_EM = "<em>|</em>";
    String SPACE = " ";

    String KEYWORD = ".keyword";
    String CONTENT = OpenSearchDocumentKey.content.toString();
    String NAME = OpenSearchDocumentKey.name.toString();
    String NAME_SUGGEST = OpenSearchDocumentKey.name_suggest.toString();
    String[] SUGGEST_OTHERS_2 = { NAME_SUGGEST, "name_suggest._2gram", "name_suggest._3gram" };
    String EXTENSION = OpenSearchDocumentKey.extension.toString();
    String CREATED_BY = OpenSearchDocumentKey.createdBy.toString();
    String UPDATED_BY = OpenSearchDocumentKey.updatedBy.toString();

    default String getTrimQuery(String query) {
        return SPACE + query.trim() + SPACE;
    }

    default void addSorting(SortInput sort, SearchRequest.Builder requestBuilder) {
        // 5. Add Sorting
        if (sort != null && StringUtils.hasText(sort.field())) {
            // Map our enum to the OpenSearch enum
            var osSortOrder = sort.order() == SortOrder.ASC ?
                    org.opensearch.client.opensearch._types.SortOrder.Asc :
                    org.opensearch.client.opensearch._types.SortOrder.Desc;

            // Again, use .keyword for sorting on text fields to sort alphabetically, not by relevance.
            String sortField = sort.field();
            if (NAME.equals(sortField) || EXTENSION.equals(sortField)
                    || CREATED_BY.equals(sortField) || UPDATED_BY.equals(sortField)) {
                sortField += KEYWORD;
            }

            String finalSortField = sortField;
            requestBuilder.sort(s -> s.field(f -> f.field(finalSortField).order(osSortOrder)));
        }
    }

}
