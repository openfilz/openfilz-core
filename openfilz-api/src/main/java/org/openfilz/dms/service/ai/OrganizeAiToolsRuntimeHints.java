package org.openfilz.dms.service.ai;

import org.openfilz.dms.dto.request.ReorganizationPlanRequest;
import org.openfilz.dms.dto.response.ReorganizationApplyResult;
import org.openfilz.dms.dto.response.ReorganizationPlanView;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * GraalVM native-image hints for {@link OrganizeAiTools}: the tool object is built per call (not a
 * bean), so its {@code @Tool} methods must be registered for reflection like
 * {@link PdfAiToolsRuntimeHints} does; the plan records are (de)serialised with Jackson from the
 * model's JSON and the JSONB column, so they need their constructors and accessors too.
 */
public class OrganizeAiToolsRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.reflection().registerType(TypeReference.of(OrganizeAiTools.class),
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_DECLARED_METHODS);
        for (Class<?> type : new Class<?>[]{
                ReorganizationPlanRequest.class, ReorganizationPlanRequest.Move.class,
                ReorganizationPlanView.class, ReorganizationPlanView.Item.class, ReorganizationPlanView.ItemResult.class,
                ReorganizationApplyResult.class, ReorganizationPlanService.StoredPlan.class}) {
            hints.reflection().registerType(TypeReference.of(type),
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.DECLARED_FIELDS);
        }
    }
}
