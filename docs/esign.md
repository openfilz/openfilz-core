# e-Sign — electronic signatures (Community Edition)

OpenFilz lets you send any PDF stored in the DMS to one or more people for electronic
signature, collect typed fields along the way, and store the sealed result back in the DMS
next to the original — versions, permissions, search and audit included.

Everything described here is part of the open-source core. The Enterprise Edition adds a
trust tier (PDF/A-2b + AATL seal + timestamp + veraPDF), automatic reminders, bulk send,
SMS one-time codes, embedded signing and in-app notifications — see
`openfilz-enterprise/docs/esign-ce-ee-split.md`.

## Enabling the feature

```yaml
openfilz:
  signature:
    active: true                 # OPENFILZ_SIGNATURE_ACTIVE — menus, endpoints, sweeper
  common:
    web-public-base-url: https://app.example.com/   # where the signing links point
spring:
  mail:
    host: smtp.example.com       # SMTP_HOST — without it links are only logged
    port: 587
    username: …
    password: …
```

`openfilz.signature.active` is read at runtime: the same image serves deployments with the
feature on or off. When off, `/api/v1/signatures/**`, `/api/v1/signature-templates/**` and the
public `/api/v1/public/signatures/**` answer `404`, and `GET /api/v1/settings` reports
`signatureActive: false` so the web app hides the menu.

## Concepts

| Term | Meaning |
|---|---|
| **Envelope** | One source PDF sent to N recipients. Status `DRAFT → SENT → COMPLETED \| DECLINED \| CANCELLED \| EXPIRED`. |
| **Recipient** | A `SIGNER` (must act) or `CC` (receives the final document). Internal users and external e-mail addresses alike. Optional `EMAIL_OTP` access code. |
| **Field** | A typed box placed for one recipient: `SIGNATURE`, `INITIALS`, `DATE_SIGNED` (auto), `TEXT`, `NUMBER`, `EMAIL`, `PHONE`, `CHECKBOX`, `RADIO`, `SELECT`, `IMAGE`, `STAMP`. Coordinates are normalised (0..1, PDF origin bottom-left). |
| **Sequential signing** | Recipients with a lower `orderIndex` sign first; the next group is invited automatically. |
| **Template** | Reusable roles + fields (and optionally a default document). Instantiate it by binding each role to a person. |
| **Certificate of Completion** | A page appended to the signed PDF: signers, timestamps, IP, OTP method, full audit trail, SHA-256 of the original. |
| **Seal** | A PAdES signature applied over the whole document once everyone signed. |

## Signing-link security

Every recipient gets a link `{web-public-base-url}sign?token=<32 random bytes>`. Only the
SHA-256 of the token is stored; the raw token exists in the e-mail only. Resending a link
(`POST /signatures/{id}/recipients/{rid}/resend`) mints a new token and revokes the old one.
Recipients with `authMethod: EMAIL_OTP` must additionally enter a 6-digit code (10 minutes,
5 attempts) before signing. Every action is recorded in the envelope's event trail and in the
tamper-evident audit log, attributed to the real actor (signer e-mail from the validated
token row; initiator e-mail from the JWT at send time) — never to `anonymousUser`.

## Seal providers

| `openfilz.signature.seal.provider` | Identity | Use when |
|---|---|---|
| `self-signed-dev` (default) | ephemeral self-signed certificate generated at startup | evaluation, development |
| `pkcs12` | your own certificate (`seal.keystore-path/-password/-alias`), e.g. an AATL cert you purchased | production, bring-your-own-certificate |
| `openfilz-cloud` | OpenFilz's AATL certificate via `sign.openfilz.com` (`seal.cloud.api-key`) | production, green "Valid" in Acrobat without buying a certificate. Only a hash leaves your server. |

The `openfilz-cloud` provider is an **opt-in** connector — the CE never calls any OpenFilz
service unless you configure it. Keys are issued with a `sign.openfilz.com` account.

## REST API

Initiator (OIDC; `CONTRIBUTOR` to write, `READER` to list):

| Method | Path | |
|---|---|---|
| `POST` | `/api/v1/signatures` | create (+ send unless `send=false`) |
| `POST` | `/api/v1/signatures/{id}/send` | send a draft |
| `GET` | `/api/v1/signatures?status=` | envelopes I sent |
| `GET` | `/api/v1/signatures/to-sign` | waiting for my signature |
| `GET` | `/api/v1/signatures/{id}` · `/{id}/events` | detail, audit trail |
| `POST` | `/api/v1/signatures/{id}/cancel` | |
| `POST` | `/api/v1/signatures/{id}/recipients/{rid}/resend` | new link + reminder |
| `GET` | `/api/v1/signatures/{id}/signed-document` | sealed PDF |
| `POST/GET/PUT/DELETE` | `/api/v1/signature-templates[/{id}]` | templates |
| `POST` | `/api/v1/signature-templates/{id}/envelopes` | instantiate |

Signer (token only): `GET /api/v1/public/signatures?token=`, `POST …/viewed`,
`GET …/document`, `POST …/otp/request`, `POST …/otp/verify`, `POST …/sign`, `POST …/decline`.

Example:

```bash
curl -X POST $API/api/v1/signatures -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{
  "sourceDocId": "6f1e…", "title": "Service agreement", "sequential": true, "expiresInDays": 14,
  "recipients": [
    { "name": "Ada", "email": "ada@example.com", "orderIndex": 0, "authMethod": "EMAIL_OTP",
      "fields": [
        { "type": "SIGNATURE", "page": 2, "x": 0.1, "y": 0.12, "w": 0.3, "h": 0.08 },
        { "type": "DATE_SIGNED", "page": 2, "x": 0.5, "y": 0.12, "w": 0.2, "h": 0.04 },
        { "type": "SELECT", "page": 0, "x": 0.1, "y": 0.5, "w": 0.3, "h": 0.04, "label": "Plan",
          "options": { "choices": ["Basic", "Pro"] } } ] },
    { "name": "Legal", "email": "legal@example.com", "role": "CC" }
  ]
}'
```

## Operations

* `openfilz.signature.sweep.cron` (default every 5 min) flips overdue `SENT` envelopes to `EXPIRED`.
* `openfilz.signature.default-expiry-days` (30), `otp.length/valid-minutes/max-attempts`, `max-image-bytes` (512 KiB).
* E-mails are localised (`en fr de es it nl pt ar`) from the recipient's `locale`, then the envelope's, then English; branding via `openfilz.signature.mail.{from,from-name,product-name,logo-url}`.
* Audit actions: `SIGNATURE_ENVELOPE_CREATED/SENT/COMPLETED/DECLINED/CANCELLED/EXPIRED`, `SIGNATURE_DOCUMENT_SIGNED`, `SIGNATURE_REMINDER_SENT`, `SIGNATURE_TEMPLATE_CREATED/DELETED`.
* The signed copy is a normal document flagged `{"_signed":true,"_readOnly":true,"_signedEnvelopeId":…}` (OnlyOffice opens it read-only).

## Extension points

Editions and integrators can replace any of these beans (`org.openfilz.dms.service.signature`):
`SignatureAccessPolicy` (who may initiate, where the signed copy goes), `SignatureActorResolver`
(audit attribution), `SignatureNotifier`, `SignatureMailer`, `SignatureSealer`,
`SignatureOtpSender` (add SMS), `SignatureCompletionListener`. Mark yours `@Primary`.
