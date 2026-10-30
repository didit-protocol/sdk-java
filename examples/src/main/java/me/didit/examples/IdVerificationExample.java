package me.didit.examples;

import java.nio.file.Path;
import me.didit.Didit;
import me.didit.types.IdVerificationVerifyParams;
import me.didit.types.IdVerificationVerifyResponse;

/** Standalone ID verification: upload the front and back of a document (multipart). */
public final class IdVerificationExample {
  private IdVerificationExample() {}

  public static void main(String[] args) {
    Didit client = Env.client();
    IdVerificationVerifyResponse result =
        client
            .idVerification()
            .verify(
                IdVerificationVerifyParams.builder()
                    .frontImage(Path.of(args[0]))
                    .backImage(Path.of(args[1]))
                    .vendorData("user-42")
                    .build());
    result
        .idVerification()
        .ifPresent(
            id ->
                System.out.println(
                    "Status "
                        + id.status().map(Object::toString).orElse("?")
                        + ", document "
                        + id.documentType().orElse("?")));
  }
}
