package org.openfilz.dms.service;

import org.openfilz.dms.enums.OpenSearchDocumentKey;

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

}
