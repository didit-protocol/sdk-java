package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** {@code kyb.registry_search.resolved}: a business registry search finished. Didit delivers it unsigned today, so {@link Webhooks#verify} rejects it with {@code missing_signature}; {@link Webhooks#parseEvent} reads it. */
public final class KybRegistrySearchResolvedEvent extends WebhookEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "kyb.registry_search.resolved";

  KybRegistrySearchResolvedEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }
}
