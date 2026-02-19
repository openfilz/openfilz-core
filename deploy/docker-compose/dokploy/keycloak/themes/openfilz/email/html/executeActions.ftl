<#outputformat "plainText">
<#assign requiredActionsText><#if requiredActions??><#list requiredActions as reqActionItem>${msg("requiredAction.${reqActionItem}")}<#sep>, </#list><#else></#if></#assign>
</#outputformat>
<#import "template.ftl" as layout>
<@layout.emailLayout icon="&#9881;" title=msg("executeActionsTitle")>
<p style="margin: 0 0 16px 0; font-size: 15px; color: #4b5563; line-height: 1.6;">${msg("executeActionsInstruction")}</p>
<div style="background-color: #f8fafc; border-radius: 12px; padding: 20px; margin-bottom: 24px; border: 1px solid #e5e7eb;">
<p style="margin: 0; font-size: 15px; font-weight: 600; color: #1a1a2e;">${requiredActionsText}</p>
</div>
<@layout.button url=link text=msg("executeActionsButtonText")/>
<div style="background-color: #f8fafc; border-radius: 12px; padding: 16px; margin-bottom: 20px;">
<p style="margin: 0; font-size: 14px; color: #6b7280; line-height: 1.6;">&#9200; ${msg("executeActionsExpiry", linkExpirationFormatter(linkExpiration))}</p>
</div>
<p style="margin: 0; font-size: 14px; color: #9ca3af; line-height: 1.6;">${msg("executeActionsIgnore")}</p>
</@layout.emailLayout>
