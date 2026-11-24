package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import me.didit.core.Conv;
import me.didit.core.Json;
import me.didit.types.BankLinkEventData;

/**
 * A Bank Link event: {@code bank.link.completed}, {@code bank.link.cancelled}, {@code
 * bank.link.expired} or {@code bank.link.failed}. The ids and statuses are under {@code data}.
 */
public abstract class BankLinkEvent extends BankEvent {
  BankLinkEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }

  /**
   * The link id, status, connection, ownership check and error code.
   *
   * @return the link data
   */
  @Override
  public final BankLinkEventData data() {
    return BankLinkEventData.fromJson(Json.required(json, "data", Conv.ANY));
  }
}
