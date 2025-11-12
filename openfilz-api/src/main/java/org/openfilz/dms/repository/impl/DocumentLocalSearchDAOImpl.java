package org.openfilz.dms.repository.impl;

import io.r2dbc.spi.Readable;
import lombok.RequiredArgsConstructor;
import org.openfilz.dms.dto.response.Suggest;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.repository.DocumentLocalSearchDAO;
import org.openfilz.dms.utils.FileUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.UUID;

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

    private Suggest toSuggest(Readable row) {
        UUID uuid = row.get(0, UUID.class);
        String name = row.get(1, String.class);
        DocumentType type = DocumentType.valueOf(row.get(2, String.class));
        String ext = FileUtils.getFileExtension(type, name);
        String s = FileUtils.removeFileExtension(name);
        return new Suggest(uuid, s, ext);
    }
}
