package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.Optional;
import me.didit.core.Conv;
import me.didit.types.BankConnectionUpdatedData;

/**
 * {@code bank.connection.updated}: a connection's status changed in a way no other event reports.
 * It has two shapes. A revocation starting carries {@code connection_id}, {@code status} and {@code
 * revocation_status: pending} until the bank confirms it. Every other update carries {@code
 * connection_id}, {@code status}, {@code status_reason} and {@code ends_at}. Read the shape-specific
 * fields with {@link #revocationStatus()}, {@link #statusReason()} and {@link #endsAt()}, which are
 * empty when the field is absent or null.
 */
public final class BankConnectionUpdatedEvent extends BankEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "bank.connection.updated";

  BankConnectionUpdatedEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }

  /**
   * The connection id and status. On an update that is not a revocation, {@code
   * data().revocationStatus()} throws because the field is absent: use {@link #revocationStatus()}
   * instead.
   *
   * @return the connection data
   */
  @Override
  public BankConnectionUpdatedData data() {
    return BankConnectionUpdatedData.fromJson(json);
  }

  /**
   * {@code revocation_status}: {@code pending} while a requested revocation waits for the bank.
   * Present only on the revocation shape.
   *
   * @return the revocation status, or empty
   */
  public Optional<BankConnectionUpdatedData.RevocationStatus> revocationStatus() {
    return optional(
        "revocation_status", Conv.enumeration(BankConnectionUpdatedData.RevocationStatus::of));
  }

  /**
   * {@code status_reason}: why the connection changed, {@code insufficient_balance} or {@code
   * first_sync_failed}. Empty when null or absent (always absent on the revocation shape).
   *
   * @return the reason, or empty
   */
  public Optional<String> statusReason() {
    return optional("status_reason", Conv.STRING);
  }

  /**
   * {@code ends_at}: when the connection ends unless its coming month is paid. Empty when null or
   * absent (always absent on the revocation shape).
   *
   * @return the end time, or empty
   */
  public Optional<OffsetDateTime> endsAt() {
    return optional("ends_at", Conv.DATE_TIME);
  }
}
