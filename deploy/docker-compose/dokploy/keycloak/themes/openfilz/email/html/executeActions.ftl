<#import "template.ftl" as layout>
<@layout.emailLayout icon="&#128075;" title=msg("executeActionsTitle")>
<p style="margin: 0 0 16px 0; font-size: 15px; color: #4b5563; line-height: 1.6;">${msg("executeActionsInstruction")}</p>
<p style="margin: 0 0 24px 0; font-size: 15px; color: #4b5563; line-height: 1.6;">${msg("executeActionsDetail")}</p>
<@layout.button url=link text=msg("executeActionsButtonText")/>
<div style="background-color: #f8fafc; border-radius: 12px; padding: 16px; margin-bottom: 20px;">
<p style="margin: 0; font-size: 14px; color: #6b7280; line-height: 1.6;">&#9200; ${msg("executeActionsExpiry", linkExpirationFormatter(linkExpiration))}</p>
</div>
<p style="margin: 0; font-size: 14px; color: #9ca3af; line-height: 1.6;">${msg("executeActionsIgnore")}</p>
</@layout.emailLayout>
