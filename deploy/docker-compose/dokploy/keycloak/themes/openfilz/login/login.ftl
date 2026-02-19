<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('username','password') displayInfo=realm.password && realm.registrationAllowed && !registrationDisabled??; section>

    <#if section = "header">
        ${msg("loginWelcome")}
    <#elseif section = "subtitle">
        ${msg("loginSubtitle")}

    <#elseif section = "socialProviders">
        <#if realm.password && social?? && social.providers?? && (social.providers?size gt 0)>
            <#-- Social/External Identity Providers -->
            <div class="of-social-section">
                <div class="of-social-buttons">
                    <#list social.providers as p>
                        <a
                            href="${p.loginUrl}"
                            class="of-social-btn of-social-btn--${p.alias!p.providerId}"
                            aria-label="${msg("loginWith", p.displayName)}"
                        >
                            <#-- Provider Icons -->
                            <#if p.providerId == "google">
                                <svg class="of-social-icon" viewBox="0 0 24 24" aria-hidden="true">
                                    <path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 0 1-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z" fill="#4285F4"/>
                                    <path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853"/>
                                    <path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" fill="#FBBC05"/>
                                    <path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" fill="#EA4335"/>
                                </svg>
                            <#elseif p.providerId == "microsoft" || p.alias?contains("microsoft")>
                                <svg class="of-social-icon" viewBox="0 0 24 24" aria-hidden="true">
                                    <rect x="1" y="1" width="10" height="10" fill="#F25022"/>
                                    <rect x="13" y="1" width="10" height="10" fill="#7FBA00"/>
                                    <rect x="1" y="13" width="10" height="10" fill="#00A4EF"/>
                                    <rect x="13" y="13" width="10" height="10" fill="#FFB900"/>
                                </svg>
                            <#elseif p.providerId == "github">
                                <svg class="of-social-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                                    <path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0 0 24 12c0-6.63-5.37-12-12-12z"/>
                                </svg>
                            <#elseif p.providerId == "facebook">
                                <svg class="of-social-icon" viewBox="0 0 24 24" fill="#1877F2" aria-hidden="true">
                                    <path d="M24 12.073c0-6.627-5.373-12-12-12s-12 5.373-12 12c0 5.99 4.388 10.954 10.125 11.854v-8.385H7.078v-3.47h3.047V9.43c0-3.007 1.792-4.669 4.533-4.669 1.312 0 2.686.235 2.686.235v2.953H15.83c-1.491 0-1.956.925-1.956 1.874v2.25h3.328l-.532 3.47h-2.796v8.385C19.612 23.027 24 18.062 24 12.073z"/>
                                </svg>
                            <#elseif p.providerId == "apple">
                                <svg class="of-social-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                                    <path d="M17.05 20.28c-.98.95-2.05.88-3.08.4-1.09-.5-2.08-.48-3.24 0-1.44.62-2.2.44-3.06-.4C2.79 15.25 3.51 7.59 9.05 7.31c1.35.07 2.29.74 3.08.8 1.18-.24 2.31-.93 3.57-.84 1.51.12 2.65.72 3.4 1.8-3.12 1.87-2.38 5.98.48 7.13-.57 1.5-1.31 2.99-2.54 4.09zM12.03 7.25c-.15-2.23 1.66-4.07 3.74-4.25.29 2.58-2.34 4.5-3.74 4.25z"/>
                                </svg>
                            <#else>
                                <svg class="of-social-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                                    <circle cx="12" cy="12" r="10"/>
                                    <line x1="2" y1="12" x2="22" y2="12"/>
                                    <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/>
                                </svg>
                            </#if>
                            <span>${msg("loginWith", p.displayName)}</span>
                        </a>
                    </#list>
                </div>
            </div>

            <#-- Separator -->
            <div class="of-separator" role="separator">
                <span class="of-separator-text">${msg("or")}</span>
            </div>
        </#if>

    <#elseif section = "form">
        <#if realm.password>
            <form id="kc-form-login" action="${url.loginAction}" method="post" novalidate>

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
                            class="of-input<#if messagesPerField.existsError('username','password')> of-input--error</#if>"
                            value="${(login.username!'')}"
                            autocomplete="username"
                            autofocus
                            aria-invalid="<#if messagesPerField.existsError('username','password')>true<#else>false</#if>"
                            <#if messagesPerField.existsError('username','password')>aria-describedby="input-error"</#if>
                            placeholder="${msg("usernamePlaceholder")}"
                        />
                    </div>
                    <#if messagesPerField.existsError('username','password')>
                        <span id="input-error" class="of-field-error" role="alert">
                            ${kcSanitize(messagesPerField.getFirstError('username','password'))?no_esc}
                        </span>
                    </#if>
                </div>

                <#-- Password -->
                <div class="of-form-group">
                    <label for="password" class="of-label">${msg("password")}</label>
                    <div class="of-input-wrapper of-input-wrapper--password">
                        <input
                            id="password"
                            name="password"
                            type="password"
                            class="of-input<#if messagesPerField.existsError('username','password')> of-input--error</#if>"
                            autocomplete="current-password"
                            aria-invalid="<#if messagesPerField.existsError('username','password')>true<#else>false</#if>"
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
                </div>

                <#-- Remember Me & Forgot Password -->
                <div class="of-form-links">
                    <#if realm.rememberMe && !usernameHidden??>
                        <div class="of-checkbox-group of-mb-0">
                            <input
                                id="rememberMe"
                                name="rememberMe"
                                type="checkbox"
                                class="of-checkbox"
                                <#if login.rememberMe??>checked</#if>
                            />
                            <label for="rememberMe" class="of-checkbox-label">${msg("rememberMe")}</label>
                        </div>
                    </#if>

                    <#if realm.resetPasswordAllowed>
                        <a href="${url.loginResetCredentialsUrl}" class="of-link">${msg("doForgotPassword")}</a>
                    </#if>
                </div>

                <#-- Submit Button -->
                <div class="of-form-group of-mb-0">
                    <input
                        type="hidden"
                        id="id-hidden-input"
                        name="credentialId"
                        <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>
                    />
                    <button type="submit" class="of-btn of-btn--primary" id="kc-login">
                        ${msg("doLogIn")}
                    </button>
                </div>
            </form>
        </#if>

        <#-- Registration Link -->
        <#if realm.password && realm.registrationAllowed && !registrationDisabled??>
            <div class="of-card-footer">
                <p class="of-card-footer-text">
                    ${msg("noAccount")} <a href="${url.registrationUrl}">${msg("doRegister")}</a>
                </p>
            </div>
        </#if>

    </#if>
</@layout.registrationLayout>
