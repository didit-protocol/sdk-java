package me.didit.webhooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import me.didit.core.Conv;
import me.didit.core.Json;
import me.didit.types.BankLinkEventData;

/**
 * A Bank Link event: {@code bank.link.completed}, {@code bank.link.cancelled}, {@code
 * bank.link.expired} or {@code bank.link.failed}. The ids and statuses are under {@code data}.
 *
 * <p>To correlate a renewal link with the connection it renews, use {@link #renewsConnectionId()}
 * when present and fall back to {@code data().connectionId()}: an older failed renewal carries its
 * target in {@code connection_id} and no {@code renews_connection_id}.
 */
public abstract class BankLinkEvent extends BankEvent {
  BankLinkEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }

  /**
   * The link id, status, resulting connection, renewal target, ownership check and error code.
   *
   * @return the link data
   */
  @Override
  public final BankLinkEventData data() {
    return BankLinkEventData.fromJson(Json.required(json, "data", Conv.ANY));
  }

  /**
   * {@code data.renews_connection_id}: the connection a renewal link targets, set when the link is
   * created. Empty on a link that renews nothing and on events stored before the field existed.
   * {@code data.connection_id} is the connection the link produced or renewed, null until the link
   * completes.
   *
   * @return the renewed connection id, or empty
   */
  public final Optional<String> renewsConnectionId() {
    JsonNode data = Json.required(json, "data", Conv.ANY);
    return Json.optional(data, "renews_connection_id", Conv.STRING);
  }
}
