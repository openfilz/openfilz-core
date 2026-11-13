<#ftl output_format="plainText">
<#-- Plain-text part. Same arguments as Keycloak's base template, the product name in place of realmName
     (html/template.ftl explains brandName). -->
<#assign brandName = (realmName?has_content && realmName?lower_case != "openfilz")?then(realmName, "OpenFilz")>
${msg("emailVerificationBody", link, linkExpiration, brandName, linkExpirationFormatter(linkExpiration))}
