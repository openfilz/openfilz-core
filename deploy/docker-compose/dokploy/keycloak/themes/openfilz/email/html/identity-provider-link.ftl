<#import "template.ftl" as layout>
<@layout.emailLayout icon="&#128279;" title=msg("identityProviderLinkTitle")>
<p style="margin: 0 0 24px 0; font-size: 15px; color: #4b5563; line-height: 1.6;">${msg("identityProviderLinkInstruction", identityProviderAlias)}</p>
<@layout.button url=link text=msg("identityProviderLinkButtonText")/>
<div style="background-color: #f8fafc; border-radius: 12px; padding: 16px; margin-bottom: 20px;">
<p style="margin: 0; font-size: 14px; color: #6b7280; line-height: 1.6;">&#9200; ${msg("identityProviderLinkExpiry", linkExpirationFormatter(linkExpiration))}</p>
</div>
<p style="margin: 0; font-size: 14px; color: #9ca3af; line-height: 1.6;">${msg("identityProviderLinkIgnore")}</p>
</@layout.emailLayout>
