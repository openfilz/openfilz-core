<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('totp'); section>

    <#if section = "header">
        ${msg("loginOtpTitle")}
    <#elseif section = "subtitle">
        ${msg("loginOtpSubtitle")}

    <#elseif section = "form">
        <form id="kc-otp-login-form" action="${url.loginAction}" method="post" novalidate>

            <#-- OTP Device Selector (if multiple configured) -->
            <#if otpLogin.userOtpCredentials?size gt 1>
                <div class="of-form-group">
                    <label class="of-label">${msg("loginOtpDevice")}</label>
                    <#list otpLogin.userOtpCredentials as otpCredential>
                        <div class="of-checkbox-group">
                            <input
                                id="kc-otp-credential-${otpCredential?index}"
                                name="selectedCredentialId"
                                type="radio"
                                class="of-checkbox"
                                value="${otpCredential.id}"
                                <#if otpCredential.id == otpLogin.selectedCredentialId>checked</#if>
                            />
                            <label for="kc-otp-credential-${otpCredential?index}" class="of-checkbox-label">
                                ${otpCredential.userLabel}
                            </label>
                        </div>
                    </#list>
                </div>
            </#if>

            <#-- OTP Info -->
            <p class="of-otp-info">
                ${msg("loginOtpInstruction")}
            </p>

            <#-- OTP Input -->
            <div class="of-form-group">
                <label for="otp" class="of-label">${msg("loginOtpCode")}</label>
                <div class="of-input-wrapper">
                    <input
                        id="otp"
                        name="otp"
                        type="text"
                        class="of-input of-otp-input<#if messagesPerField.existsError('totp')> of-input--error</#if>"
                        autofocus
                        autocomplete="one-time-code"
                        inputmode="numeric"
                        pattern="[0-9]*"
                        aria-invalid="<#if messagesPerField.existsError('totp')>true<#else>false</#if>"
                        <#if messagesPerField.existsError('totp')>aria-describedby="input-error-otp"</#if>
                        placeholder="${msg("otpPlaceholder")}"
                    />
                </div>
                <#if messagesPerField.existsError('totp')>
                    <span id="input-error-otp" class="of-field-error" role="alert">
                        ${kcSanitize(messagesPerField.get('totp'))?no_esc}
                    </span>
                </#if>
            </div>

            <#-- Submit -->
            <div class="of-form-group of-mb-0">
                <button type="submit" class="of-btn of-btn--primary">
                    ${msg("doLogIn")}
                </button>
            </div>
        </form>

    </#if>
</@layout.registrationLayout>
