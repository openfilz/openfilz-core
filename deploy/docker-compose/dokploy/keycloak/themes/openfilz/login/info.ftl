<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>

    <#if section = "header">
        ${msg("infoTitle")}
    <#elseif section = "subtitle">
        ${msg("infoSubtitle")}

    <#elseif section = "form">

        <#-- Success Icon -->
        <div class="of-success-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/>
                <polyline points="22 4 12 14.01 9 11.01"/>
            </svg>
        </div>

        <#-- Info Message -->
        <div class="of-info-message">
            <#if message?has_content>
                ${kcSanitize(message.summary)?no_esc}
            </#if>
        </div>

        <#-- Redirect to application -->
        <#if pageRedirectUri?has_content>
            <a href="${pageRedirectUri}" class="of-btn of-btn--primary">
                ${msg("goToOpenFilz")}
            </a>
        <#elseif actionUri?has_content>
            <a href="${actionUri}" class="of-btn of-btn--primary">
                ${msg("proceedWithAction")}
            </a>
        <#elseif client?? && client.baseUrl?has_content>
            <a href="${client.baseUrl}" class="of-btn of-btn--primary">
                ${msg("goToOpenFilz")}
            </a>
        </#if>

    </#if>
</@layout.registrationLayout>
