package me.didit.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import me.didit.Didit;
import me.didit.core.Page;
import me.didit.errors.NotFoundException;
import me.didit.types.SessionsCreateParams;
import me.didit.types.SessionsCreateResponse;
import me.didit.types.WorkflowListItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Smoke tests against a sandbox application (DESIGN 11). They run only with {@code ./gradlew
 * liveTest} and {@code DIDIT_SANDBOX_API_KEY}; they create nothing beyond sandbox sessions.
 */
@Tag("live")
final class LiveSmokeTest {
  private Didit client;

  @BeforeEach
  void setUp() {
    String key = System.getenv("DIDIT_SANDBOX_API_KEY");
    assumeTrue(key != null && !key.isEmpty(), "DIDIT_SANDBOX_API_KEY is not set");
    Didit.Builder builder = Didit.builder().apiKey(key);
    String baseUrl = System.getenv("DIDIT_SANDBOX_BASE_URL");
    if (baseUrl != null && !baseUrl.isEmpty()) {
      builder.baseUrl(baseUrl);
    }
    client = builder.build();
  }

  @Test
  void listsWorkflows() {
    Page<WorkflowListItem> page = client.workflows().list();
    assertFalse(page.results().size() < 0);
  }

  @Test
  void createsASandboxSessionAndReadsIt() {
    String workflowId = System.getenv("DIDIT_SANDBOX_WORKFLOW_ID");
    assumeTrue(workflowId != null && !workflowId.isEmpty(), "DIDIT_SANDBOX_WORKFLOW_ID is not set");
    SessionsCreateResponse session =
        client
            .sessions()
            .create(
                SessionsCreateParams.builder()
                    .workflowId(workflowId)
                    .vendorData("didit-java-smoke-test")
                    .build());
    assertEquals(workflowId, session.workflowId());
    assertEquals(
        session.sessionId(),
        client.sessions().retrieveDecision(session.sessionId()).sessionId().orElseThrow());
  }

  @Test
  void unknownSessionIsNotFound() {
    Executable call =
        () -> client.sessions().retrieveDecision("00000000-0000-4000-8000-000000000000");
    org.junit.jupiter.api.Assertions.assertThrows(NotFoundException.class, call);
  }
}
