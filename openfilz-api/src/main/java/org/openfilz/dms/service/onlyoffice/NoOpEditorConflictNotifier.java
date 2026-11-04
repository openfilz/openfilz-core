package org.openfilz.dms.service.onlyoffice;

import org.springframework.stereotype.Component;

/** The default {@link EditorConflictNotifier}; an extension replaces it with a {@code @Primary} bean. */
@Component
public class NoOpEditorConflictNotifier implements EditorConflictNotifier {
}
