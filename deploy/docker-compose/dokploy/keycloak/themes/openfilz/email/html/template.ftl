<#macro emailLayout icon title>
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
</head>
<body style="margin: 0; padding: 0; background-color: #f4f6f9; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color: #f4f6f9;">
<tr>
<td style="padding: 40px 20px;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 16px; box-shadow: 0 4px 24px rgba(0, 0, 0, 0.08); overflow: hidden;">
<!-- Header with gradient -->
<tr>
<td style="background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 40px 40px 30px 40px; text-align: center;">
<table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin: 0 auto 20px auto;">
<tr>
<td align="center" valign="middle" width="80" height="80" style="width: 80px; height: 80px; background-color: rgba(255,255,255,0.8); border-radius: 50%; text-align: center; vertical-align: middle;">
<img src="https://openfilz.com/assets/img/logo-email-header.png" alt="OpenFilz" width="64" height="49" style="display:block;border:0;margin:0 auto;" />
</td>
</tr>
</table>
<h1 style="margin: 0; font-size: 24px; font-weight: 600; color: #ffffff; letter-spacing: -0.5px;">${title}</h1>
</td>
</tr>
<!-- Body content -->
<tr>
<td style="padding: 40px;">
<#nested>
</td>
</tr>
<!-- Footer -->
<tr>
<td style="padding: 30px 40px; background-color: #f8fafc; border-top: 1px solid #e5e7eb; text-align: center;">
<p style="margin: 0 0 10px 0; font-size: 13px; color: #9ca3af;">${msg("emailFooterSentVia")}</p>
<a href="https://openfilz.com" style="text-decoration: none;">
<table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin: 0 auto;">
<tr>
<td valign="middle" style="padding-right: 6px;">
<img src="https://openfilz.com/assets/img/logo-email-footer.png" alt="OpenFilz" width="37" height="28" style="display:block;border:0;" />
</td>
<td valign="middle">
<span style="font-size: 15px; font-weight: 700; color: #667eea; letter-spacing: 0.08em;">OPENFILZ</span>
</td>
</tr>
</table>
</a>
</td>
</tr>
</table>
</td>
</tr>
</table>
</body>
</html>
</#macro>

<#macro button url text>
<div style="text-align: center; margin: 30px 0;">
<a href="${url}" style="display: inline-block; padding: 14px 36px; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: #ffffff; font-size: 15px; font-weight: 600; text-decoration: none; border-radius: 8px; box-shadow: 0 4px 14px rgba(102, 126, 234, 0.4);">${text}</a>
</div>
</#macro>
