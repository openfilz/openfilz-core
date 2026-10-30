# e-Sign — internals

Contributor notes for the e-Sign feature: the design decisions and mechanics behind
[the public page](../esign.md), which describes what the feature does and how to configure it.
This page is not published on the website.

## Runtime toggle and mailer selection

* `openfilz.signature.active` is read at runtime (never as a bean condition), so a single GraalVM
  native image serves deployments with the feature on or off. When off, `/api/v1/signatures/**`,
  `/api/v1/signature-templates/**` and `/api/v1/public/signatures/**` answer `404`, the public
  security chain matches an unreachable sentinel path, the sweeper idles, and
  `GET /api/v1/settings` reports `signatureActive: false`. Flipping it requires a restart (the
  security chain reads it once at startup).
* Without SMTP (`spring.mail.host` empty) the context wires `LoggingSignatureMailer` instead of
  `SmtpSignatureMailer`.
* `openfilz.signature.sweep.cron` is bound through the `@Scheduled` placeholder, not
  `SignatureProperties`. The OTP attempt counter is reserved atomically, so parallel guesses share
  the same budget.
* `SignatureConfig` uses `seal.provider` only to decide between the cloud sealer and the in-process
  one (only `openfilz-cloud` is matched explicitly); the in-process sealer then reports itself as
  `pkcs12` when `keystore-path` is set and `self-signed-dev` otherwise.

## Security model

**Signing links.** Each recipient gets `{web-base-url}sign?token=<token>`, where the token is
32 bytes from `SecureRandom`, base64url without padding. Only `sha256(token)` is stored on the
recipient row — the raw token exists in the e-mail and nowhere else. The lookup is
`findByTokenHash(sha256(raw))`; an unknown token is `404`.

**What a link may show.** The view endpoints (`GET` and `POST /viewed`) answer in three
shapes, decided by `SignatureServiceImpl.publicView`:

* **Open envelope, OTP passed (or none required)** — the full view: the recipient's own
  fields with their values, and the other recipients' already-filled fields as *placements*
  (`id`, `recipientId`, `type`, position, `filledAt`) with `value` and `valueImage` **null**.
  Another signer's signature image or typed text never crosses a link that is not theirs.
* **OTP recipient who has not passed the step** (or whose verification has aged past
  `otp.verified-for`) — a status-only view: envelope title, initiator, the recipient's own
  name / e-mail, statuses, `otpRequired: true`, `otpVerified: false`; `documentName`,
  `message`, `fields` and `otherFields` are empty. `GET /document` answers `403` until the
  code is verified. The signing page shows the code step from this view before it ever asks
  for the PDF.
* **Closed envelope** (`COMPLETED` / `DECLINED` / `CANCELLED` / `EXPIRED`, an envelope past
  `expiresAt` the sweeper has not flipped yet, or a revoked token) — the same status-only
  view, so the page can say "completed" / "cancelled", and nothing else: no document, no
  fields. The completed PDF reaches the parties through the completion e-mail (attachment)
  and the initiator through `GET /signatures/{id}/document`, never through a dead link.

The response of the recipient's **own** `/sign`, `/decline` and `/otp/verify` call is the full
view even when that call just closed the envelope, so the signer sees what they signed once.

**Actions on a closed link.** `GET /document`, `POST /otp/request`, `/otp/verify`, `/sign` and
`/decline` answer `410 Gone` for a terminal or expired envelope and for a revoked token, `409`
for a draft. `/decline` requires the passed OTP step like `/sign` (voiding the envelope is as
consequential as signing it).

**Revocation.** When an envelope reaches a terminal state — completion, decline, cancel, the
expiry sweeper — every recipient's `token_revoked` is set in the same transaction, so no link
of that envelope can act again whatever its hash. Sending a draft, resending a link, advancing
a sequential envelope to the next group, sending a scheduled reminder and minting an
embedded-signing URL (an extension endpoint) all go through one helper (`issueToken`) that **overwrites the stored
hash with a fresh one**, lifts the revocation and restarts the per-link OTP request throttle —
which is what makes the previous link stop resolving. Resend, next-group advance and scheduled
reminders also clear `otpVerifiedAt`, so the OTP step must be passed again on the new link; the
embedded-signing URL rotation does not.

