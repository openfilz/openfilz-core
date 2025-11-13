package org.openfilz.dms.repository.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.dto.response.Suggest;
import org.openfilz.dms.repository.DocumentLocalSearchDAO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
@ConditionalOnProperties(value = {
        @ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "false", matchIfMissing = true),
        @ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
})
public class DocumentLocalSearchDAOImpl implements DocumentLocalSearchDAO {

    private final DatabaseClient databaseClient;


    @Override
    public Flux<Suggest> getSuggestions(String query) {
        return databaseClient.sql("select id, name, type from documents where lower(name) like :q limit 10")
                .bind("q", "%" + query.toLowerCase() + "%")
                .map(this::toSuggest)
                .all();
    }

}
