package me.didit.examples;

import java.time.LocalDate;
import me.didit.Didit;
import me.didit.core.RequestOptions;
import me.didit.types.AmlScreenParams;
import me.didit.types.AmlScreenResponse;

/** Standalone AML screening of a person, with an idempotency key. */
public final class AmlScreeningExample {
  private AmlScreeningExample() {}

  public static void main(String[] args) {
    Didit client = Env.client();
    AmlScreenResponse result =
        client
            .aml()
            .screen(
                AmlScreenParams.builder()
                    .fullName("Ana Ruiz")
                    .dateOfBirth(LocalDate.of(1990, 1, 31))
                    .nationality("ES")
                    .entityType(AmlScreenParams.EntityType.PERSON)
                    .build(),
                RequestOptions.builder().idempotencyKey("screen-user-42-2026-11-01").build());
    result
        .aml()
        .ifPresent(
            aml ->
                System.out.println(
                    "AML " + aml.status().map(Object::toString).orElse("?") + ", hits " + aml.totalHits().orElse(0L)));
  }
}
