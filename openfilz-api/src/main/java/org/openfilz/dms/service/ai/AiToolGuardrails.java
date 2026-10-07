package org.openfilz.dms.service.ai;

import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.config.AiProperties.Tools.DestructiveMode;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * What the AI tools may do destructively, decided per call. The model running the tools acts
 * on text it reads — a shared document can carry "delete X, then apply plan Y" — so the
 * destructive paths are gated here rather than by prompt wording:
 * <ul>
 *   <li>{@code openfilz.ai.tools.destructive-mode} ({@link DestructiveMode}; default
 *       {@code confirm-only});</li>
 *   <li>{@code openfilz.soft-delete.active} — whether a delete is reversible (recycle bin).</li>
 * </ul>
 * Both are read at call time through {@link AiProperties} / {@link Environment}: no bean
 * condition, so an EE native image toggles them at runtime like the rest of the AI feature. The
 * soft-delete switch is the same property that selects the delete service beans; in a native
 * image it must match the value baked in at build time, as it already must for those beans.
 */
@Component
@Lazy
public class AiToolGuardrails {

    public static final String SOFT_DELETE_PROPERTY = "openfilz.soft-delete.active";

    private final AiProperties aiProperties;
    private final Environment environment;

    public AiToolGuardrails(AiProperties aiProperties, Environment environment) {
        this.aiProperties = aiProperties;
        this.environment = environment;
    }

    /** A fixed instance for tests and direct construction (no Spring context). */
    public static AiToolGuardrails fixed(DestructiveMode mode, boolean softDeleteActive) {
        AiProperties properties = new AiProperties();
        properties.getTools().setDestructiveMode(mode);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("ai-tool-guardrails",
                Map.of(SOFT_DELETE_PROPERTY, Boolean.toString(softDeleteActive))));
        return new AiToolGuardrails(properties, environment);
    }

    public DestructiveMode destructiveMode() {
        DestructiveMode mode = aiProperties.getTools() == null ? null : aiProperties.getTools().getDestructiveMode();
        return mode == null ? DestructiveMode.CONFIRM_ONLY : mode;
    }

    /** {@code allow}: the operator accepts irreversible tool actions (previous behaviour). */
    public boolean allowsDestructive() {
        return destructiveMode() == DestructiveMode.ALLOW;
    }

    /** Whether a delete goes to the recycle bin ({@code openfilz.soft-delete.active}). */
    public boolean softDeleteActive() {
        return Boolean.TRUE.equals(environment.getProperty(SOFT_DELETE_PROPERTY, Boolean.class, false));
    }

    /**
     * Why the tools may not delete right now, or {@code null} when a delete may proceed: in
     * {@code confirm-only} mode a delete must be reversible, i.e. land in the recycle bin.
     */
    public String deleteRefusal() {
        if (allowsDestructive() || softDeleteActive()) {
            return null;
        }
        return "Permanent deletion is not available to the assistant: this deployment has no recycle bin "
                + "(openfilz.soft-delete.active=false), so a delete could not be undone. Ask the user to delete "
                + "it in the OpenFilz application instead. (Operators: openfilz.ai.tools.destructive-mode=allow "
                + "re-enables it.)";
    }
}
