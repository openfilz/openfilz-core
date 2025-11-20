package org.openfilz.dms.service;

import org.openfilz.dms.enums.OpenSearchDocumentKey;
import org.opensearch.client.opensearch._types.mapping.DynamicMapping;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.util.ObjectBuilder;

import java.util.function.Function;

public interface IndexMappingsProvider {

    default Function<TypeMapping.Builder, ObjectBuilder<TypeMapping>> getIndexMappings() {
        return this::baseMappings;
    }

    default TypeMapping.Builder baseMappings(TypeMapping.Builder m) {
        return m
                .properties(OpenSearchDocumentKey.id.toString(), p -> p.keyword(k -> k))
                .properties(OpenSearchDocumentKey.name.toString(), p -> p.text(tx -> tx.fields("keyword", b -> b.keyword(builder -> builder))))
                .properties(OpenSearchDocumentKey.name_suggest.toString(), p -> p.searchAsYouType(builder -> builder))
                .properties(OpenSearchDocumentKey.extension.toString(), p -> p.keyword(k -> k))
                .properties(OpenSearchDocumentKey.size.toString(), p -> p.long_(k -> k))
                .properties(OpenSearchDocumentKey.parentId.toString(), p -> p.keyword(k -> k))
                .properties(OpenSearchDocumentKey.createdAt.toString(), p -> p.date(k -> k))
                .properties(OpenSearchDocumentKey.updatedAt.toString(), p -> p.date(k -> k))
                .properties(OpenSearchDocumentKey.createdBy.toString(), p -> p.keyword(k -> k))
                .properties(OpenSearchDocumentKey.updatedBy.toString(), p -> p.keyword(k -> k))
                .properties(OpenSearchDocumentKey.content.toString(), p -> p.text(txt -> txt.analyzer("standard")))
                .properties(OpenSearchDocumentKey.metadata.toString(), p -> p.object(tx -> tx.dynamic(DynamicMapping.True)));
    }

}
