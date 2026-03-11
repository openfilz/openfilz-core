<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('firstName','lastName','email','username'); section>

    <#if section = "header">
        ${msg("loginProfileTitle")}
    <#elseif section = "subtitle">
        ${msg("loginProfileSubtitle")}

    <#elseif section = "form">
        <#-- Resolve email from User Profile attributes (Keycloak 26+) or legacy user bean -->
        <#assign emailValue = '' />

        <#-- Try 1: KC 26+ profile.attributes (List<ProfileBean.Attribute>) -->
        <#if profile?? && profile.attributes??>
            <#list profile.attributes as attribute>
                <#if attribute?? && attribute.name?? && attribute.name == 'email'>
                    <#if attribute.value?? && attribute.value?has_content>
                        <#assign emailValue = attribute.value />
                    <#elseif attribute.values?? && attribute.values?size gt 0 && attribute.values[0]??>
                        <#assign emailValue = attribute.values[0] />
                    </#if>
                    <#break>
                </#if>
            </#list>
        </#if>

        <#-- Try 2: user bean email -->
        <#if emailValue == '' && user??>
            <#assign emailValue = ((user.email)!'') />
        </#if>

        <#-- Try 3: user bean username (often the email for IdP-created users) -->
        <#if emailValue == '' && user??>
            <#assign emailValue = ((user.username)!'') />
        </#if>

        <#-- Resolve firstName and lastName from profile attributes or user bean -->
        <#assign firstNameValue = '' />
        <#assign lastNameValue = '' />
        <#if profile?? && profile.attributes??>
            <#list profile.attributes as attribute>
                <#if attribute?? && attribute.name??>
                    <#if attribute.name == 'firstName'>
                        <#if attribute.value?? && attribute.value?has_content>
                            <#assign firstNameValue = attribute.value />
                        <#elseif attribute.values?? && attribute.values?size gt 0 && attribute.values[0]??>
                            <#assign firstNameValue = attribute.values[0] />
                        </#if>
                    <#elseif attribute.name == 'lastName'>
                        <#if attribute.value?? && attribute.value?has_content>
                            <#assign lastNameValue = attribute.value />
                        <#elseif attribute.values?? && attribute.values?size gt 0 && attribute.values[0]??>
                            <#assign lastNameValue = attribute.values[0] />
                        </#if>
                    </#if>
                </#if>
            </#list>
        </#if>
        <#if firstNameValue == '' && user??>
            <#assign firstNameValue = ((user.firstName)!'') />
        </#if>
        <#if lastNameValue == '' && user??>
            <#assign lastNameValue = ((user.lastName)!'') />
        </#if>

        <form id="kc-update-profile-form" action="${url.loginAction}" method="post" novalidate>

            <#-- Email (read-only, shown only when pre-filled by the identity provider) -->
            <#if emailValue?has_content>
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
                            value="${emailValue}"
                            readonly
                            tabindex="-1"
                        />
                    </div>
                    <span class="of-field-hint">${msg("loginProfileEmailReadonly")}</span>
                </div>
            </#if>

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
                        value="${firstNameValue}"
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
                        value="${lastNameValue}"
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
