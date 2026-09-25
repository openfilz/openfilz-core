package org.openfilz.dms.service;

import org.openfilz.dms.dto.request.SortInput;
import org.openfilz.dms.enums.OpenSearchDocumentKey;
import org.openfilz.dms.enums.SortOrder;
import org.opensearch.client.opensearch._types.mapping.FieldType;
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
    String ACTIVE = OpenSearchDocumentKey.active.toString();

    default String getTrimQuery(String query) {
        return query.trim();
    }

    /**
     * The sortable index fields, by the name the API receives: {@code name} sorts on its keyword
     * sub-field, the others are keyword / date / long fields sorted as they are. {@code type} sorts
     * folders (no extension) apart from files. Any other name is ignored — sorting on a field the
     * index does not map fails the whole query.
     */
    java.util.Map<String, String> SORT_FIELDS = java.util.Map.of(
            NAME, NAME + KEYWORD,
            "type", EXTENSION,
            EXTENSION, EXTENSION,
            OpenSearchDocumentKey.contentType.toString(), OpenSearchDocumentKey.contentType.toString(),
            OpenSearchDocumentKey.size.toString(), OpenSearchDocumentKey.size.toString(),
            OpenSearchDocumentKey.createdAt.toString(), OpenSearchDocumentKey.createdAt.toString(),
            OpenSearchDocumentKey.updatedAt.toString(), OpenSearchDocumentKey.updatedAt.toString(),
            CREATED_BY, CREATED_BY,
            UPDATED_BY, UPDATED_BY
    );

    default void addSorting(SortInput sort, SearchRequest.Builder requestBuilder) {
        // 5. Add Sorting (none: relevance order)
        if (sort == null || !StringUtils.hasText(sort.field())) {
            return;
        }
        String sortField = SORT_FIELDS.get(sort.field());
        if (sortField == null) {
            return;
        }
        var osSortOrder = sort.order() == SortOrder.ASC ?
                org.opensearch.client.opensearch._types.SortOrder.Asc :
                org.opensearch.client.opensearch._types.SortOrder.Desc;
        // unmappedType: an index created before a field was mapped (contentType) sorts instead of failing
        requestBuilder.sort(s -> s.field(f -> f.field(sortField).order(osSortOrder).unmappedType(FieldType.Keyword)));
        // Ties keep a stable order across pages
        requestBuilder.sort(s -> s.field(f -> f.field(SUGGEST_ID).order(osSortOrder)));
    }

}
