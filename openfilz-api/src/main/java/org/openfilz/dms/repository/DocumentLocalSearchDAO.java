package org.openfilz.dms.repository;

import org.openfilz.dms.dto.response.Suggest;
import reactor.core.publisher.Flux;

public interface DocumentLocalSearchDAO {
    Flux<Suggest> getSuggestions(String query);
}
