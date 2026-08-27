# e-Sign — electronic signatures (Community Edition)

OpenFilz lets you send any PDF stored in the DMS to one or more people for electronic
signature, collect typed and drawn fields along the way, and store the sealed result back in
the DMS next to the original — versions, permissions, search, thumbnails and audit included.

Everything on this page is part of the open-source core (`openfilz-api`, AGPL-3.0) and works
offline: no call to any `*.openfilz.com` service is made unless you explicitly configure the
`openfilz-cloud` seal provider. What the Enterprise Edition adds is listed in
[§9](#9-what-the-enterprise-edition-adds); the architecture decision behind the split is
`openfilz-enterprise/docs/esign-ce-ee-split.md`.

---

## 1. What it does

| Concept | Behaviour |
|---|---|
| **Envelope** | One source PDF sent to N recipients. `DRAFT → SENT → COMPLETED \| DECLINED \| CANCELLED \| EXPIRED`. The source must be an active PDF (`contentType` contains `pdf` or the name ends in `.pdf`), otherwise the create call answers `422`. |
| **Recipient** | A `SIGNER` (must act) or a `CC` (only receives the completed document). Internal users and external e-mail addresses alike; e-mails are lower-cased and must be unique inside an envelope. |
| **Fields** | Any number of typed boxes per recipient. Coordinates are normalised to the page media box (`0..1`, PDF origin bottom-left), `page` is 0-based, and placement is validated against the real page count at create time. |
| **Field types (12)** | `SIGNATURE`, `INITIALS`, `DATE_SIGNED`, `TEXT`, `NUMBER`, `EMAIL`, `PHONE`, `CHECKBOX`, `RADIO`, `SELECT`, `IMAGE`, `STAMP`. `SIGNATURE`/`INITIALS`/`IMAGE`/`STAMP` carry a base64 PNG in `valueImage`; everything else stores text in `value` (checkbox `"true"`/`"false"`, dates ISO-8601, radio/select the chosen option). `DATE_SIGNED` is filled by the server and never asked of the signer. `RADIO` and `SELECT` require `options.choices`. |
| **Sequential / parallel** | With `sequential: true`, only recipients whose `orderIndex` equals the envelope's `currentOrder` may act; the next group is invited automatically (with fresh tokens) when the current one finishes. With `sequential: false` (default) everyone is invited at once. Recipients sharing an `orderIndex` always sign in parallel. |
| **Drafts** | `send: false` on create stores the envelope as `DRAFT` and sends nothing. `POST /signatures/{id}/send` re-issues every token and mails the invitations. |
| **Templates** | Reusable named roles + fields (stored as JSON) with an optional default document. Instantiating binds each role to a real person. |
| **Email OTP** | Opt-in per recipient (`authMethod: EMAIL_OTP`). The signer must request and verify a numeric code before `/sign` is accepted. |
| **Resend** | `POST /signatures/{id}/recipients/{rid}/resend` mints a new token — the previous link stops working immediately — and re-mails it. It counts as a reminder (`reminderCount++`, `RECIPIENT_REMINDED` event, `SIGNATURE_REMINDER_SENT` audit action). |
| **Expiry sweeper** | A scheduled job flips `SENT` envelopes past `expiresAt` to `EXPIRED`. The public endpoints reject expired envelopes with `410` regardless, so the sweeper is there to make the *status column* converge for listings, metrics, audit and webhooks. |
| **Certificate of Completion** | A page appended to the signed PDF listing every signer with timestamps, IP and OTP method, the SHA-256 of the original document, and the full append-only event trail. |
| **Seal** | Once every SIGNER has signed, the stamped PDF gets a PAdES signature over the whole document (see [§4](#4-seal-providers)). |
| **The signed copy** | A normal DMS document named `<title> (signed).pdf`, created next to the source, with metadata `{"_signed":true,"_readOnly":true,"_signedEnvelopeId":"<uuid>"}` — OnlyOffice opens it read-only. It is also mailed as an attachment to the initiator and to every recipient (signers **and** CCs), de-duplicated by address. |
| **Audit** | Nine `SIGNATURE_*` actions land in the tamper-evident audit log, always attributed to a real identity (see [§5](#5-security-model)). |

---

## 2. Quick start

The minimum for a working signing flow:

```yaml
openfilz:
  signature:
    active: true                                    # OPENFILZ_SIGNATURE_ACTIVE
  common:
    web-public-base-url: https://app.example.com/   # OPENFILZ_WEB_PUBLIC_BASE_URL
spring:
  mail:
    host: smtp.example.com                          # SMTP_HOST
    port: 587                                       # SMTP_PORT
    username: apikey                                # SMTP_USER
    password: ${SMTP_PASSWORD}
```

* **`openfilz.signature.active`** is the master switch and defaults to `false`. It is read at
  runtime (never as a bean condition), so a single GraalVM native image serves deployments
  with the feature on or off. When off, `/api/v1/signatures/**`,
  `/api/v1/signature-templates/**` and `/api/v1/public/signatures/**` answer `404`, the
  public security chain matches an unreachable sentinel path, the sweeper idles, and
  `GET /api/v1/settings` reports `signatureActive: false` so the web app hides the menu.
  Flipping it requires a restart (the security chain reads it once at startup).
* **`web-public-base-url`** is where the signing links point. Signer links are built as
  `{base}sign?token=<token>`; a trailing `/` is added if missing. Set
  `openfilz.signature.web-base-url` instead if e-Sign links must go somewhere different from
  the rest of the app. If both are empty the link falls back to `http://localhost:4200/`.
* **Without SMTP** (`spring.mail.host` empty — the shipped default) the context wires
  `LoggingSignatureMailer` instead of `SmtpSignatureMailer` and logs a warning at startup:

  ```
  [e-sign] spring.mail.host is not set — signing links will only be LOGGED, not emailed
  ```

  Invitations, reminders, OTP codes and completion notices are then written to the
  application log (`[e-sign][no-smtp] …`, including the raw signing link and the OTP code in
  clear) and nothing is sent. That is enough to try the whole ceremony locally; it is not
  suitable for anything with real signers.

Send a document for signature, watch the log or your inbox for the link, open it in the web
app, fill the fields, sign. When the last SIGNER is done, the sealed PDF appears in the DMS
and is mailed to everyone.

---

## 3. Configuration reference

Env-var names below are the ones wired in `openfilz-api/src/main/resources/application.yml`.
Rows marked † have no placeholder in the shipped YAML; they are reachable only through Spring
Boot relaxed binding, using the canonical uppercase form of the property name.

### 3.1 Feature

| Property | Env var | Default | What it does |
|---|---|---|---|
| `openfilz.signature.active` | `OPENFILZ_SIGNATURE_ACTIVE` | `false` | Master switch: endpoints, public chain, sweeper, `Settings.signatureActive`. |
| `openfilz.signature.default-expiry-days` | `OPENFILZ_SIGNATURE_DEFAULT_EXPIRY_DAYS` | `30` | Envelope TTL when the create request omits `expiresInDays` (which itself accepts 1..365). |
| `openfilz.signature.web-base-url` | `OPENFILZ_SIGNATURE_WEB_BASE_URL` | *(empty)* | Overrides `openfilz.common.web-public-base-url` for signing links only. |
| `openfilz.common.web-public-base-url` | `OPENFILZ_WEB_PUBLIC_BASE_URL` | `http://localhost:4200/` | Public URL of the web app; base of every signing link. |
| `openfilz.signature.max-image-bytes` † | `OPENFILZ_SIGNATURE_MAX_IMAGE_BYTES` | `524288` (512 KiB) | Cap on a submitted base64 field image. Enforced on the *encoded* string as `max-image-bytes × 4/3 + 64`, so it is a decoded-size budget. |
| `openfilz.signature.sweep.cron` | `OPENFILZ_SIGNATURE_SWEEP_CRON` | `0 */5 * * * ?` | Expiry sweeper cadence (Spring 6-field cron). Bound through the `@Scheduled` placeholder, not `SignatureProperties`. |
| `openfilz.signature.quota.envelopes-per-month` | `OPENFILZ_SIGNATURE_QUOTA_ENVELOPES_PER_MONTH` | `0` (unlimited) | Fair-use ceiling: envelopes **one initiator** may create per calendar month. Beyond it, `POST /signatures` answers `429`. Drafts count — they can be sent later, so excluding them would make the limit meaningless. Intended for deployments that expose e-Sign to people who have not paid for it (a public demo, a trial tenant); leave it at `0` on a normal self-hosted instance, where the query is then never run. |

### 3.2 Recipient OTP

Applies to `EMAIL_OTP` (CE) and `SMS_OTP` (EE).

| Property | Env var † | Default | What it does |
|---|---|---|---|
| `openfilz.signature.otp.length` | `OPENFILZ_SIGNATURE_OTP_LENGTH` | `6` | Number of digits in the generated code. |
| `openfilz.signature.otp.valid-minutes` | `OPENFILZ_SIGNATURE_OTP_VALID_MINUTES` | `10` | Validity window; after that a new code must be requested. |
| `openfilz.signature.otp.max-attempts` | `OPENFILZ_SIGNATURE_OTP_MAX_ATTEMPTS` | `5` | Failed verifications before the code is locked out (`429`) and must be re-requested. |

The three OTP keys *are* present in the shipped YAML but as literal values, so overriding them
goes through relaxed binding.

### 3.3 Seal

| Property | Env var | Default | What it does |
|---|---|---|---|
| `openfilz.signature.seal.provider` | `OPENFILZ_SIGNATURE_SEAL_PROVIDER` | `self-signed-dev` | `self-signed-dev` \| `pkcs12` \| `openfilz-cloud`. Only `openfilz-cloud` is matched explicitly; any other value selects the in-process sealer. |
| `openfilz.signature.seal.keystore-path` | `OPENFILZ_SIGNATURE_SEAL_KEYSTORE_PATH` | *(empty)* | PKCS#12 keystore holding the seal key + certificate. **This is what actually selects `pkcs12`** — see the note below. |
| `openfilz.signature.seal.keystore-password` | `OPENFILZ_SIGNATURE_SEAL_KEYSTORE_PASSWORD` | *(empty)* | Used for both the store and the key entry — they must be identical. |
| `openfilz.signature.seal.keystore-alias` | `OPENFILZ_SIGNATURE_SEAL_KEYSTORE_ALIAS` | `openfilz-seal` | Alias of the key entry inside the keystore. |
| `openfilz.signature.seal.name` † | `OPENFILZ_SIGNATURE_SEAL_NAME` | `OpenFilz e-Sign Seal` | Written into the PDF signature dictionary (`/Name`). The `/Reason` always reads `Document completed via OpenFilz e-Sign — envelope <id>`. |
| `openfilz.signature.seal.cloud.url` | `OPENFILZ_SIGNATURE_CLOUD_URL` | `https://sign.openfilz.com` | Base URL of the cloud signing service. |
| `openfilz.signature.seal.cloud.api-key` | `OPENFILZ_SIGNATURE_CLOUD_API_KEY` | *(empty)* | Tenant API key; sealing fails fast with a clear error when the `openfilz-cloud` provider is selected and this is blank. |
| `openfilz.signature.seal.cloud.timeout` † | `OPENFILZ_SIGNATURE_SEAL_CLOUD_TIMEOUT` | `15s` | Per-call timeout for `/api/v1/cert` and `/api/v1/sign-hash`. |

> **`keystore-path` wins over `provider` for the in-process sealer.** `SignatureConfig` uses
> `provider` only to decide between the cloud sealer and the in-process one; the in-process
> sealer then reports itself as `pkcs12` if `keystore-path` is set and `self-signed-dev`
> otherwise. So `provider: pkcs12` with no keystore silently produces an ephemeral dev seal,
> and `provider: self-signed-dev` with a keystore path loads the keystore. Trust the startup
> log line, not the property:
>
> ```
> [e-sign] PAdES seal loaded from keystore alias 'openfilz-seal' (CN=…)
> [e-sign] No openfilz.signature.seal.keystore-path configured — using an EPHEMERAL self-signed seal certificate …
> ```
>
> A keystore that fails to load (bad path, password or alias) is **not** fatal: it is logged
> as an error and the ephemeral seal takes over. Check the log after every deployment.

### 3.4 Mail

| Property | Env var | Default | What it does |
|---|---|---|---|
| `openfilz.signature.mail.from` | `OPENFILZ_SIGNATURE_MAIL_FROM` | `no-reply@openfilz.com` | `From` address of every e-Sign e-mail. |
| `openfilz.signature.mail.from-name` | `OPENFILZ_SIGNATURE_MAIL_FROM_NAME` | `OpenFilz e-Sign` | Display name in the `From` header. |
| `openfilz.signature.mail.product-name` | `OPENFILZ_SIGNATURE_PRODUCT_NAME` | `OpenFilz` | Product name used in subjects and bodies (white-labelling). |
| `openfilz.signature.mail.logo-url` | `OPENFILZ_SIGNATURE_LOGO_URL` | *(empty)* | Optional logo rendered at the top of the HTML e-mails. |
| `spring.mail.host` | `SMTP_HOST` | *(empty)* | **Empty ⇒ no mail is sent at all** (`LoggingSignatureMailer`). |
| `spring.mail.port` | `SMTP_PORT` | `587` | |
| `spring.mail.username` | `SMTP_USER` | *(empty)* | |
| `spring.mail.password` | `SMTP_PASSWORD` | *(empty)* | |
| `spring.mail.properties.mail.smtp.auth` | `SMTP_AUTH` | `true` | |
| `spring.mail.properties.mail.smtp.starttls.enable` | `SMTP_STARTTLS` | `true` | |

---

## 4. Seal providers

The seal is a **detached CMS/PKCS#7 PAdES signature (SHA-256 with RSA)** applied with PDFBox +
Bouncy Castle as an incremental save, over the stamped document (visible marks and Certificate
of Completion already rendered). In the Community Edition every provider produces
**PAdES-B-B**: no PDF/A conversion, no embedded RFC-3161 timestamp and no LTV material. The
PDF/A-2b + timestamped path is Enterprise (`archiving-api`).

| Provider | Identity in the PDF | What a recipient sees in Acrobat | Pick it when |
|---|---|---|---|
| `self-signed-dev` *(default)* | Self-signed RSA-2048 certificate, `CN=OpenFilz e-Sign Seal (dev), O=OpenFilz`, 10-year validity, **generated in memory at every startup** | *"Signature validity is unknown"* — the certificate is not chained to any trusted root. Recipients can make it green only by importing that exact certificate as a trusted root, and it changes on every restart, so yesterday's PDFs and today's do not even share an anchor. | Evaluation and development. Never for real signers. |
| `pkcs12` | Your own certificate and chain from a `.p12` on disk | Valid and green wherever the issuing CA is trusted: everywhere for an AATL document-signing cert (GlobalSign, SSL.com, DigiCert), inside your organisation for a corporate CA. | Production, bring-your-own-certificate. Include the intermediates in the `.p12` (`openssl pkcs12 -export -certfile chain.crt`) so Acrobat can build a path without network lookups. |
| `openfilz-cloud` | OpenFilz's AATL certificate, held by `sign.openfilz.com` | Valid and green on every machine out of the box; the signer line reads *"Signed by OpenFilz"*. Per-human attribution stays inside the document (visible stamps + Certificate of Completion). | Production without buying and operating a certificate. Requires a `sign.openfilz.com` account and an API key. |

**How `openfilz-cloud` works.** The CMS container is built locally; only a SHA-256 digest of
the CMS signed attributes is sent to `POST /api/v1/sign-hash`, and the raw RSA signature comes
back. **Document content never leaves your deployment.** The leaf certificate and chain are
fetched once from `GET /api/v1/cert` and cached for the JVM lifetime — a certificate rotation
on the OpenFilz side is picked up on your next restart. This is an **opt-in connector**: with
the default provider the CE never contacts any OpenFilz service.

A production `.p12` is generated and mounted exactly like any other secret. The short version:

```bash
# 10-year RSA-4096 self-signed seal (replace with your CA-issued cert for production)
keytool -genkeypair -alias openfilz-seal -keyalg RSA -keysize 4096 -validity 3650         -dname "CN=Acme Document Seal, O=Acme, C=FR"         -storetype PKCS12 -keystore seal.p12 -storepass "$SEAL_PASSWORD" -keypass "$SEAL_PASSWORD"
```

The store and key passwords **must be identical** (the loader uses one password for both).
Mount the file read-only, keep it out of the image, and rotate it like any other key: a new
keystore takes effect at the next restart and only affects documents sealed after it —
previously sealed PDFs keep verifying against the old certificate.

---

## 5. Security model

**Signing links.** Each recipient gets `{web-base-url}sign?token=<token>`, where the token is
32 bytes from `SecureRandom`, base64url without padding. Only `sha256(token)` is stored on the
recipient row — the raw token exists in the e-mail and nowhere else. The lookup is
`findByTokenHash(sha256(raw))` filtered on `!tokenRevoked`.

**Revocation.** Sending a draft, resending a link, advancing a sequential envelope to the next
group, sending a scheduled reminder and minting an embedded-signing URL (EE) all **overwrite
the stored hash with a fresh one**, which is what makes the previous link stop resolving.
(The `token_revoked` column exists and is honoured by the read path, but nothing in the current
code sets it to `true` — revocation in practice is the hash rotation.) Resend, next-group
advance and scheduled reminders also clear `otpVerifiedAt`, so the OTP step must be passed
again on the new link; the EE `embed-url` rotation does not.

**OTP.** For `EMAIL_OTP`, `POST /otp/request` generates an all-digit code, stores only its
SHA-256, and mails it. `POST /otp/verify` compares in constant time
(`MessageDigest.isEqual`), increments `otpAttempts` on failure, and refuses beyond
`max-attempts` (`429`) or past `otpExpiresAt` (`410`). On success `otpHash` is cleared and
`otpVerifiedAt` is set. `/sign` answers `403` until then. An `authMethod` that this deployment
cannot deliver is refused **at envelope creation** (`422`) rather than stranding a signer who
could never pass the step.

**Public chain.** `/api/v1/public/signatures/**` gets its own `@Order(-3)`
`SecurityWebFilterChain` (`SignaturePublicSecurityConfig`) with CSRF disabled and
`permitAll()` — the token in the query string is the authenticator. When the feature is off
the chain's matcher points at `/_disabled-public-signatures-chain`, so it stays in the context
(native-image safe) while matching nothing.

**Roles.** `AbstractSecurityService.isSignatureAuthorized` maps `GET` on `/signatures/**` and
`/signature-templates/**` to `READER` *or* `CONTRIBUTOR`, and every other method to
`CONTRIBUTOR`. Which documents a user may send is a separate decision, delegated to
`SignatureAccessPolicy`: the CE default (`DefaultSignatureAccessPolicy`) allows any
CONTRIBUTOR to send any active document, because core has no per-document permission model.
Envelope management (`canManage`) is restricted to the initiator's e-mail.

**Audit attribution.** e-Sign writes audit rows from contexts that have no logged-in user (the
signer has no Keycloak session; completion and expiry run in a scheduler). There is
deliberately no "log as an arbitrary string" API. `SignatureActorResolver` builds a synthetic
`JwtAuthenticationToken` whose `email` claim comes from a source the caller cannot forge — the
recipient row resolved by a *validated* token hash for a signer, the stored `initiatorEmail`
for the requester — marked with `azp: openfilz-signature-link` / `openfilz-signature-service`.
Audit rows are therefore never attributed to `anonymousUser`.

**Other guardrails.** Recipient e-mails must be unique per envelope; a SIGNER needs at least
one `SIGNATURE` or `INITIALS` field; field boxes must stay inside the page (`x+w ≤ 1`,
`y+h ≤ 1`) and inside the real page count; submitted field ids must belong to the recipient;
images must be `data:image/…` or base64 and under the size cap; client IP comes from
`X-Forwarded-For` (first hop) when present.

---

## 6. REST API

### Initiator — `/api/v1/signatures` (OIDC; `READER` to read, `CONTRIBUTOR` to write)

| Method | Path | Notes |
|---|---|---|
| `POST` | `/signatures` | Create. `send: false` in the body keeps a `DRAFT`; default is send-now. |
| `POST` | `/signatures/{id}/send` | `DRAFT → SENT`; every token is re-issued. `409` if already sent. |
| `GET` | `/signatures?status=` | Envelopes I sent, newest first. |
| `GET` | `/signatures/to-sign` | `SENT` envelopes where I am a pending/viewed SIGNER (matched by e-mail). |
| `GET` | `/signatures/{id}` | `SignatureEnvelopeDTO` — recipients with their fields and values. |
| `GET` | `/signatures/{id}/events` | The append-only event trail (`SignatureEventDTO[]`). |
| `POST` | `/signatures/{id}/cancel` | Non-terminal envelopes only. |
| `POST` | `/signatures/{id}/recipients/{rid}/resend` | New token, previous link dead, reminder mail sent. |
| `GET` | `/signatures/{id}/signed-document` | Streams the sealed PDF (`application/pdf`, initiator only). |

### Templates — `/api/v1/signature-templates`

`POST` · `GET` · `GET /{id}` · `PUT /{id}` · `DELETE /{id}` · `POST /{id}/envelopes`
(instantiate: bind every template role to a person, optionally overriding `sourceDocId`).

### Signer — `/api/v1/public/signatures` (no session; `?token=` is the credential)

| Method | Path | Notes |
|---|---|---|
| `GET` | `` | `PublicSignatureView`: envelope, this recipient, their fields, other recipients' already-filled values, `myTurn`, `otpRequired`. |
| `POST` | `/viewed` | Records the open (IP + user-agent) and flips the recipient to `VIEWED`. |
| `GET` | `/document` | The source PDF, inline. |
| `POST` | `/otp/request` | `202`. `501` if this deployment has no sender for the recipient's `authMethod`. |
| `POST` | `/otp/verify` | Body `{ "code": "123456" }`. |
| `POST` | `/sign` | Body `{ "fields": [{ "fieldId": …, "value": …, "valueImage": … }] }`. The legacy single-field shape (exactly one of `signatureImage` or `typedName`, applied to every SIGNATURE/INITIALS field) is still accepted. |
| `POST` | `/decline` | Optional `{ "reason": "…" }`; voids the envelope and alerts the initiator. |

### Example — create and send an envelope

```bash
curl -X POST "$API/api/v1/signatures" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{
  "sourceDocId": "6f1e0f2c-1f2a-4b7d-9d4e-2a5c0e7b1a33",
  "title": "Service agreement",
  "message": "Please sign before Friday.",
  "sequential": true,
  "expiresInDays": 14,
  "locale": "en",
  "recipients": [
    {
      "name": "Ada Lovelace", "email": "ada@example.com",
      "orderIndex": 0, "authMethod": "EMAIL_OTP",
      "fields": [
        { "type": "SIGNATURE",   "page": 2, "x": 0.10, "y": 0.12, "w": 0.30, "h": 0.08 },
        { "type": "DATE_SIGNED", "page": 2, "x": 0.50, "y": 0.12, "w": 0.20, "h": 0.04 },
        { "type": "SELECT",      "page": 0, "x": 0.10, "y": 0.50, "w": 0.30, "h": 0.04,
          "label": "Plan", "options": { "choices": ["Basic", "Pro"] } }
      ]
    },
    { "name": "Legal", "email": "legal@example.com", "role": "CC" }
  ]
}'
```

Returns a `SignatureEnvelopeDTO` with `status: "SENT"`. Add `"send": false` to get a `DRAFT`
instead, or `"reminderDays": 3` to arm the Enterprise reminder scheduler (the field is
persisted in CE but nothing acts on it there).

---

## 7. Operations

* **Sweeper.** `openfilz.signature.sweep.cron`, every 5 minutes by default. It self-guards on
  `openfilz.signature.active`, logs `[e-sign] sweeper expired N envelope(s)` when it does
  something, and writes an `ENVELOPE_EXPIRED` event plus a `SIGNATURE_ENVELOPE_EXPIRED` audit
  row per envelope.
* **Mail locales.** Bodies come from `resources/signature-mail/messages_*.properties`:
  `en`, `fr`, `de`, `es`, `it`, `nl`, `pt`, `ar` (plus a default bundle). The locale is
  resolved per message as *recipient locale → envelope locale → English*, normalised to the
  language subtag (`fr-CA` → `fr`). Sending is fire-and-forget on the bounded-elastic
  scheduler: a broken SMTP server logs `[e-sign] failed to send mail …` and never fails the
  signing flow — which also means a bounced invitation is invisible to the initiator.
* **Audit actions.** `SIGNATURE_ENVELOPE_CREATED`, `_SENT`, `_COMPLETED`, `_DECLINED`,
  `_CANCELLED`, `_EXPIRED`, plus `SIGNATURE_DOCUMENT_SIGNED`, `SIGNATURE_REMINDER_SENT`,
  `SIGNATURE_TEMPLATE_CREATED`, `SIGNATURE_TEMPLATE_DELETED`.
* **Event types** (envelope trail, rendered into the Certificate of Completion):
  `ENVELOPE_CREATED`, `ENVELOPE_SENT`, `RECIPIENT_VIEWED`, `RECIPIENT_OTP_VERIFIED`,
  `RECIPIENT_SIGNED`, `RECIPIENT_DECLINED`, `RECIPIENT_REMINDED`, `RECIPIENT_LINK_RESENT`,
  `ENVELOPE_COMPLETED`, `ENVELOPE_CANCELLED`, `ENVELOPE_EXPIRED`.
* **`GET /api/v1/settings`** exposes the e-Sign fields the web app depends on:
  `signatureActive` (boolean) drives the *Signatures* menu and the *Request signature*
  document action; `signatureAuthMethods` (string list) is the set of recipient
  authentication methods this deployment can actually deliver — always `NONE`, plus
  `EMAIL_OTP` and/or `SMS_OTP` when a matching `SignatureOtpSender` bean is registered *and*
  configured. The UI offers only those, which keeps it from proposing a channel the API would
  refuse at create time. `sealProvider` (string, null when e-Sign is off) reports the seal
  identity that will actually sign completed envelopes, via the protected
  `SettingsServiceImpl.effectiveSealProvider()` hook (the EE overrides it to report
  archiving-api's provider): while it is `self-signed-dev` the web app shows a dismissible
  "untrusted demo seal" notice on the signature screens. `signatureCloudActive` turns on the
  Cloud Signing subscription card on the Settings page. Unrelated to e-Sign but delivered on
  the same endpoint: `demoMode` (`openfilz.demo-mode` / `OPENFILZ_DEMO_MODE`, default false)
  marks shared public demo deployments — the web app then shows the demo disclaimers
  (shared-visibility warning on CE, demo note + trial CTA on EE).
* **Schema.** Flyway `V1_7__create_signature_schema.sql`, fully idempotent
  (`CREATE TABLE IF NOT EXISTS` / `ADD COLUMN IF NOT EXISTS`): `signature_envelope`,
  `signature_recipient`, `signature_field`, `signature_template`, `signature_event`.
* **Dependencies added for this feature.** `spring-boot-starter-mail` and Bouncy Castle
  (`bcpkix-jdk18on`). `SHA256withRSA` is resolved from the default JDK provider rather than
  `"BC"` — forcing the BC provider breaks in GraalVM native images.

---

## 8. Extension points

Seven interfaces in `org.openfilz.dms.service.signature`. Each has a permissive or no-op core
default; register your own bean `@Primary` to take over.

| Seam | Core default | What you would override it for |
|---|---|---|
| `SignatureAccessPolicy` | any CONTRIBUTOR may send any active document; signed copy next to the source; no post-persist hook | plug your own per-document permission model, and choose where the signed copy lands |
| `SignatureActorResolver` | synthetic JWT built from the trusted stored e-mail | attribute audit to a real IdP principal (e.g. an OIDC token exchange) |
| `SignatureNotifier` | no-op | push in-app notifications on request / completed / declined |
| `SignatureMailer` | `SmtpSignatureMailer`, or `LoggingSignatureMailer` with no SMTP host | template engine, transactional-mail provider, per-tenant branding |
| `SignatureSealer` | `InProcessSignatureSealer` or `CloudSignatureSealer`, chosen at runtime by `SignatureConfig` | HSM / KMS / PKCS#11 keys, PDF/A conversion, timestamping. The core bean stays available as `@Qualifier(SignatureConfig.CORE_SEALER)` so you can delegate to it as a fallback. |
| `SignatureOtpSender` | `EmailSignatureOtpSender` (`EMAIL_OTP`) | add a channel — `supports(method)` is what makes it appear in `Settings.signatureAuthMethods` |
| `SignatureCompletionListener` | no-op | record compliance metadata after the signed document row is inserted (runs inside the completion transaction) |

`SignatureSealer` is also the fallback seam: if the `@Primary` sealer fails, the core sealer is
tried automatically, so envelope completion never blocks on an external signing service.

---

## 9. What the Enterprise Edition adds

Same envelope engine, same API, same database — the EE registers `@Primary` implementations of
the seams above (gated on `openfilz.features.custom-access=true`) plus a few extra endpoints:

* **PDF/A-2b + a trusted, timestamped seal.** `ArchivingSignatureSealer` hands the stamped PDF
  to `archiving-api`, which converts it to PDF/A-2b, seals it with an AATL certificate (PKCS#12,
  Azure Key Vault or `sign.openfilz.com`), adds an RFC-3161 timestamp and validates the result
  with veraPDF. Falls back to the core sealer on outage.
* **Automatic reminders** on the envelope's `reminderDays` cadence, **bulk send** from
  template × rows, **embedded signing** URLs for iframe/SDK integration.
* **SMS one-time codes** (`SMS_OTP`) through a pluggable gateway.
* **In-app notifications**, **owner/write-share authorisation** for who may send a document,
  and **Keycloak token-exchange** audit attribution.
* **`signature.*` webhook events** dispatched by `webhooks-api`.

Full configuration reference and the CE-vs-EE capability table:
**`openfilz-enterprise/docs/signing.md`**.
