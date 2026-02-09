<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('firstName','lastName','email','username','password','password-confirm'); section>

    <#if section = "header">
        ${msg("registerTitle")}
    <#elseif section = "subtitle">
        ${msg("registerSubtitle")}

    <#elseif section = "form">
        <form id="kc-register-form" action="${url.registrationAction}" method="post" novalidate>

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
                        value="${(register.formData.firstName!'')}"
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
                        value="${(register.formData.lastName!'')}"
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

            <#-- Email -->
            <div class="of-form-group">
                <label for="email" class="of-label">
                    ${msg("email")} <span class="of-required" aria-hidden="true">*</span>
                </label>
                <div class="of-input-wrapper">
                    <input
                        id="email"
                        name="email"
                        type="email"
                        class="of-input<#if messagesPerField.existsError('email')> of-input--error</#if>"
                        value="${(register.formData.email!'')}"
                        autocomplete="email"
                        required
                        aria-invalid="<#if messagesPerField.existsError('email')>true<#else>false</#if>"
                        <#if messagesPerField.existsError('email')>aria-describedby="input-error-email"</#if>
                        placeholder="${msg("emailPlaceholder")}"
                    />
                </div>
                <#if messagesPerField.existsError('email')>
                    <span id="input-error-email" class="of-field-error" role="alert">
                        ${kcSanitize(messagesPerField.get('email'))?no_esc}
                    </span>
                </#if>
            </div>

            <#-- Username (only if not using email as username) -->
            <#if !realm.registrationEmailAsUsername>
                <div class="of-form-group">
                    <label for="username" class="of-label">
                        ${msg("username")} <span class="of-required" aria-hidden="true">*</span>
                    </label>
                    <div class="of-input-wrapper">
                        <input
                            id="username"
                            name="username"
                            type="text"
                            class="of-input<#if messagesPerField.existsError('username')> of-input--error</#if>"
                            value="${(register.formData.username!'')}"
                            autocomplete="username"
                            required
                            aria-invalid="<#if messagesPerField.existsError('username')>true<#else>false</#if>"
                            <#if messagesPerField.existsError('username')>aria-describedby="input-error-username"</#if>
                            placeholder="${msg("usernamePlaceholder")}"
                        />
                    </div>
                    <#if messagesPerField.existsError('username')>
                        <span id="input-error-username" class="of-field-error" role="alert">
                            ${kcSanitize(messagesPerField.get('username'))?no_esc}
                        </span>
                    </#if>
                </div>
            </#if>

            <#-- Password -->
            <div class="of-form-group">
                <label for="password" class="of-label">
                    ${msg("password")} <span class="of-required" aria-hidden="true">*</span>
                </label>
                <div class="of-input-wrapper of-input-wrapper--password">
                    <input
                        id="password"
                        name="password"
                        type="password"
                        class="of-input<#if messagesPerField.existsError('password')> of-input--error</#if>"
                        autocomplete="new-password"
                        required
                        aria-invalid="<#if messagesPerField.existsError('password')>true<#else>false</#if>"
                        <#if messagesPerField.existsError('password')>aria-describedby="input-error-password"</#if>
                        placeholder="${msg("passwordPlaceholder")}"
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

            <#-- Confirm Password -->
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
                        placeholder="${msg("passwordConfirmPlaceholder")}"
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

            <#-- reCAPTCHA (if enabled) -->
            <#if recaptchaRequired??>
                <div class="of-form-group">
                    <div class="g-recaptcha" data-size="compact" data-sitekey="${recaptchaSiteKey}"></div>
                </div>
            </#if>

            <#-- Submit -->
            <div class="of-form-group of-mb-0">
                <button type="submit" class="of-btn of-btn--primary">
                    ${msg("doRegister")}
                </button>
            </div>
        </form>

        <#-- Back to Login -->
        <div class="of-card-footer">
            <p class="of-card-footer-text">
                ${msg("alreadyHaveAccount")} <a href="${url.loginUrl}">${msg("backToLogin")}</a>
            </p>
        </div>

    </#if>
</@layout.registrationLayout>
