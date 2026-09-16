package org.openfilz.dms.service.insight;

import org.openfilz.dms.dto.response.InsightFacets;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * GraalVM native-image hints for the facets DTO: {@link InsightFacets} is a record holding a
 * nested record in two lists, serialised by Jackson from a controller — registered explicitly
 * like the other AI DTOs built outside Spring's own binding scan, so the native image keeps the
 * record components and their accessors.
 */
public class InsightFacetsRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        for (Class<?> type : new Class<?>[]{InsightFacets.class, InsightFacets.Facet.class}) {
            hints.reflection().registerType(TypeReference.of(type),
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.DECLARED_FIELDS);
        }
    }
}
