<#ftl output_format="plainText">
<#-- Plain-text part. {0} provider, {1} product name, {3} link, {4} expiry
     (html/template.ftl explains brandName). -->
<#assign brandName = (realmName?has_content && realmName?lower_case != "openfilz")?then(realmName, "OpenFilz")>
${msg("identityProviderLinkBody", identityProviderDisplayName!identityProviderAlias, brandName, identityProviderContext.username, link, linkExpirationFormatter(linkExpiration))}
