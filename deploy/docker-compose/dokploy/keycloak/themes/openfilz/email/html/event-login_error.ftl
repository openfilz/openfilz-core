<#import "template.ftl" as layout>
<@layout.emailLayout icon="&#9888;" title=msg("eventLoginErrorTitle")>
<div style="background-color: #fef2f2; border-radius: 12px; padding: 20px; margin-bottom: 24px; border: 1px solid #fecaca;">
<p style="margin: 0; font-size: 15px; color: #991b1b; line-height: 1.6;">${msg("eventLoginErrorInstruction")}</p>
</div>
<p style="margin: 0; font-size: 15px; color: #4b5563; line-height: 1.6; font-weight: 600;">${msg("eventLoginErrorAction")}</p>
</@layout.emailLayout>
