<#import "template.ftl" as layout>
<#import "user-profile-commons.ftl" as userProfileCommons>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('global'); section>

    <#if section = "header">
        ${msg("loginProfileTitle")}
    <#elseif section = "subtitle">
        ${msg("loginProfileSubtitle")}

    <#elseif section = "form">
        <form id="kc-idp-review-profile-form" action="${url.loginAction}" method="post" novalidate>

            <@userProfileCommons.userProfileFormFields/>

            <#-- Submit -->
            <div class="of-form-group of-mb-0">
                <button type="submit" class="of-btn of-btn--primary">
                    ${msg("doSubmit")}
                </button>
            </div>
        </form>

    </#if>
</@layout.registrationLayout>
