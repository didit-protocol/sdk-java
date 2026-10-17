package me.didit.webhooks;

/** Which signature authenticated a webhook delivery. */
public enum VerifiedBy {
  /** {@code X-Signature} (or its {@code -Previous}): the HMAC of the exact body bytes. */
  SIGNATURE,
  /** {@code X-Signature-V2} (or its {@code -Previous}): the HMAC of the canonical JSON. */
  SIGNATURE_V2,
  /**
   * {@code X-Signature-Simple} (or its {@code -Previous}), accepted only with {@code allowSimple}.
   * It covers the envelope only: the body is not authenticated, so re-fetch the record from the API
   * before acting on it.
   */
  SIGNATURE_SIMPLE,
  /** Not verified: the event was parsed with {@link Webhooks#parseEvent}. */
  NONE
}
