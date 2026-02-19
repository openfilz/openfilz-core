<#macro registrationLayout bodyClass="" displayInfo=false displayMessage=true displayRequiredFields=false showAnotherWayIfPresent=true>
<!DOCTYPE html>
<html lang="${(locale.currentLanguageTag)!'en'}">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="robots" content="noindex, nofollow">
    <title>${msg("loginTitle", (realm.displayName!''))}</title>

    <#if properties.meta?has_content>
        <#list properties.meta?split(' ') as meta>
            <meta name="${meta?keep_before('=')}" content="${meta?keep_after('=')}"/>
        </#list>
    </#if>

    <#if properties.styles?has_content>
        <#list properties.styles?split(' ') as style>
            <link rel="stylesheet" href="${url.resourcesPath}/${style}"/>
        </#list>
    </#if>

    <#if properties.scripts?has_content>
        <#list properties.scripts?split(' ') as script>
            <script src="${url.resourcesPath}/${script}"></script>
        </#list>
    </#if>
</head>
<body>
    <div class="of-page" role="main">
        <div class="of-card" role="region" aria-label="${msg("loginTitle", (realm.displayName!''))}">

            <#-- Locale Switcher -->
            <#if realm.internationalizationEnabled && locale?? && locale.supported?? && (locale.supported?size gt 1)>
                <div class="of-locale-wrapper">
                    <button
                        type="button"
                        id="of-locale-toggle"
                        class="of-locale-toggle"
                        aria-expanded="false"
                        aria-haspopup="true"
                        aria-label="${msg("locale_label")}"
                    >
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                            <circle cx="12" cy="12" r="10"/>
                            <line x1="2" y1="12" x2="22" y2="12"/>
                            <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/>
                        </svg>
                        <span>${locale.current}</span>
                        <svg class="of-locale-chevron" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                            <polyline points="6 9 12 15 18 9"/>
                        </svg>
                    </button>
                    <div id="of-locale-dropdown" class="of-locale-dropdown" role="menu" aria-labelledby="of-locale-toggle">
                        <#list locale.supported as l>
                            <a
                                href="${l.url}"
                                role="menuitem"
                                <#if l.languageTag == (locale.currentLanguageTag)!''>class="active" aria-current="true"</#if>
                            >${l.label}</a>
                        </#list>
                    </div>
                </div>
            </#if>

            <#-- Logo -->
            <div class="of-logo-wrapper" role="img" aria-label="${msg("logoAlt")}">
                <svg class="of-logo" viewBox="0 0 200 48" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <rect x="2" y="6" width="36" height="36" rx="8" fill="currentColor" opacity="0.15"/>
                    <rect x="6" y="10" width="28" height="28" rx="6" fill="var(--of-brand-primary)"/>
                    <path d="M14 20h16M14 24h12M14 28h8" stroke="#fff" stroke-width="2" stroke-linecap="round"/>
                    <text x="48" y="33" font-family="system-ui, -apple-system, sans-serif" font-size="22" font-weight="700" fill="currentColor">OpenFilz</text>
                </svg>
            </div>

            <#-- Page Header -->
            <h1 class="of-card-title" id="of-page-title">
                <#nested "header">
            </h1>
            <p class="of-card-subtitle" id="of-page-subtitle">
                <#nested "subtitle">
            </p>

            <#-- Alert Messages -->
            <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
                <div class="of-alert of-alert--${message.type}" role="alert" aria-live="polite">
                    <svg class="of-alert-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                        <#if message.type == 'error'>
                            <circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/>
                        <#elseif message.type == 'success'>
                            <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/>
                        <#elseif message.type == 'warning'>
                            <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>
                        <#else>
                            <circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/>
                        </#if>
                    </svg>
                    <span>${kcSanitize(message.summary)?no_esc}</span>
                </div>
            </#if>

            <#-- Social Providers (above the form) -->
            <#nested "socialProviders">

            <#-- Main Form Content -->
            <#nested "form">

            <#-- Info Section -->
            <#if displayInfo>
                <div class="of-mt-md">
                    <#nested "info">
                </div>
            </#if>

        </div>
    </div>
</body>
</html>
</#macro>