**OTP.** For `EMAIL_OTP`, `POST /otp/request` generates an all-digit code, stores only its
SHA-256, and mails it — under two throttles enforced by one conditional `UPDATE`
(`SignatureRecipientRepository.issueOtp`): a cooldown between requests on the same link
(`otp.request-cooldown`, `429` with the remaining wait) and a lifetime cap per link
(`otp.max-requests`, `429` telling the signer to ask for a new link); a new code resets the
failed-attempt counter. `POST /otp/verify` first **reserves an attempt** with an atomic
conditional increment (`reserveOtpAttempt`: `otp_attempts + 1 WHERE otp_attempts < max`, its
own statement, so a wrong guess keeps its slot consumed) — parallel guesses can never exceed
`max-attempts` together, the overflow answers `429` — then compares in constant time
(`MessageDigest.isEqual`); a wrong code is `403`, a missing or expired code `410`. On success
`otpHash` is cleared and `otpVerifiedAt` is set; the unlock lasts `otp.verified-for`. `/sign`
and `GET /document` answer `403` until then. An `authMethod` that this deployment cannot
deliver is refused **at envelope creation** (`422`) rather than stranding a signer who could
never pass the step.

**Public chain.** `/api/v1/public/signatures/**` gets its own `@Order(-3)`
`SecurityWebFilterChain` (`SignaturePublicSecurityConfig`) with CSRF disabled and
`permitAll()` — the token in the query string is the authenticator. When the feature is off
the chain's matcher points at `/_disabled-public-signatures-chain`, so it stays in the context
(native-image safe) while matching nothing.

**Roles.** `AbstractSecurityService.isSignatureAuthorized` maps `GET` on `/signatures/**` and
`/signature-templates/**` to `READER` *or* `CONTRIBUTOR`, and every other method to
`CONTRIBUTOR` — plus `SIGN_REQUESTER` when `openfilz.signature.require-requester-role=true`
(see §3.1), which lets a deployment restrict per user who may initiate signature requests.
Which documents a user may send is a separate decision, delegated to
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

## Operations

* **Sweeper.** `openfilz.signature.sweep.cron`, hourly by default. It self-guards on
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
* **Finalization and the audit chain.** A signature is one transaction: recipient + fields +
  `RECIPIENT_SIGNED` event, then — when it was the last one — stamp, seal (an HTTP call for remote
  seal providers), store, the signed document row, `COMPLETED`, link
  revocation and the completion listener; **the audit rows (`SIGNATURE_DOCUMENT_SIGNED`,
  then `SIGNATURE_ENVELOPE_COMPLETED`) are written last**, right before the commit. The
  chained audit insert takes the instance-wide advisory lock until the commit, so putting it
  first would have held every other audited write (uploads, moves, downloads…) behind a seal
  call. Notifications, the completion mails and post-processing run after the commit. A seal
  or completion-listener failure still fails the whole signature, so the recipient stays
  actionable and simply retries; an envelope wedged by the historical partial commit is
  re-finalised when a signer re-opens their link (standalone transaction, then audit, then
  mails). The envelope row is locked (`SELECT … FOR UPDATE`) for the signing transaction, so
  two last signers finishing together serialise and the second one completes the envelope.
  The lock wait itself is bounded by `openfilz.audit.chain.lock-timeout` (`5s`; `0` = forever):
  a holder stuck in slow work fails the waiting audit insert with a logged
  `Audit chain lock not acquired within …` instead of queueing the instance behind it.
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
  `SettingsServiceImpl.effectiveSealProvider()` hook (an extension may override it to report
  its own seal provider): while it is `self-signed-dev` the web app shows a dismissible
  "untrusted demo seal" notice on the signature screens. `signatureCloudActive` turns on the
  Cloud Signing subscription card on the Settings page. Unrelated to e-Sign but delivered on
  the same endpoint: `demoMode` (`openfilz.demo-mode` / `OPENFILZ_DEMO_MODE`, default false)
  marks shared public demo deployments — the web app then shows the demo disclaimers
  (shared-visibility warning).
* **Schema.** Flyway `V1_7__create_signature_schema.sql`, fully idempotent
  (`CREATE TABLE IF NOT EXISTS` / `ADD COLUMN IF NOT EXISTS`): `signature_envelope`,
  `signature_recipient`, `signature_field`, `signature_template`, `signature_event`.
* **Dependencies added for this feature.** `spring-boot-starter-mail` and Bouncy Castle
  (`bcpkix-jdk18on`). `SHA256withRSA` is resolved from the default JDK provider rather than
  `"BC"` — forcing the BC provider breaks in GraalVM native images.

## Extension points

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
