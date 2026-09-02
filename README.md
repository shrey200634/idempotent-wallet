# Idempotent Payment/Wallet Event Processor

## Run the tests in IntelliJ
1. Open this folder (IntelliJ will auto-detect `build.gradle` / `settings.gradle` and prompt
   "Trust Gradle Project" — accept it, and let it sync/download dependencies, needs internet
   the first time).
2. Right-click `src/test/java/com/shrey/wallet/TransactionProcessingTest.java` → **Run**.
3. Watch the console — each test prints its `@DisplayName` and the actual numbers
   (success/duplicate counts, final balance).

No Gradle wrapper is checked in — IntelliJ will generate one automatically on first import
(or run `gradle wrapper` yourself once if you have Gradle installed locally, then commit
`gradlew`, `gradlew.bat`, and the `gradle/wrapper/` folder before pushing, so the reviewer
doesn't need Gradle pre-installed).

## Run the app itself
`./gradlew bootRun` (or `gradle bootRun` if you haven't generated the wrapper), then:
```
POST http://localhost:8080/api/v1/transactions/process
Content-Type: application/json

{
  "transactionId": "11111111-1111-1111-1111-111111111111",
  "userId": "<any UUID>",
  "amount": 250.00,
  "type": "DEBIT"
}
```
Note: there's no wallet-creation endpoint yet — the tests seed a wallet directly via
`WalletRepository`. Add a `POST /api/v1/wallets` endpoint if you want to hit this via
Postman/curl with a real wallet.
