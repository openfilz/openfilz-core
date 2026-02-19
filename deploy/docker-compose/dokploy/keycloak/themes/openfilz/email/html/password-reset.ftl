<#import "template.ftl" as layout>
<@layout.emailLayout icon="&#128274;" title=msg("passwordResetTitle")>
<p style="margin: 0 0 16px 0; font-size: 16px; font-weight: 600; color: #1a1a2e; line-height: 1.6;">${msg("passwordResetGreeting")}</p>
<p style="margin: 0 0 24px 0; font-size: 15px; color: #4b5563; line-height: 1.6;">${msg("passwordResetInstruction")}</p>
<@layout.button url=link text=msg("passwordResetButtonText")/>
<div style="background-color: #f8fafc; border-radius: 12px; padding: 16px; margin-bottom: 20px;">
<p style="margin: 0; font-size: 14px; color: #6b7280; line-height: 1.6;">&#9200; ${msg("passwordResetExpiry", linkExpirationFormatter(linkExpiration))}</p>
</div>
<p style="margin: 0; font-size: 14px; color: #9ca3af; line-height: 1.6;">${msg("passwordResetIgnore")}</p>
</@layout.emailLayout>
