package me.didit.webhooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import me.didit.core.Conv;
import me.didit.core.Json;

/**
 * A verified webhook delivery (DESIGN 9.4). Check the concrete type with {@code instanceof} (or
 * Kotlin's {@code when}); unknown types arrive as {@link UnknownEvent}.
 *
 * <p>Every getter is lenient: fields Didit does not send for a given event are empty, and {@link
 * #_json()} returns the whole payload.
 */
public abstract class WebhookEvent {
  /** The payload, owned by this event. */
  final ObjectNode json;

  private final VerifiedBy verifiedBy;

  WebhookEvent(ObjectNode json, VerifiedBy verifiedBy) {
    this.json = json;
    this.verifiedBy = verifiedBy;
  }

  static WebhookEvent of(ObjectNode json, VerifiedBy verifiedBy) {
    String type = type(json);
    switch (type) {
      case SessionStatusUpdatedEvent.TYPE:
        return new SessionStatusUpdatedEvent(json, verifiedBy);
      case SessionDataUpdatedEvent.TYPE:
        return new SessionDataUpdatedEvent(json, verifiedBy);
      case UserStatusUpdatedEvent.TYPE:
        return new UserStatusUpdatedEvent(json, verifiedBy);
      case UserDataUpdatedEvent.TYPE:
        return new UserDataUpdatedEvent(json, verifiedBy);
      case BusinessStatusUpdatedEvent.TYPE:
        return new BusinessStatusUpdatedEvent(json, verifiedBy);
      case BusinessDataUpdatedEvent.TYPE:
        return new BusinessDataUpdatedEvent(json, verifiedBy);
      case ActivityCreatedEvent.TYPE:
        return new ActivityCreatedEvent(json, verifiedBy);
      case TransactionCreatedEvent.TYPE:
        return new TransactionCreatedEvent(json, verifiedBy);
      case TransactionStatusUpdatedEvent.TYPE:
        return new TransactionStatusUpdatedEvent(json, verifiedBy);
      case TravelRuleStatusUpdatedEvent.TYPE:
        return new TravelRuleStatusUpdatedEvent(json, verifiedBy);
      case WorkflowComplianceChangedEvent.TYPE:
        return new WorkflowComplianceChangedEvent(json, verifiedBy);
      case KybRegistrySearchResolvedEvent.TYPE:
        return new KybRegistrySearchResolvedEvent(json, verifiedBy);
      default:
        return new UnknownEvent(json, verifiedBy);
    }
  }

  /** {@code webhook_type}, or {@code event_type} for deliveries that use that name. */
  static String type(ObjectNode json) {
    for (String field : new String[] {"webhook_type", "event_type"}) {
      JsonNode value = json.get(field);
      if (value != null && value.isTextual()) {
        return value.textValue();
      }
    }
    return "";
  }

  /**
   * The event type, for example {@code status.updated}.
   *
   * @return the type; empty when the payload names none
   */
  public final String webhookType() {
    return type(json);
  }

  /**
   * The unique id of this event; use it to drop duplicate deliveries.
   *
   * @return the id
   */
  public final Optional<String> eventId() {
    return optional("event_id", Conv.STRING);
  }

  /**
   * When the delivery was signed, in unix seconds.
   *
   * @return the timestamp
   */
  public final Optional<Long> timestamp() {
    return optional("timestamp", Conv.LONG);
  }

  /**
   * When the change happened, in unix seconds.
   *
   * @return the time
   */
  public final Optional<Long> createdAt() {
    return optional("created_at", Conv.LONG);
  }

  /**
   * The application the event belongs to.
   *
   * @return the application id
   */
  public final Optional<String> applicationId() {
    return optional("application_id", Conv.STRING);
  }

  /**
   * {@code live} or {@code sandbox}.
   *
   * @return the environment
   */
  public final Optional<String> environment() {
    return optional("environment", Conv.STRING);
  }

  /**
   * Whether the event comes from a sandbox application.
   *
   * @return true for sandbox events
   */
  public final boolean isSandbox() {
    return environment().map("sandbox"::equals).orElse(false);
  }

  /**
   * Which signature verified the delivery.
   *
   * @return the signature kind
   */
  public final VerifiedBy verifiedBy() {
    return verifiedBy;
  }

  /**
   * Whether the whole body is authenticated. False when only the deprecated Simple signature
   * matched: then re-fetch the record from the API before acting on it.
   *
   * @return true for {@code X-Signature} and {@code X-Signature-V2}
   */
  public final boolean isBodyAuthenticated() {
    return verifiedBy == VerifiedBy.SIGNATURE || verifiedBy == VerifiedBy.SIGNATURE_V2;
  }

  /**
   * A copy of the whole payload.
   *
   * @return the JSON object
   */
  public final ObjectNode _json() {
    return json.deepCopy();
  }

  final <T> Optional<T> optional(String field, Conv<T> conv) {
    return Json.optional(json, field, conv);
  }

  @Override
  public String toString() {
    return getClass().getSimpleName()
        + "{type="
        + webhookType()
        + ", eventId="
        + eventId().orElse("none")
        + ", verifiedBy="
        + verifiedBy
        + "}";
  }
}
