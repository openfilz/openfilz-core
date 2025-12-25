package org.openfilz.dms.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response containing OnlyOffice editor configuration.
 * Sent to the frontend to initialize the OnlyOffice editor.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OnlyOfficeConfigResponse(
        String documentServerUrl,
        String apiJsUrl,
        OnlyOfficeDocumentConfig config,
        String token
) {
    /**
     * Full OnlyOffice editor configuration.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OnlyOfficeDocumentConfig(
            DocumentInfo document,
            EditorConfig editorConfig,
            String documentType  // "word", "cell", "slide"
    ) {}

    /**
     * Document information for OnlyOffice.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DocumentInfo(
            String fileType,
            String key,
            String title,
            String url,
            Permissions permissions
    ) {}

    /**
     * Document permissions in OnlyOffice.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Permissions(
            boolean download,
            boolean edit,
            boolean print,
            boolean review,
            boolean comment
    ) {}

    /**
     * Editor configuration settings.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EditorConfig(
            String callbackUrl,
            String lang,
            String mode,  // "edit" or "view"
            IUserInfo user,
            Customization customization
    ) {}

    /**
     * Editor customization options.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Customization(
            boolean autosave,
            boolean chat,
            boolean comments,
            boolean forcesave
    ) {}
}
