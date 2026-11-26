package me.didit.examples;

import java.time.OffsetDateTime;
import me.didit.Didit;
import me.didit.types.TransactionDetail;
import me.didit.types.TransactionsCreateParams;

/** Transaction monitoring: submit a payment for screening and read its status and score. */
public final class TransactionScreeningExample {
  private TransactionScreeningExample() {}

  public static void main(String[] args) {
    Didit client = Env.client();
    TransactionDetail transaction =
        client
            .transactions()
            .create(
                TransactionsCreateParams.builder()
                    .transactionId("order-1001")
                    .transactionAt(OffsetDateTime.now())
                    .transactionCategory(TransactionsCreateParams.TransactionCategory.FINANCE)
                    .transactionDetails(
                        TransactionsCreateParams.TransactionDetails.builder()
                            .direction(
                                TransactionsCreateParams.TransactionDetails.Direction.OUTBOUND)
                            .amount(250.0)
                            .currency("EUR")
                            .build())
                    .subject(
                        TransactionsCreateParams.Subject.builder()
                            .entityType("user")
                            .vendorData("user-42")
                            .fullName("Ana Ruiz")
                            .build())
                    .build());
    System.out.println(
        "Transaction "
            + transaction.uuid().orElse("?")
            + ": "
            + transaction.status().map(Object::toString).orElse("?")
            + ", score "
            + transaction.score().orElse(0L));
  }
}
