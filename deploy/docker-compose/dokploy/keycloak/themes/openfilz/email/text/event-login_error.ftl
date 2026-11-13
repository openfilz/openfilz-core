<#ftl output_format="plainText">
<#-- Plain-text part. {0} product name, {1} date, {2} address
     (html/template.ftl explains brandName). -->
<#assign brandName = (realmName?has_content && realmName?lower_case != "openfilz")?then(realmName, "OpenFilz")>
${msg("eventLoginErrorBody", brandName, event.date, event.ipAddress)}
