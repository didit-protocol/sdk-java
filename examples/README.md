# Examples

Runnable examples of the Didit Java SDK. Set `DIDIT_API_KEY` (and `DIDIT_BASE_URL` to point at a mock).

| Example | What it shows |
|---|---|
| `Quickstart` | Create a session, read the decision, verify a webhook |
| `QuickstartDemo` | The quickstart against a local mock of the API: `./gradlew :examples:quickstartDemo` |
| `IdVerificationExample` | Standalone ID verification with file uploads |
| `AmlScreeningExample` | AML screening with an idempotency key |
| `TransactionScreeningExample` | Transaction monitoring |
| `TravelRuleExample` | Wallet screening and the VASP directory with automatic pagination |
| `AuthenticationExample` | Verifying access tokens and asking for a step-up |
| `BankDataExample` | Create a Bank Link, verify `bank.connection.created` and read the accounts: `./gradlew :examples:bankData` |
| `KotlinQuickstart.kt` | The quickstart in Kotlin, with `when` over webhook events |

To run the Bank example, configure your application's webhook endpoint to forward signed deliveries to `http://127.0.0.1:8080/webhooks/didit`, preserving the raw body and signature headers. Set `DIDIT_WEBHOOK_SECRET` and run the command on the consenting user's desktop. The hosted link opens in their browser. The command exits after it receives the matching connection event and lists the accounts.

Webhook receivers:

| Framework | File |
|---|---|
| Spring Boot | `webhooks/SpringBootReceiver.java` |
| Javalin | `webhooks/JavalinReceiver.java` |
| Ktor | `KtorReceiver.kt` |
| JDK `HttpServer` | `webhooks/JdkHttpServerReceiver.java` |

Each one verifies the raw request body with `Webhooks.verify(...)` and answers 401 when verification fails.
