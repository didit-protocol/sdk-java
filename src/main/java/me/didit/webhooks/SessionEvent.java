package me.didit.webhooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Optional;
import me.didit.core.Conv;
import me.didit.types.SessionsRetrieveDecisionResponse;

/** A verification session event: {@code status.updated} or {@code data.updated}. */
public abstract class SessionEvent extends WebhookEvent {
  SessionEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }

  /**
   * The session id.
   *
   * @return the id
   */
  public final Optional<String> sessionId() {
    return optional("session_id", Conv.STRING);
  }

  /**
   * The business session id, for KYB sessions.
   *
   * @return the id
   */
  public final Optional<String> businessSessionId() {
    return optional("business_session_id", Conv.STRING);
  }

  /**
   * {@code user} (KYC) or {@code business} (KYB).
   *
   * @return the session kind
   */
  public final Optional<String> sessionKind() {
    return optional("session_kind", Conv.STRING);
  }

  /**
   * The session status, for example {@code Approved}.
   *
   * @return the status
   */
  public final Optional<String> status() {
    return optional("status", Conv.STRING);
  }

  /**
   * The workflow of the session.
   *
   * @return the workflow id
   */
  public final Optional<String> workflowId() {
    return optional("workflow_id", Conv.STRING);
  }

  /**
   * The workflow version the session ran.
   *
   * @return the version
   */
  public final Optional<Long> workflowVersion() {
    return optional("workflow_version", Conv.LONG);
  }

  /**
   * Your reference for the user or business, as given when the session was created.
   *
   * @return the vendor data
   */
  public final Optional<String> vendorData() {
    return optional("vendor_data", Conv.STRING);
  }

  /**
   * The metadata given when the session was created.
   *
   * @return the metadata
   */
  public final Optional<Map<String, JsonNode>> metadata() {
    return optional("metadata", Conv.OBJECT);
  }

  /**
   * The decision, sent with {@code Approved}, {@code Declined}, {@code In Review} and {@code
   * Abandoned}. Its fields depend on the application's webhook version; read raw values with
   * {@link SessionsRetrieveDecisionResponse#_json()}.
   *
   * @return the decision
   */
  public final Optional<SessionsRetrieveDecisionResponse> decision() {
    return optional("decision", Conv.model(SessionsRetrieveDecisionResponse::fromJson));
  }

  /**
   * What the user must resubmit, sent with {@code Resubmitted}.
   *
   * @return {@code nodes_to_resubmit} and {@code reasons}
   */
  public final Optional<JsonNode> resubmitInfo() {
    return optional("resubmit_info", Conv.ANY);
  }
}
