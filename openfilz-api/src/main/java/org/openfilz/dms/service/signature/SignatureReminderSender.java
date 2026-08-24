package org.openfilz.dms.service.signature;

/**
 * Marker for a component that actually sends the scheduled reminders an envelope's
 * {@code reminderDays} cadence promises.
 *
 * <p>The core stores {@code reminderDays} but never acts on it: its schedulers close expired
 * envelopes, they do not chase pending recipients. So a deployment with no implementation of this
 * interface reports {@code signatureRemindersActive: false} in {@code GET /settings}, and the web
 * app hides the "remind every N days" field rather than offering a setting that would be recorded
 * and then silently ignored.
 *
 * <p>Deliberately empty: presence in the context is the whole signal. The Enterprise edition's
 * {@code SignatureReminderScheduler} implements it.
 */
public interface SignatureReminderSender {
}
