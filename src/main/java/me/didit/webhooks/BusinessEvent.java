package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import me.didit.core.Conv;

/** A business event */
public abstract class BusinessEvent extends WebhookEvent {
  BusinessEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }

  /**
   * Didit's id of {@code business.status.updated} or {@code business.data.updated}.:the business.
   *
   * @return the id
   */
  public final Optional<String> vendorBusinessId() {
    return optional("vendor_business_id", Conv.STRING);
  }

  /**
   * Your reference for {@code business.status.updated} or {@code business.data.updated}.:the
   * business.
   *
   * @return the vendor data
   */
  public final Optional<String> vendorData() {
    return optional("vendor_data", Conv.STRING);
  }

  /**
   * The status of {@code business.status.updated} or {@code business.data.updated}.:the business.
   *
   * @return the status
   */
  public final Optional<String> status() {
    return optional("status", Conv.STRING);
  }
}
