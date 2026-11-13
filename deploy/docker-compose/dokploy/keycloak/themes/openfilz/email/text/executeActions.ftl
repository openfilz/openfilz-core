<#ftl output_format="plainText">
<#-- Plain-text part. {0} link, {2} product name, {3} expiry, {4} required actions
     (html/template.ftl explains brandName). -->
<#assign brandName = (realmName?has_content && realmName?lower_case != "openfilz")?then(realmName, "OpenFilz")>
<#assign requiredActionsText><#if requiredActions??><#list requiredActions><#items as reqActionItem>${msg("requiredAction.${reqActionItem}")}<#sep>, </#items></#list></#if></#assign>
${msg("executeActionsBody", link, linkExpiration, brandName, linkExpirationFormatter(linkExpiration), requiredActionsText)}
