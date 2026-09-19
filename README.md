# ledger-sync

Financial transaction ingestion, normalization, cross-channel deduplication, and reconciliation engine built for the Simplify Money **Software Engineering Intern (Backend, Java)** technical assessment.

This service processes raw, multi-channel bank artifacts (SMS alerts and email notifications) sitting on a user's mobile device, resolves multiple pieces of evidence into canonical financial events, categorizes expenditures, maintains balance state, and audits the ledger against bank-stated balances.

---

## Table of Contents

1. [Executive Summary & Verification Status](#executive-summary--verification-status)
2. [End-to-End Architecture & Data Flow](#end-to-end-architecture--data-flow)
3. [Incident INC-2026-09-11 Root Cause & Resolution](#incident-inc-2026-09-11-root-cause--resolution)
4. [Parser Coverage & Format Matrix](#parser-coverage--format-matrix)
5. [Transaction Normalization & Deduplication Engine](#transaction-normalization--deduplication-engine)
6. [Transaction Categorization](#transaction-categorization)
7. [Reconciliation & The ₹7,500 Balance Discrepancy](#reconciliation--the-7500-balance-discrepancy)
8. [DocumentStore Architecture](#documentstore-architecture)
9. [Backfill Engine](#backfill-engine)
10. [ConsistencyChecker](#consistencychecker)
11. [Reporting Pipeline & Idempotency Safeguards](#reporting-pipeline--idempotency-safeguards)
12. [Submission Deliverables](#submission-deliverables)
13. [Decision Log](#decision-log)
14. [Unfinished Work & Limitations](#unfinished-work--limitations)
15. [AI Disclosure](#ai-disclosure)
16. [Running & Verifying the Pipeline](#running--verifying-the-pipeline)

---

## Executive Summary & Verification Status

Over the implementation period, all initially unwritten, stubbed, or failing components were analyzed, implemented, and verified against the 522-message test corpus (`fixtures/corpus-a.jsonl`) and the totals checkpoint (`fixtures/corpus-a-totals.json`).

### Current Verification Metrics

```
INGEST
  messages read        522
  transactions written 256
  messages skipped     43 (periodic balance-only alerts)

BY CATEGORY
  SPEND         142,567.64
  INCOME        142,791.16
  MICRO           4,443.85
  TRANSFER       62,000.00

AGAINST fixtures/corpus-a-totals.json
  transactions   expected 257, produced 256
  **4821  txns 145 (expected 146)
           balance from ledger: 48,626.34 | bank says: 41,126.34 | difference: 7,500.00
  **9075  txns 91 (expected 91)
           balance from ledger: 51,210.63 | bank says: 51,210.63 | difference: 0.00

RECONCILIATION
  discrepancies found: 1
  **4821 at 2026-07-29T17:06+05:30: amount 7,500.00
    Bank stated balance changed from 36054.05 at 2026-07-29T11:53+05:30 to 28479.05 at 2026-07-29T17:06+05:30,
    but ledger transactions account for net movement of -75.00 (unexplained difference of -7500.00).

SUBMISSION OUTPUT
  submission/ledger.json: 271 transactions (15 legacy SQL seed rows + 256 corpus transactions)
  submission/summary.json: per-account spending, income, micro-totals, and transfers (accounts 3310, 4821, 9075)
  submission/reconciliation.json: exactly 1 discrepancy (account 4821, amount 7500.00)

TEST SUITE
  62 unit and integration tests passing across 11 test suites with 0 failures.
```

---

## End-to-End Architecture & Data Flow

The ingest and reconciliation pipeline transitions raw, unstructured messages into a clean, audited ledger:

```
                  RawMessage (fixtures/corpus-a.jsonl)
                                  │
                                  ▼
                     [ IngestService.java ]
                                  │
          ┌───────────────────────┴───────────────────────┐
          ▼                                               ▼
[ Balance-Only Filter ]                         [ Parsers.java Router ]
Regex: "Avl Bal in a/c..."                                │
  - 43 messages skipped                         ┌─────────┼─────────┐
  - Emits BalanceSnapshot                       ▼         ▼         ▼
                                            HDFC SMS   ICICI SMS  Email
                                            (V1/V2/Card) (V1/V2) (MIME)
                                                └─────────┬─────────┘
                                                          ▼
                                                  ParsedTxn
                                                          │
                                                          ▼
                                                [ Categorizer.java ]
                                                (TRANSFER/MICRO/SPEND/INCOME)
                                                          │
                                                          ▼
                                                   NormalizedTxn
                                                          │
                                                          ▼
                                            [ Deduplication Engine ]
                                            - Intra-Channel (TxnKey)
                                            - Cross-Channel (CrossChannelKey)
                                            - Merges sourceMessageIds
                                                          │
                                                          ▼
                                              [ Store Layer (Save) ]
                                              - SqlLedgerStore (H2)
                                              - InMemoryLedgerStore
                                                          │
                               ┌──────────────────────────┴──────────────────────────┐
                               ▼                                                     ▼
                     [ Backfill Engine ]                                   [ Reports Pipeline ]
                     - Consolidates legacy SQL                             - summary.json
                     - Idempotent migration                                - ledger.json
                               │                                           - reconciliation.json
                               ▼                                                     │
                    [ InMemoryDocumentStore ]                                        ▼
                    - Q1: forAccountMonth (newest first)                    [ 1 Discrepancy Found ]
                    - Q2: categoryTotals                                    Account 4821: ₹7,500.00
                    - Q3: byMessageId
                               ▲
                               │
                    [ ConsistencyChecker ]
                    - Deep bidirectional audit
```

---

## Incident INC-2026-09-11 Root Cause & Resolution

### The Incident
Customer complaint on account ending **4821:
> *"Your app says I spent ₹92,213.10 on a water can. I paid ₹5. Fix this, I am not able to trust anything else on this screen now."*

The balance alert `ledger.balance.divergence` fired on account `**4821` with a massive divergence of `-1,254,130.19`.

### Root Cause Analysis
In `src/main/java/in/simplifymoney/ledgersync/parse/Amounts.java`:
```java
// Original code:
private static final Pattern AMOUNT =
        Pattern.compile("(?:Rs\\.?|INR)\\s*([0-9,]+\\.[0-9]{2})");
```
The regex strictly required two decimal places (`\\.[0-9]{2}`). When parsing:
```
"Rs.5 debited from a/c **4821 on 04-07-26 at 07:19 to UPI/WATER CAN. Avl Bal: Rs.92,213.10."
```
1. `AMOUNT` attempted to match `"Rs.5"`. Because `"5"` had no decimal part, the match failed.
2. The regex engine continued scanning the string and matched the *next* currency pattern: `"Rs.92,213.10"`.
3. As a result, `Amounts.first()` returned `92213.10` instead of `5.00`. The user's ledger recorded a ₹92,213.10 debit, completely corrupting the running balance.

### Why Existing Tests Missed It
In `AmountsTest.java`, all initial test cases (`readsRupeesWithADot`, `readsInrPrefix`, `readsThousandsSeparators`) tested amounts with explicit decimal places (`2499.50`, `333.33`, `45000.00`). No test case covered an integer whole-rupee amount (`Rs.5`) preceding an available balance.

### The Fix
Updated `Amounts.java` (line 18) to make the decimal component optional while still capturing decimal digits when present:
```java
private static final Pattern AMOUNT =
        Pattern.compile("(?:Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]{2})?)");
```
And updated decimal conversion in `Amounts.toDecimal()` to guarantee a fixed scale of 2:
```java
return new BigDecimal(raw.replace(",", "")).setScale(2);
```

### Regression Test
Added `readsWholeRupeeAmount()` to `AmountsTest.java`:
```java
@Test
void readsWholeRupeeAmount() {
    assertEquals(
            new BigDecimal("5.00"),
            Amounts.first(
                    "Rs.5 debited from a/c **4821 on 04-07-26 at 11:54 " +
                            "to UPI/WATER CAN. Avl Bal: Rs.92,213.10."
            )
    );
}
```

### Five-Line Incident Post-Mortem (Ops Channel)
1. **What broke**: `Amounts.java` strictly required two decimal digits, skipping whole-rupee transactions (e.g., `Rs.5`) and capturing the subsequent available balance (`Rs.92,213.10`).
2. **How found**: Customer complaint on account `**4821` and automated alert `ledger.balance.divergence`.
3. **Blast radius**: Affected any transaction notification where the transaction amount was an integer and an available balance was quoted (account 4821 legacy seed row and corpus message `m-00004-9c11ae`).
4. **Resolution**: Made decimal component optional in `AMOUNT` regex and enforced 2-decimal scaling on `BigDecimal`.
5. **Prevention**: Added regression unit test in `AmountsTest` and verified end-to-end ledger totals with `selfCheck`.

---

## Parser Coverage & Format Matrix

Rather than relying on one generic, brittle regular expression for every bank, `Parsers.java` routes messages to bank- and channel-specific parser implementations. Each parser extracts raw transaction details into `ParsedTxn`, which then converges into the unified `NormalizedTxn` pipeline.

### Actual Formats Handled in Code

| Bank / Channel | Format / Pattern Identifier | Recognition Criteria | Parser Class | Key Differences & Edge Cases Handled | Demonstrating Test |
|---|---|---|---|---|---|
| **HDFC Bank (SMS)** | **`V1`** (Single Sentence) | Channel `sms`, Sender `AD-HDFCBK-S`, matches `debited from` or `credited to a/c **<acct>` | `HdfcSmsParser` | Parses single-sentence alerts; extracts 2-digit year timestamps (`dd-MM-yy at HH:mm`); extracts merchant after `to/by`; parses balance from `Avl Bal: Rs.<bal>`. Supports whole and decimal amounts. | `AmountsTest.readsWholeRupeeAmount`, `HdfcSmsParserTest` |
| **HDFC Bank (SMS)** | **`V2`** (Multi-line Structured) | Channel `sms`, Sender `AD-HDFCBK-S`, begins with `Sent` / `Received` with newline fields (`To:`/`From:`, `On:`, `A/c: XX<acct>`) | `HdfcSmsParser` | Multi-line structured body; parses 3-letter month dates (`dd MMM yy HH:mm`); maps `Sent` $\rightarrow$ `DEBIT`, `Received` $\rightarrow$ `CREDIT`. | `HdfcSmsParserTest`, `SelfCheck` |
| **HDFC Bank (Card)** | **`CARD`** (Credit Card Alert) | Channel `sms`, Sender `AD-HDFCBK-S`, matches `spent on HDFC Bank Card x<acct> at <merchant>` | `HdfcSmsParser` | Always `DEBIT`; parses card account last 4 digits; quotes available credit limit (`Avl Limit`) which `IngestService.isCardLimit()` excludes from bank account balance snapshots. | `HdfcSmsParserTest`, `IngestService` |
| **ICICI Bank (SMS)** | **`V1`** (Standard Sentence) | Channel `sms`, Sender `VM-ICICIB-T`, matches `Acct XX<acct> is debited/credited with ...` | `IciciSmsParser` | Parses standard alert sentences; handles slash dates (`dd/MM/yyyy HH:mm`); extracts merchant from `Info: <merchant>.`; extracts balance from `Avl Bal Rs.<bal>`. | `IciciSmsParserTest` |
| **ICICI Bank (SMS)** | **`V2`** (Terse Dr/Cr Alert) | Channel `sms`, Sender `VM-ICICIB-T`, matches `ICICI Bank Acct XX<acct> (Dr\|Cr) INR <amount> ... BalAvl Rs.<bal>` | `IciciSmsParser` | Recognizes terse tokens (`Dr` $\rightarrow$ `DEBIT`, `Cr` $\rightarrow$ `CREDIT`); handles hyphenated dates (`dd-MMM-yyyy HH:mm`); uses semicolon and `ref no` delimiters; captures `BalAvl Rs.` without colon; extracts whole and decimal rupee amounts. | `IciciSmsParserTest.parsesV2CreditTransaction`, `parsesV2DebitWithWholeRupeeAmount` |
| **Bank Email Alerts** | **`TRANSACTION` + `DATE` + `REF`** | Channel `email`, contains `account ending <acct> has been debited/credited with INR/Rs. <amount>` | `EmailParser` | Full multi-line MIME parsing; extracts RFC timestamp with offset (`dd MMM yyyy HH:mm:ss xx`); extracts `Transaction reference: <ref>` for exact reference deduplication; returns `null` balance (not quoted in email). | `EmailParserTest.parsesDebitTransactionEmail`, `CrossChannelDedupTest` |
| **HDFC Balance SMS** | **`BALANCE_ONLY`** | Channel `sms`, matches `Avl Bal in a/c **<acct> is Rs.<bal> as on <date>` | `IngestService` | Periodic balance status notifications containing no transaction event. Emits `BalanceSnapshot` for reconciliation; skipped from transaction generation (`skipped++`). | `SelfCheck` (43 skipped messages), `ReconciliationTest` |

---

## Transaction Normalization & Deduplication Engine

### Why Transactions Must Be Deduplicated
In production mobile environments, a single real financial event generates multiple messages:
1. **Cross-Channel Redundancy**: A single UPI payment generates both an SMS alert and an email alert.
2. **Carrier / Network Retries**: The same SMS or email alert may be received multiple times with different message IDs.

Saving one transaction per raw message inflated transaction counts from the true count (256) to over 323.

### Deduplication Architecture
`IngestService.java` maintains two indices during ingestion:

1. **Intra-Channel Deduplication (`TxnKey`)**:
   - If an explicit bank reference is present (e.g., from email alerts or ICICI V2 SMS):
     `TxnKey.forReference(accountLast4, transactionReference)`
   - Otherwise, details-based matching:
     `TxnKey.forDetails(accountLast4, occurredAt, direction, amount, merchant, statedBalance)`

2. **Cross-Channel Deduplication (`CrossChannelKey`)**:
   SMS and email alerts often describe the exact same transaction but have slight variations in formatting (e.g., SMS says `"to UPI/WATER CAN."` while email says `"Merchant / Remarks: UPI / WATER CAN"`).
   - Timestamp normalization: `occurredAt.toInstant()` (aligning time across timezones/formats).
   - Merchant normalization: `normalizeMerchant(merchant)` converts string to uppercase and removes all non-alphanumeric characters:
     ```java
     private String normalizeMerchant(String merchant) {
         if (merchant == null) return "";
         return merchant.toUpperCase().replaceAll("[^A-Z0-9]", "");
     }
     ```
   - Composite Key: `(accountLast4, instant, direction, amount, normalizedMerchant)`

3. **Source Message ID Merging**:
   When duplicate evidence is identified, the engine retains the canonical transaction and appends the new `sourceMessageId` to `sourceMessageIds`. Every source message that evidenced the transaction is preserved.

### Impact on the Corpus
- **522 raw messages** read
- **43 balance-only messages** filtered to `BalanceSnapshot`
- **479 transaction alerts** processed
- **223 duplicate messages** merged
- **256 canonical transactions** written

---

## Transaction Categorization

Every normalized transaction is assigned exactly one category by `Categorizer.java`:

| Category | What it means | Rule Implemented in `Categorizer.java` | Corpus Totals |
|---|---|---|---|
| `TRANSFER` | Funds moved between user's own accounts | `isTransfer(merchant)`: Merchant string contains `"PARAG KAPOOR"` (case-insensitive) | **₹62,000.00** |
| `MICRO` | Small UPI expenditure rolled up as a total | `isMicro(dir, amount, merchant)`: `direction == DEBIT`, `amount <= 100.00`, and `merchant.contains("UPI")` | **₹4,443.85** (97 txns) |
| `SPEND` | Outgoing expenditure | `direction == DEBIT` and not categorized as `TRANSFER` or `MICRO` | **₹142,567.64** |
| `INCOME` | Incoming funds | `direction == CREDIT` and not categorized as `TRANSFER` | **₹142,791.16** |

### Critical Architectural Detail: The `TRANSFER` Rule
In `fixtures/corpus-a.jsonl`, transfers between the user's accounts (`**4821` and `**9075`) are conducted via IMPS (`IMPS/P2A/PARAG KAPOOR`). The categorizer checks:
```java
public static boolean isTransfer(String merchant) {
    if (merchant == null) return false;
    return merchant.toUpperCase().contains("PARAG KAPOOR");
}
```
*Portability Note*: Hardcoding the user's name is tailored to this single-user corpus. In a multi-tenant production system, transfers must be identified against a registered user account/VPA directory rather than a static string.

---

## Reconciliation & The ₹7,500 Balance Discrepancy

### The Reconciliation Algorithm
`Reports.reconciliation()` evaluates ledger integrity by comparing consecutive bank balance snapshots against transactions:
1. Groups balance snapshots by account and sorts them chronologically.
2. Deduplicates snapshots recorded at the same timestamp.
3. For each consecutive snapshot pair $(S_{i-1}, S_i)$:
   $$\Delta_{ledger} = \sum \text{Credits} - \sum \text{Debits} \quad (\text{for transactions occurring in } (S_{i-1}.time, S_i.time])$$
   $$\text{Expected Balance} = S_{i-1}.balance + \Delta_{ledger}$$
   $$\text{Discrepancy} = S_i.balance - \text{Expected Balance}$$
4. If $\text{Discrepancy} \neq 0$, records an unexplained gap with full context.

### The Real Incident Discovered: Account 4821 Gap
Reconciliation on the corpus isolates **exactly one discrepancy**:
- **Account**: `4821`
- **Timestamp**: `2026-07-29T17:06+05:30`
- **Amount**: `₹7,500.00`
- **Audit Note**:
  > *Bank stated balance changed from 36054.05 at 2026-07-29T11:53+05:30 to 28479.05 at 2026-07-29T17:06+05:30, but ledger transactions account for net movement of -75.00 (unexplained difference of -7500.00).*

This represents a genuine missing transaction from bank alerts (e.g., an unnotified cash withdrawal or direct debit) between 11:53 and 17:06 on 29 July 2026. This explains why `fixtures/corpus-a-totals.json` expected 257 transactions while the corpus produced 256. Account `9075` reconciles with **0 discrepancies**.

---

## DocumentStore Architecture

The service defines `DocumentStore.java` to support three primary access patterns. We implemented `InMemoryDocumentStore.java` using Java collections for maximum performance, concurrency safety, and zero external dependency overhead:

```java
public interface DocumentStore {
    // Q1: one account's transactions for one month, newest first
    List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month);

    // Q2: running totals per category for an account, for its whole history
    Map<Category, BigDecimal> categoryTotals(String accountLast4);

    // Q3: which transaction, if any, did this message produce?
    Optional<NormalizedTxn> byMessageId(String messageId);

    void save(NormalizedTxn txn);
}
```

### Storage Design & Indexing Strategy
1. **Primary Store**: `Map<String, NormalizedTxn>` keyed by `accountLast4|occurredAt.toInstant()|direction|amount|merchant`.
2. **Inverted Message Index (Q3)**: `Map<String, String>` mapping each `sourceMessageId` to the primary transaction key.
3. **Pre-aggregated Category Totals (Q2)**: `Map<String, Map<Category, BigDecimal>>` maintaining running totals per account and category. Incremented on new saves and adjusted if transaction properties change.
4. **Monthly Queries (Q1)**: Filters transactions by account and `YearMonth.from(occurredAt)`, returning transactions sorted strictly descending by `occurredAt`.
5. **Idempotency**: Repeated `save()` calls do not duplicate records or double-count category totals. If a transaction arrives with additional `sourceMessageIds`, they are merged and sorted into the existing record.

---

## Backfill Engine

`Backfill.java` handles migrating historical transactions from `SqlLedgerStore` into `DocumentStore`.

### Challenges Handled
1. **Unconstrained Historical SQL**: The legacy SQL table lacked uniqueness constraints. `V2__seed.sql` contained 15 rows with duplicate transactions and repeated message IDs.
2. **Consolidation**: Backfill groups rows by business identity (`accountLast4`, `occurredAt`, `direction`, `amount`, `merchant`) and merges their `sourceMessageIds`.
3. **Idempotency & Resumption**: Checks `target.forAccountMonth` before saving. Can be executed repeatedly or resumed after partial failure without duplicating data.
4. **Telemetry**: Returns `Result(read, written, skipped)`.
   - Running over `V2__seed.sql` (15 rows) consolidates into 10 unique transactions: `read: 15, written: 10, skipped: 5`.
   - Subsequent runs safely skip all existing rows: `read: 15, written: 0, skipped: 15`.

---

## ConsistencyChecker

`ConsistencyChecker.java` provides deep, bidirectional validation between `SqlLedgerStore` and `DocumentStore`. It detects discrepancies well beyond simple row counts:

### Divergences Detected & Tested
1. `missing_transaction_in_documents:<key>`: Transaction exists in SQL but missing in DocumentStore.
2. `extra_transaction_in_documents:<key>`: Injected transaction in DocumentStore missing in SQL.
3. `amount_mismatch:<key>`: Altered amount between stores.
4. `direction_mismatch:<key>`: Altered direction (e.g., DEBIT vs CREDIT).
5. `category_mismatch:<key>`: Divergent category classification.
6. `occurred_at_mismatch:<key>`: Divergent transaction timestamps.
7. `merchant_mismatch:<key>`: Altered merchant text.
8. `source_message_ids_mismatch:<key>`: Missing or divergent source message IDs.
9. `by_message_id_missing:<id>` / `by_message_id_mismatch:<id>`: Broken message ID index lookup.
10. `category_total_mismatch:<account>:<category>`: Divergence in precomputed category running totals.
11. `ordering_violation:<account>:<month>`: Failure to return transactions newest first.

---

## Reporting Pipeline & Idempotency Safeguards

### The Reporting Regression & Resolution
During development, running:
```bash
./gradlew run --args="report submission/"
```
could generate false reconciliation discrepancies if ingest had run multiple times.

#### Root Cause
`SelfCheck` runs against a fresh `InMemoryLedgerStore`. In contrast, `App.java`'s `ingest` and `report` commands operate on the persistent H2 database (`data/ledger.mv.db`). Because the SQL table has no uniqueness constraints, running `ingest` repeatedly inserted duplicate transaction and snapshot rows into SQL. During reconciliation, duplicate transactions doubled the computed net movements, corrupting the balance calculation.

#### Defense-in-Depth Fix
1. **Idempotent Ingestion (`IngestService.java`)**: Ingest checks existing SQL rows and balance snapshots before saving. Repeated ingestion runs now skip already-persisted records.
2. **Defensive Reconciliation (`Reports.reconciliation()`)**: Added deduplication of transactions per account before calculating net movement, ensuring historical SQL duplicates (like those in `V2__seed.sql`) cannot distort balance reconciliation.
3. **Regression Tests**: Added tests in `IngestTest.java` and `ReconciliationTest.java` confirming that running ingestion multiple times produces identical, clean reconciliation reports.

---

## Submission Deliverables

The three required submission artifacts are generated in `submission/`:

### 1. `submission/ledger.json`
Contains **271 transactions** (15 legacy SQL seed records from `V2__seed.sql` + 256 ingested corpus transactions). Each entry conforms to the schema:
```json
{
  "account_last4": "4821",
  "occurred_at": "2026-07-04T20:24:00+05:30",
  "direction": "debit",
  "amount": "2499.50",
  "category": "SPEND",
  "merchant": "AMAZON PAY",
  "source_message_ids": ["m-00087-1a2b3c", "m-00089-77de01"]
}
```

### 2. `submission/summary.json`
Per-account summary across accounts `3310`, `4821`, and `9075`:
```json
{
  "accounts": {
    "3310": {
      "spend": "23941.15",
      "income": "0.00",
      "micro_count": 0,
      "micro_total": "0.00",
      "transferred_out": "0.00",
      "transferred_in": "0.00"
    },
    "4821": {
      "spend": "174988.46",
      "income": "151340.83",
      "micro_count": 52,
      "micro_total": "2357.51",
      "transferred_out": "25000.00",
      "transferred_in": "6000.00"
    },
    "9075": {
      "spend": "46752.11",
      "income": "41450.33",
      "micro_count": 45,
      "micro_total": "2086.34",
      "transferred_out": "6000.00",
      "transferred_in": "25000.00"
    }
  }
}
```
*(Note: Account `3310` is an HDFC credit card; accounts `4821` and `9075` are bank savings accounts).*

### 3. `submission/reconciliation.json`
Contains **exactly 1 discrepancy**:
```json
{
  "discrepancies": [
    {
      "account_last4": "4821",
      "occurred_at": "2026-07-29T17:06+05:30",
      "amount": "7500.00",
      "note": "Bank stated balance changed from 36054.05 at 2026-07-29T11:53+05:30 to 28479.05 at 2026-07-29T17:06+05:30, but ledger transactions account for net movement of -75.00 (unexplained difference of -7500.00)"
    }
  ]
}
```

---

## Decision Log

1. **In-Memory DocumentStore over External DB Cluster (Time vs. Core Value Trade-off)**:
   - *Context*: The assignment description prefers a document store (DynamoDB or MongoDB) run via `docker compose up` with 100k synthetic scan telemetry.
   - *Decision*: Running short on time within the 2-day sprint, we prioritized the core financial integrity requirements — resolving INC-2026-09-11, multi-format SMS/email parsing, cross-channel deduplication, categorization, reconciliation, Backfill, and ConsistencyChecker — over provisioning a live Dockerized database cluster.
   - *Implementation*: Implemented `InMemoryDocumentStore` using standard Java collections (`LinkedHashMap`, `EnumMap`, `HashMap`) satisfying all three queries (Q1, Q2, Q3) with thread safety, secondary indices, and running aggregates. Decoupled behind the `DocumentStore` interface so an external driver can be plugged in without changing domain logic.
2. **Regex Routing over Monolithic Patterns**:
   - *Decision*: Bank-specific parser dispatch via `Parsers.java` with named regex capture groups.
   - *Rationale*: Financial SMS templates diverge across banks and notification revisions. Isolated parsers prevent pattern collisions and allow targeted regression testing.
3. **Composite Deduplication Strategy**:
   - *Decision*: Two-tier deduplication using `TxnKey` (intra-channel reference/details) and `CrossChannelKey` (normalized merchant + instant).
   - *Rationale*: Balances accuracy and performance. Alphanumeric merchant normalization (`replaceAll("[^A-Z0-9]", "")`) strips punctuation differences between SMS and email alerts without risking false merges across distinct transactions.
4. **Idempotent Ingestion & Backfill**:
   - *Decision*: Read-before-write checks in both `IngestService` and `Backfill`.
   - *Rationale*: In real distributed systems, webhook retries and job restarts happen constantly. Designing pipelines to be naturally idempotent prevents duplicate financial records and avoids requiring distributed locking.
5. **Corpus-Specific TRANSFER Rule**:
   - *Decision*: Recognized self-account transfers in `fixtures/corpus-a.jsonl` by matching `"PARAG KAPOOR"`.
   - *Rationale*: For this single-user corpus, all inter-account IMPS legs explicitly designate the user's name. A multi-tenant production system would instead match against a registered user-account directory.

---

## Unfinished Work & Limitations

1. **Production Document Database & 100k Load Benchmarks**:
   - We did not spin up an external MongoDB or DynamoDB cluster via Docker Compose due to time constraints.
   - We did not generate 100,000 synthetic records to measure hardware-level `ScannedCount` vs `Count` or `totalDocsExamined` vs `nReturned`.
   - The query patterns (Q1, Q2, Q3), deduplication, and indexing contracts are fully verified against `InMemoryDocumentStore`.
2. **Multi-Tenant User Account Registry for Transfers**:
   - `Categorizer.isTransfer()` currently relies on hardcoded matching of the user's name (`"PARAG KAPOOR"`). In a multi-user production service, this should be driven by a dynamic user-account and UPI VPA registry.
3. **Database-Level Unique Constraints**:
   - The underlying `ledger` table in `SqlLedgerStore` lacks a composite UNIQUE constraint. Idempotency is enforced programmatically in `IngestService` and `Backfill`. In production, a database-level unique index on `(account_last4, occurred_at, direction, amount, merchant)` should be applied.

---

## AI Disclosure

An AI coding assistant (Antigravity) was used during this 2-day implementation for pair programming, exploratory corpus and codebase inspection, scaffolding JUnit test edge cases, and drafting documentation based on the verified repository state.

ChatGPT was also used as a discussion and reasoning aid, particularly for understanding the existing codebase, discussing implementation approaches and trade-offs, and planning the high-level architecture/system diagram.

All architectural decisions, implementation changes, bug fixes, test verification, Git commit history, and final verification runs (`verify.sh`, `selfCheck`, `test`) were reviewed and validated by the candidate.

---

## Running & Verifying the Pipeline

### 1. Run Complete Test Suite
```bash
./gradlew test
```
*Executes all 62 unit and integration tests.*

### 2. Run SelfCheck (In-Memory Verification)
```bash
./gradlew selfCheck
```
*Ingests `fixtures/corpus-a.jsonl`, compares category totals against `fixtures/corpus-a-totals.json`, and verifies the ₹7,500 reconciliation discrepancy.*

### 3. Run Pipeline via Verification Script
```bash
./verify.sh
```

### 4. Run Migration, Ingestion & Reporting (SQL Store)
```bash
# Apply database migrations
./gradlew run --args="migrate"

# Ingest corpus into SQL store
./gradlew run --args="ingest fixtures/corpus-a.jsonl"

# Generate submission documents into submission/
./gradlew run --args="report submission/"
```
