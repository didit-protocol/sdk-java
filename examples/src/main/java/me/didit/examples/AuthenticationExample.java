package me.didit.examples;

import me.didit.authentication.AccessTokenClaims;
import me.didit.authentication.Authentication;
import me.didit.authentication.StepUp;
import me.didit.authentication.StepUpParams;
import me.didit.errors.TokenVerificationException;

/**
 * Didit Authentication on your backend: verify the access token of a request, then ask for a
 * step-up before a payout.
 */
public final class AuthenticationExample {
  private AuthenticationExample() {}

  // One instance per issuer: it caches discovery and the signing keys.
  private static final Authentication AUTH =
      Authentication.builder()
          .publishableKey(Env.require("DIDIT_PUBLISHABLE_KEY"))
          .clientId(Env.require("DIDIT_APPLICATION_ID"))
          .build();

  /** Returns the user id of a request's {@code Authorization: Bearer} token, or null. */
  static String authenticate(String authorizationHeader) {
    if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
      return null;
    }
    try {
      AccessTokenClaims claims = AUTH.verifyAccessToken(authorizationHeader.substring(7));
      return claims.subject();
    } catch (TokenVerificationException e) {
      System.out.println("Rejected token: " + e.reason().code());
      return null;
    }
  }

  public static void main(String[] args) {
    String accessToken = args[0];
    System.out.println("User: " + authenticate("Bearer " + accessToken));
    StepUp stepUp =
        AUTH.createStepUp(
            accessToken,
            StepUpParams.builder("payout")
                .amount("250.00")
                .currency("EUR")
                .returnTo("https://app.example.com/payouts")
                .build());
    if (!stepUp.required()) {
      System.out.println("No step-up needed");
    } else {
      System.out.println("Send the user to " + stepUp.verificationUrl().orElse("?"));
      // When the user returns, act only on an approved step-up:
      // AUTH.retrieveStepUp(accessToken, stepUp.id().orElseThrow()).isApproved()
    }
  }
}
