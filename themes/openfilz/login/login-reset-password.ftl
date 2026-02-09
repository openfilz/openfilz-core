<#import "template.ftl" as layout>
<@layout.registrationLayout displayInfo=true displayMessage=!messagesPerField.existsError('username'); section>

    <#if section = "header">
        ${msg("emailForgotTitle")}
    <#elseif section = "subtitle">
        ${msg("emailForgotSubtitle")}

    <#elseif section = "form">
        <form id="kc-reset-password-form" action="${url.loginAction}" method="post" novalidate>

            <#-- Username / Email -->
            <div class="of-form-group">
                <label for="username" class="of-label">
                    <#if !realm.loginWithEmailAllowed>
                        ${msg("username")}
                    <#elseif !realm.registrationEmailAsUsername>
                        ${msg("usernameOrEmail")}
                    <#else>
                        ${msg("email")}
                    </#if>
                </label>
                <div class="of-input-wrapper">
                    <input
                        id="username"
                        name="username"
                        type="text"
                        class="of-input<#if messagesPerField.existsError('username')> of-input--error</#if>"
                        autofocus
                        autocomplete="username"
                        aria-invalid="<#if messagesPerField.existsError('username')>true<#else>false</#if>"
                        <#if messagesPerField.existsError('username')>aria-describedby="input-error-username"</#if>
                        placeholder="${msg("resetPasswordPlaceholder")}"
                    />
                </div>
                <#if messagesPerField.existsError('username')>
                    <span id="input-error-username" class="of-field-error" role="alert">
                        ${kcSanitize(messagesPerField.get('username'))?no_esc}
                    </span>
                </#if>
            </div>

            <#-- Submit -->
            <div class="of-form-group of-mb-0">
                <button type="submit" class="of-btn of-btn--primary">
                    ${msg("doSubmit")}
                </button>
            </div>
        </form>

        <#-- Back to Login -->
        <div class="of-card-footer">
            <p class="of-card-footer-text">
                ${msg("rememberedPassword")} <a href="${url.loginUrl}">${msg("backToLogin")}</a>
            </p>
        </div>

    <#elseif section = "info">
        <p class="of-text-center" style="font-size: 13px; color: var(--of-text-secondary);">
            ${msg("emailInstruction")}
        </p>
    </#if>
</@layout.registrationLayout>
