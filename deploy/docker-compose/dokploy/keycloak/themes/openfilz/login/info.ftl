<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>

    <#if section = "header">
        ${msg("infoTitle")}
    <#elseif section = "subtitle">
        ${msg("infoSubtitle")}

    <#elseif section = "form">

        <#-- Resolve login hint from user email or username -->
        <#assign loginHint = '' />
        <#if user?? && user.username?has_content>
            <#assign loginHint = user.username />
        </#if>

        <#--
            After completing required actions (email verification, profile update, password setup)
            from an executeActionsEmail flow, Keycloak shows this info page WITHOUT creating an
            SSO session. Instead of showing a button that redirects to the app (which would then
            redirect back to the login page), auto-redirect to the OIDC authorization endpoint.
            This takes the user directly to the login page with their email pre-filled via login_hint.
        -->
        <#if pageRedirectUri?has_content && client?? && client.clientId?has_content>
            <script>
                (function() {
                    try {
                        // Extract realm base URL from current page URL
                        // e.g. https://auth.example.com/realms/openfilz/login-actions/... → https://auth.example.com/realms/openfilz
                        var match = window.location.href.match(/(https?:\/\/[^\/]+\/realms\/[^\/]+)\//);
                        if (match) {
                            var oidcAuthUrl = match[1] + '/protocol/openid-connect/auth';
                            var params = new URLSearchParams();
                            params.set('client_id', '${client.clientId?js_string}');
                            params.set('redirect_uri', '${pageRedirectUri?js_string}');
                            params.set('response_type', 'code');
                            params.set('scope', 'openid');
                            <#if loginHint?has_content>
                            params.set('login_hint', '${loginHint?js_string}');
                            </#if>
                            window.location.replace(oidcAuthUrl + '?' + params.toString());
                            return;
                        }
                    } catch (e) {
                        // Fallback to plain redirect on error
                    }
                    window.location.replace('${pageRedirectUri?js_string}');
                })();
            </script>
            <noscript>
                <a href="${pageRedirectUri}" class="of-btn of-btn--primary">
                    ${msg("goToOpenFilz")}
                </a>
            </noscript>
        <#else>

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

        <#-- Redirect to application (non-executeActionsEmail flows) -->
        <#if actionUri?has_content>
            <a href="${actionUri}" class="of-btn of-btn--primary">
                ${msg("proceedWithAction")}
            </a>
        <#elseif client?? && client.baseUrl?has_content>
            <a href="${client.baseUrl}" class="of-btn of-btn--primary">
                ${msg("goToOpenFilz")}
            </a>
        </#if>

        </#if>

    </#if>
</@layout.registrationLayout>
