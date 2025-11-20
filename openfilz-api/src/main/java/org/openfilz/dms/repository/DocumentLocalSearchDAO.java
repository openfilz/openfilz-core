package org.openfilz.dms.repository;

import org.openfilz.dms.dto.request.FilterInput;
import org.openfilz.dms.dto.request.SortInput;
import org.openfilz.dms.dto.response.Suggest;
import reactor.core.publisher.Flux;

import java.util.List;

public interface DocumentLocalSearchDAO {

    Flux<Suggest> getSuggestions(String query, List<FilterInput> filters, SortInput sort);

}
