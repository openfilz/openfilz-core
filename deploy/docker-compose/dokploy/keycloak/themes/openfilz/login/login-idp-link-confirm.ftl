<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>

    <#if section = "header">
        ${msg("idpLinkConfirmTitle")}
    <#elseif section = "subtitle">
        ${msg("idpLinkConfirmSubtitle")}

    <#elseif section = "form">
        <form id="kc-register-form" action="${url.loginAction}" method="post">
            <div class="of-form-group of-mb-0">
                <button type="submit" class="of-btn of-btn--primary" name="submitAction" id="linkAccount" value="linkAccount">
                    <svg class="of-btn-icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                        <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/>
                        <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/>
                    </svg>
                    ${msg("idpLinkConfirmLinkAccount")}
                </button>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
