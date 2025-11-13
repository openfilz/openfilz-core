package org.openfilz.dms.repository;

import io.r2dbc.spi.Readable;
import org.openfilz.dms.dto.response.Suggest;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.utils.FileUtils;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface DocumentLocalSearchDAO {

    default Suggest toSuggest(Readable row) {
        UUID uuid = row.get(0, UUID.class);
        String name = row.get(1, String.class);
        DocumentType type = DocumentType.valueOf(row.get(2, String.class));
        String ext = FileUtils.getFileExtension(type, name);
        String s = FileUtils.removeFileExtension(name);
        return new Suggest(uuid, s, ext);
    }

    Flux<Suggest> getSuggestions(String query);
}
