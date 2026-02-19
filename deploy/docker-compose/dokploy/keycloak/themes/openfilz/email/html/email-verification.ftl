<#import "template.ftl" as layout>
<@layout.emailLayout icon="&#9993;" title=msg("emailVerificationTitle")>
<p style="margin: 0 0 16px 0; font-size: 16px; font-weight: 600; color: #1a1a2e; line-height: 1.6;">${msg("emailVerificationGreeting")}</p>
<p style="margin: 0 0 24px 0; font-size: 15px; color: #4b5563; line-height: 1.6;">${msg("emailVerificationInstruction")}</p>
<@layout.button url=link text=msg("emailVerificationButtonText")/>
<div style="background-color: #f8fafc; border-radius: 12px; padding: 16px; margin-bottom: 20px;">
<p style="margin: 0; font-size: 14px; color: #6b7280; line-height: 1.6;">&#9200; ${msg("emailVerificationExpiry", linkExpirationFormatter(linkExpiration))}</p>
</div>
<p style="margin: 0; font-size: 14px; color: #9ca3af; line-height: 1.6;">${msg("emailVerificationIgnore")}</p>
</@layout.emailLayout>
