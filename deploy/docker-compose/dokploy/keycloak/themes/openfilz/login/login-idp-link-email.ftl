<#--
    Shown on the first sign-in through Google, Microsoft or GitHub when an account with the same
    e-mail already exists and the provider did not verify that e-mail itself (step
    idp-email-verification of the "openfilz link existing account" flow). Keycloak has e-mailed a
    link to the existing account's address; the provider is linked once the owner opens it.
    Both links post back to url.loginAction: Keycloak re-sends the e-mail, or carries on when the
    link was already opened in another browser.
-->
<#import "template.ftl" as layout>
<#-- Keycloak's own warning repeats the text below; errors and the re-send notice still show. -->
<@layout.registrationLayout displayMessage=!(message?? && message.type = 'warning'); section>

    <#if section = "header">
        ${msg("idpLinkEmailTitle")}
    <#elseif section = "subtitle">
        ${msg("idpLinkEmailSubtitle", idpDisplayName)}

    <#elseif section = "form">
        <div class="of-success-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                <rect x="2" y="4" width="20" height="16" rx="2"/>
                <path d="m22 7-10 6L2 7"/>
            </svg>
        </div>

        <div class="of-info-message" id="instruction1">
            ${msg("idpLinkEmailSent", idpDisplayName, (brokerContext.email)!(brokerContext.username), layout.brandName)}
        </div>

        <div class="of-text-center">
            <p class="of-field-hint" id="instruction2">
                ${msg("idpLinkEmailNotReceived")}
                <a href="${url.loginAction}" class="of-link">${msg("idpLinkEmailResend")}</a>
            </p>
            <p class="of-field-hint" id="instruction3">
                ${msg("idpLinkEmailAlreadyConfirmed")}
                <a href="${url.loginAction}" class="of-link">${msg("idpLinkEmailContinue")}</a>
            </p>
        </div>
    </#if>
</@layout.registrationLayout>
