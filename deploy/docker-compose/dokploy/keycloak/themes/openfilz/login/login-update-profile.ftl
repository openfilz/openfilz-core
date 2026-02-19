<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('firstName','lastName','email','username'); section>

    <#if section = "header">
        ${msg("loginProfileTitle")}
    <#elseif section = "subtitle">
        ${msg("loginProfileSubtitle")}

    <#elseif section = "form">
        <form id="kc-update-profile-form" action="${url.loginAction}" method="post" novalidate>

            <#-- Email (read-only, provided by the identity provider) -->
            <div class="of-form-group">
                <label for="email" class="of-label">
                    ${msg("email")}
                </label>
                <div class="of-input-wrapper">
                    <input
                        id="email"
                        name="email"
                        type="email"
                        class="of-input of-input--readonly"
                        value="${(user.email!'')}"
                        readonly
                        tabindex="-1"
                    />
                </div>
                <span class="of-field-hint">${msg("loginProfileEmailReadonly")}</span>
            </div>

            <#-- First Name -->
            <div class="of-form-group">
                <label for="firstName" class="of-label">
                    ${msg("firstName")} <span class="of-required" aria-hidden="true">*</span>
                </label>
                <div class="of-input-wrapper">
                    <input
                        id="firstName"
                        name="firstName"
                        type="text"
                        class="of-input<#if messagesPerField.existsError('firstName')> of-input--error</#if>"
                        value="${(user.firstName!'')}"
                        autocomplete="given-name"
                        autofocus
                        required
                        aria-invalid="<#if messagesPerField.existsError('firstName')>true<#else>false</#if>"
                        <#if messagesPerField.existsError('firstName')>aria-describedby="input-error-firstname"</#if>
                        placeholder="${msg("firstNamePlaceholder")}"
                    />
                </div>
                <#if messagesPerField.existsError('firstName')>
                    <span id="input-error-firstname" class="of-field-error" role="alert">
                        ${kcSanitize(messagesPerField.get('firstName'))?no_esc}
                    </span>
                </#if>
            </div>

            <#-- Last Name -->
            <div class="of-form-group">
                <label for="lastName" class="of-label">
                    ${msg("lastName")} <span class="of-required" aria-hidden="true">*</span>
                </label>
                <div class="of-input-wrapper">
                    <input
                        id="lastName"
                        name="lastName"
                        type="text"
                        class="of-input<#if messagesPerField.existsError('lastName')> of-input--error</#if>"
                        value="${(user.lastName!'')}"
                        autocomplete="family-name"
                        required
                        aria-invalid="<#if messagesPerField.existsError('lastName')>true<#else>false</#if>"
                        <#if messagesPerField.existsError('lastName')>aria-describedby="input-error-lastname"</#if>
                        placeholder="${msg("lastNamePlaceholder")}"
                    />
                </div>
                <#if messagesPerField.existsError('lastName')>
                    <span id="input-error-lastname" class="of-field-error" role="alert">
                        ${kcSanitize(messagesPerField.get('lastName'))?no_esc}
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

    </#if>
</@layout.registrationLayout>
