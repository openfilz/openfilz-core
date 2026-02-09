<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>

    <#if section = "header">
        ${msg("errorTitle")}
    <#elseif section = "subtitle">
        ${msg("errorSubtitle")}

    <#elseif section = "form">

        <#-- Error Icon -->
        <div class="of-error-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="12" cy="12" r="10"/>
                <line x1="15" y1="9" x2="9" y2="15"/>
                <line x1="9" y1="9" x2="15" y2="15"/>
            </svg>
        </div>

        <#-- Error Message -->
        <div class="of-error-message" role="alert">
            <#if message?has_content>
                ${kcSanitize(message.summary)?no_esc}
            <#else>
                ${msg("errorGeneric")}
            </#if>
        </div>

        <#-- Back to Application -->
        <#if skipLink??>
        <#else>
            <#if client?? && client.baseUrl?has_content>
                <a href="${client.baseUrl}" class="of-btn of-btn--primary">
                    ${msg("backToApplication")}
                </a>
            </#if>
        </#if>

    </#if>
</@layout.registrationLayout>
