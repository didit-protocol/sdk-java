package me.didit.webhooks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import me.didit.core.Conv;

/** {@code workflow.compliance_changed}: a workflow's compliance status changed. */
public final class WorkflowComplianceChangedEvent extends WebhookEvent {
  /** The {@code webhook_type} of this event. */
  public static final String TYPE = "workflow.compliance_changed";

  WorkflowComplianceChangedEvent(ObjectNode json, VerifiedBy verifiedBy) {
    super(json, verifiedBy);
  }

  /**
   * The workflow.
   *
   * @return the workflow id
   */
  public Optional<String> workflowId() {
    return optional("workflow_id", Conv.STRING);
  }
}
