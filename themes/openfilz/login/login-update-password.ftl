<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('password','password-new','password-confirm'); section>

    <#if section = "header">
        ${msg("updatePasswordTitle")}
    <#elseif section = "subtitle">
        ${msg("updatePasswordSubtitle")}

    <#elseif section = "form">
        <form id="kc-passwd-update-form" action="${url.loginAction}" method="post" novalidate>

            <#-- Current Password (if required) -->
            <#if currentPassword?has_content>
                <div class="of-form-group">
                    <label for="password" class="of-label">
                        ${msg("passwordCurrent")} <span class="of-required" aria-hidden="true">*</span>
                    </label>
                    <div class="of-input-wrapper of-input-wrapper--password">
                        <input
                            id="password"
                            name="password"
                            type="password"
                            class="of-input<#if messagesPerField.existsError('password')> of-input--error</#if>"
                            autofocus
                            autocomplete="current-password"
                            required
                            aria-invalid="<#if messagesPerField.existsError('password')>true<#else>false</#if>"
                            <#if messagesPerField.existsError('password')>aria-describedby="input-error-password"</#if>
                        />
                        <button
                            type="button"
                            class="of-password-toggle"
                            data-target="password"
                            data-label-show="${msg("showPassword")}"
                            data-label-hide="${msg("hidePassword")}"
                            aria-label="${msg("showPassword")}"
                        >
                            <svg class="of-eye-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                                <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                                <circle cx="12" cy="12" r="3" stroke="currentColor" stroke-width="2"/>
                            </svg>
                        </button>
                    </div>
                    <#if messagesPerField.existsError('password')>
                        <span id="input-error-password" class="of-field-error" role="alert">
                            ${kcSanitize(messagesPerField.get('password'))?no_esc}
                        </span>
                    </#if>
                </div>
            </#if>

            <input type="hidden" id="username" name="username" value="${username}" readonly="readonly"/>

            <#-- New Password -->
            <div class="of-form-group">
                <label for="password-new" class="of-label">
                    ${msg("passwordNew")} <span class="of-required" aria-hidden="true">*</span>
                </label>
                <div class="of-input-wrapper of-input-wrapper--password">
                    <input
                        id="password-new"
                        name="password-new"
                        type="password"
                        class="of-input<#if messagesPerField.existsError('password-new')> of-input--error</#if>"
                        <#if !currentPassword?has_content>autofocus</#if>
                        autocomplete="new-password"
                        required
                        aria-invalid="<#if messagesPerField.existsError('password-new')>true<#else>false</#if>"
                        <#if messagesPerField.existsError('password-new')>aria-describedby="input-error-password-new"</#if>
                        placeholder="${msg("newPasswordPlaceholder")}"
                    />
                    <button
                        type="button"
                        class="of-password-toggle"
                        data-target="password-new"
                        data-label-show="${msg("showPassword")}"
                        data-label-hide="${msg("hidePassword")}"
                        aria-label="${msg("showPassword")}"
                    >
                        <svg class="of-eye-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                            <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                            <circle cx="12" cy="12" r="3" stroke="currentColor" stroke-width="2"/>
                        </svg>
                    </button>
                </div>
                <#if messagesPerField.existsError('password-new')>
                    <span id="input-error-password-new" class="of-field-error" role="alert">
                        ${kcSanitize(messagesPerField.get('password-new'))?no_esc}
                    </span>
                </#if>
            </div>

            <#-- Confirm New Password -->
            <div class="of-form-group">
                <label for="password-confirm" class="of-label">
                    ${msg("passwordConfirm")} <span class="of-required" aria-hidden="true">*</span>
                </label>
                <div class="of-input-wrapper of-input-wrapper--password">
                    <input
                        id="password-confirm"
                        name="password-confirm"
                        type="password"
                        class="of-input<#if messagesPerField.existsError('password-confirm')> of-input--error</#if>"
                        autocomplete="new-password"
                        required
                        aria-invalid="<#if messagesPerField.existsError('password-confirm')>true<#else>false</#if>"
                        <#if messagesPerField.existsError('password-confirm')>aria-describedby="input-error-password-confirm"</#if>
                        placeholder="${msg("confirmNewPasswordPlaceholder")}"
                    />
                    <button
                        type="button"
                        class="of-password-toggle"
                        data-target="password-confirm"
                        data-label-show="${msg("showPassword")}"
                        data-label-hide="${msg("hidePassword")}"
                        aria-label="${msg("showPassword")}"
                    >
                        <svg class="of-eye-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                            <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                            <circle cx="12" cy="12" r="3" stroke="currentColor" stroke-width="2"/>
                        </svg>
                    </button>
                </div>
                <#if messagesPerField.existsError('password-confirm')>
                    <span id="input-error-password-confirm" class="of-field-error" role="alert">
                        ${kcSanitize(messagesPerField.get('password-confirm'))?no_esc}
                    </span>
                </#if>
            </div>

            <#-- Logout other sessions checkbox -->
            <div class="of-checkbox-group">
                <input
                    id="logout-sessions"
                    name="logout-sessions"
                    type="checkbox"
                    class="of-checkbox"
                    value="on"
                    checked
                />
                <label for="logout-sessions" class="of-checkbox-label">
                    ${msg("logoutOtherSessions")}
                </label>
            </div>

            <#-- Submit -->
            <div class="of-form-group of-mb-0">
                <button type="submit" class="of-btn of-btn--primary">
                    ${msg("doSubmit")}
                </button>
            </div>
        </form>

    </#if>
</@layout.registrationLayout>
