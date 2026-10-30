# Placement Tracker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A local Windows app that reads the owner's Gmail and university Outlook inboxes, uses Claude to recognise placement-application emails, and keeps one up-to-date record per application, with a to-do list, a "Please check" queue, a board, and Windows notifications.

**Architecture:** One Spring Boot process with a system-tray icon. A scheduled sync pulls mail from `MailSource` implementations (Gmail, Outlook *or* forwarded-to-Gmail), a conservative local pre-filter drops obvious noise, Claude classifies the rest into a typed `Classification`, and an `EmailProcessor` applies it to SQLite using pure, unit-tested rules (`StageRules`, `ApplicationMatcher`). A JSON API on `127.0.0.1` feeds a plain HTML/CSS/JS page that reproduces the approved mockup.

**Tech Stack:** Java 21 (JDK 23 installed), Spring Boot (current release from start.spring.io) with Web, JDBC (`JdbcClient`), Flyway, Validation, and the Maven wrapper; `org.xerial:sqlite-jdbc`; `com.anthropic:anthropic-java` (3.2.0 or newer); Google API client + `google-api-services-gmail` + `google-oauth-client-jetty`; `com.microsoft.graph:microsoft-graph` 6.x + `com.azure:azure-identity`; `net.java.dev.jna:jna-platform` (Windows DPAPI); `org.jsoup:jsoup`; JUnit 5, AssertJ, Mockito. Look up current versions and API details with context7 or microsoft-docs before writing each integration.

**Spec:** `docs/superpowers/specs/2026-10-29-placement-tracker-design.md`. Read it before starting any task. Section references (§) below point into it.

## Global Constraints

- Read-only mail access: Gmail scope `https://www.googleapis.com/auth/gmail.readonly`; Graph delegated `Mail.Read` (plus `offline_access`). Never send, delete, move or mark mail read.
- Web server binds to `127.0.0.1` only, port `8765` (`server.address=127.0.0.1`, `server.port=8765`).
- Mutating API requests (POST/PATCH/DELETE) must carry header `X-Tracker: 1`. Any request whose `Host` header isn't `127.0.0.1:8765` or `localhost:8765` gets `403`. No CORS. (Stops other websites and DNS rebinding from driving the local API.)
- All runtime data lives in the data dir: `%LOCALAPPDATA%\PlacementTracker` by default, overridable with property `tracker.data-dir`. That includes `tracker.db`, OAuth token stores, `google-client-secret.json`, `config.properties` and `logs/`. Nothing runtime goes in the repo.
- OAuth tokens are encrypted at rest. Gmail: DPAPI-encrypted file store (Task 10). Outlook: azure-identity persistent token cache, which uses DPAPI on Windows (Task 11A).
- Anthropic API key comes from env `ANTHROPIC_API_KEY`, else `anthropic.api-key` in `<data dir>\config.properties`.
- Model: `claude-haiku-5-5` by default (property `tracker.ai.model`). Structured output via the SDK's class-based `outputConfig(Classification.class)`. `maxTokens` 4096, since adaptive thinking is on by default on Haiku 5.5. No `temperature`. Always check `stop_reason` before reading content.
- Backfill start: `2026-06-01T00:00:00Z` (`tracker.sync.backfill-from`). Sync interval default `20` minutes (`tracker.sync.interval-minutes`, allowed 15–30).
- AI confidence threshold `0.7` (`tracker.ai.confidence-threshold`). An email whose AI call fails `3` times (`tracker.ai.max-attempts`) goes to Please check.
- Reminders at 3 days and 1 day before an action's due time. A date-only deadline means 23:59 `Europe/London` that day.
- Folders scanned: everything except Sent, Drafts and Trash/Deleted Items, including Spam/Junk (§5.3).
- `.gitignore` must cover `target/`, `.superpowers/`, `*.db`, `config.properties`, `google-client-secret.json`, `tokens/`, `src/test/resources/eval/private/`, `.env`.
- Package root `com.placementtracker`, organised by feature (`application`, `mail`, `classify`, `matching`, `processing`, `review`, `sync`, `notify`, `web`, `config`).
- Commands below assume Git Bash in the repo root. In PowerShell use `.\mvnw.cmd` instead of `./mvnw`.

## Review Focus

1. **The same email reaches both inboxes** (CC'd to Gmail and the uni address, or forwarded): it must be processed once and create one application. Owner: Task 9 (`sync_skipsDuplicateAcrossAccounts`).
2. **Company name variants and ATS senders** ("Northwind Bank plc" vs "Northwind Bank", sender `myworkday.com`): these must match one application. A genuinely ambiguous match goes to Please check and never creates a silent duplicate. Owner: Task 6 (`legalSuffixesIgnored`, `twoOpenSameCompanyDifferentRolesIsAmbiguous`).
3. **The app wasn't running when a reminder was due** (laptop asleep past the 3-day mark): on start, send exactly one reminder (the 1-day one if within a day). A restart must never resend. Owner: Task 14 (`lateStartSendsOnlyOneDayReminder`, `remindersNotResentAfterRestart`).
4. **Auth expires mid-backfill** (Google testing-mode tokens expire after about 7 days): the account shows "needs reconnecting", the high-water mark doesn't move past unfetched mail, and the next successful run catches up exactly once. Owner: Task 9 (`authFailureKeepsHighWaterAndMarksReconnect`).
5. **Claude fails for one email** (429/5xx, refusal, malformed output): other emails still process, that email is retried on later runs, and after 3 failures it lands in Please check. It's never silently marked "not placement". Owner: Tasks 5 and 9 (`refusalThrowsClassificationException`, `aiFailureRetriedThenReviewed`).

---

### Task 0: Outlook access check (manual, with the owner; throwaway)

Decides whether Task 11A (Graph) or 11B (forwarding) gets built. No product code.

**Files:**
- Create: `docs/decisions/outlook-access.md`

- [ ] **Step 1: Register an Entra app.** With the owner: at entra.microsoft.com (a free Azure account works), create an App registration named "Placement Tracker". Supported account types: *Accounts in any organizational directory and personal Microsoft accounts*. Platform: *Mobile and desktop applications*, redirect URI `http://localhost`. API permissions: Microsoft Graph, delegated, `Mail.Read` and `offline_access`. Note the Application (client) ID.
- [ ] **Step 2: Test consent with the university account.** The owner opens
  `https://login.microsoftonline.com/organizations/oauth2/v2.0/authorize?client_id=<CLIENT_ID>&response_type=code&redirect_uri=http%3A%2F%2Flocalhost&scope=offline_access%20https%3A%2F%2Fgraph.microsoft.com%2FMail.Read&prompt=consent`
  and signs in with the university account.
  - **Allowed:** a consent screen with *Accept*. After accepting, the browser lands on a `http://localhost/?code=…` page that fails to load, which is fine.
  - **Blocked:** "Need admin approval", or an `AADSTS65001`/`AADSTS90094`-style error.
- [ ] **Step 3: Record the result** in `docs/decisions/outlook-access.md`: the date, the outcome, the client ID (not secret), and the chosen path (11A if allowed, 11B if blocked). If blocked, the owner sets up auto-forwarding of the uni mailbox to Gmail in Outlook on the web. If the university also blocks forwarding, stop and raise it with the owner.

---

### Task 1: Project scaffold, configuration and database

**Files:**
- Create (generated): `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/`, `src/main/java/com/placementtracker/PlacementTrackerApplication.java`
- Create: `.gitignore`, `src/main/resources/application.yml`, `src/main/java/com/placementtracker/config/TrackerProperties.java`, `src/main/java/com/placementtracker/config/DataDir.java`, `src/main/resources/db/migration/V1__init.sql`
- Test: `src/test/java/com/placementtracker/config/DataDirTest.java`, `src/test/java/com/placementtracker/SchemaTest.java`

**Interfaces:**
- Produces: `TrackerProperties` (`@ConfigurationProperties("tracker")`) with `Path dataDir()`, `Sync sync()` → `int intervalMinutes()`, `Instant backfillFrom()`; `Ai ai()` → `String model()`, `double confidenceThreshold()`, `int maxAttempts()`; `Forwarding forwarding()` → `String fromAddress()` (nullable). `DataDir` bean: `Path root()`, `Path resolve(String name)`, creating the directory on startup. Schema below.

- [ ] **Step 1: Generate and initialise.** Download a starter from start.spring.io (`type=maven-project`, `javaVersion=21`, `groupId=com.placementtracker`, `artifactId=placement-tracker`, `packageName=com.placementtracker`, `dependencies=web,jdbc,flyway,validation`) and unzip it into the repo root (alongside the existing `docs/`). Add `sqlite-jdbc` to the POM. If Flyway reports SQLite as unsupported, add the matching `flyway-database-*` module. Run `git init`, write `.gitignore` per Global Constraints, then commit the spec and the scaffold.
- [ ] **Step 2: Start the app non-headless.** In `main`, use `new SpringApplicationBuilder(PlacementTrackerApplication.class).headless(false).run(args)`. This is needed for the tray in Task 14.
- [ ] **Step 3: Write failing tests.**

```java
// DataDirTest
@Test void defaultsToLocalAppData() { /* tracker.data-dir unset → ends with "PlacementTracker" under %LOCALAPPDATA% */ }
@Test void overrideIsUsedAndCreated(@TempDir Path tmp) { /* tracker.data-dir=tmp/x → root()==tmp/x and Files.isDirectory */ }

// SchemaTest (@SpringBootTest with tracker.data-dir=@TempDir)
@Test void migrationCreatesAllTables() {
  assertThat(tableNames()).contains("applications","field_locks","actions","emails",
      "stage_history","review_items","sync_state","notifications_sent");
}
```

- [ ] **Step 4: Run and confirm they fail.** Run `./mvnw -q test -Dtest='DataDirTest,SchemaTest'`. Expected: FAIL (classes and tables missing).
- [ ] **Step 5: Implement.** `spring.datasource.url=jdbc:sqlite:${tracker.data-dir}/tracker.db` (resolved through `DataDir`; a `DataSource` bean is fine). Also set `server.address`, `server.port` and the `tracker.*` defaults from Global Constraints. `V1__init.sql`:
  - `applications(id INTEGER PK, company TEXT NOT NULL, company_key TEXT NOT NULL, role TEXT, stage TEXT NOT NULL, interview_rounds INTEGER NOT NULL DEFAULT 0, applied_on TEXT, notes TEXT, created_at TEXT, updated_at TEXT)`
  - `field_locks(application_id INTEGER, field TEXT, PRIMARY KEY(application_id, field))`
  - `actions(id INTEGER PK, application_id INTEGER NOT NULL, description TEXT NOT NULL, due_at TEXT, status TEXT NOT NULL, stage TEXT NOT NULL, source_email_id INTEGER, created_at TEXT)`, with status `OPEN|DONE|CLOSED`
  - `emails(id INTEGER PK, account TEXT, message_id TEXT, thread_id TEXT, dedupe_key TEXT, from_address TEXT, subject TEXT, received_at TEXT, web_link TEXT, application_id INTEGER, status TEXT, attempts INTEGER DEFAULT 0, last_error TEXT, UNIQUE(account, message_id))`, with an index on `dedupe_key` and status `PENDING|FILTERED|NOT_PLACEMENT|PROCESSED|REVIEW|FAILED|DUPLICATE`
  - `stage_history(id INTEGER PK, application_id INTEGER, from_stage TEXT, to_stage TEXT, changed_at TEXT, source TEXT, email_id INTEGER)`, with source `EMAIL|USER`
  - `review_items(id INTEGER PK, email_id INTEGER, application_id INTEGER, reason TEXT, suggestion_json TEXT, status TEXT, created_at TEXT)`
  - `sync_state(account TEXT PK, high_water TEXT, last_success_at TEXT, status TEXT, last_error TEXT)`
  - `notifications_sent(key TEXT PK, sent_at TEXT)`
- [ ] **Step 6: Run the tests.** Same command. Expected: PASS. Also run `./mvnw -q spring-boot:run`, open `http://127.0.0.1:8765` (a 404 page is fine), then stop it.
- [ ] **Step 7: Commit.** Message: `feat: scaffold Spring Boot app with SQLite schema and data dir`.

---

### Task 2: Stages and stage rules

**Files:**
- Create: `src/main/java/com/placementtracker/application/Stage.java`, `.../application/StageRules.java`, `.../application/TransitionDecision.java`, `.../review/ReviewReason.java`
- Test: `src/test/java/com/placementtracker/application/StageRulesTest.java`

**Interfaces:**
- Produces:
  - `enum Stage { NOT_SUBMITTED, APPLIED, ONLINE_TEST, INTERVIEW, ASSESSMENT_CENTRE, OFFER, REJECTED, WITHDRAWN }` with `boolean isClosed()` (OFFER/REJECTED/WITHDRAWN), `boolean isMidProcess()` (ONLINE_TEST/INTERVIEW/ASSESSMENT_CENTRE) and `boolean onBoard()` (everything except NOT_SUBMITTED).
  - `enum ReviewReason { LOW_CONFIDENCE, AMBIGUOUS_MATCH, REGRESSION, CLOSED_CHANGE, EDITED_FIELD, AI_FAILED }`
  - `record TransitionDecision(Kind kind, ReviewReason reason)` with `enum Kind { APPLY, NO_CHANGE, REVIEW }`
  - `final class StageRules { static TransitionDecision decide(Stage current, Stage proposed, boolean stageLocked) }`

- [ ] **Step 1: Write failing tests** (one per §5.5 rule, in this precedence order):

```java
@Test void sameStageIsNoChange()            { assertKind(INTERVIEW, INTERVIEW, false, NO_CHANGE); }
@Test void lockedStageChangeGoesToReview()  { assertReview(INTERVIEW, REJECTED, true, EDITED_FIELD); }
@Test void notSubmittedCanMoveAnywhere()    { assertKind(NOT_SUBMITTED, APPLIED, false, APPLY); }
@Test void midProcessMovesFreely()          { assertKind(ASSESSMENT_CENTRE, INTERVIEW, false, APPLY);
                                              assertKind(INTERVIEW, ONLINE_TEST, false, APPLY); }
@Test void backToAppliedIsRegression()      { assertReview(ONLINE_TEST, APPLIED, false, REGRESSION); }
@Test void backToNotSubmittedIsRegression() { assertReview(APPLIED, NOT_SUBMITTED, false, REGRESSION); }
@Test void anyChangeFromClosedIsReviewed()  { assertReview(OFFER, WITHDRAWN, false, CLOSED_CHANGE);
                                              assertReview(REJECTED, OFFER, false, CLOSED_CHANGE);
                                              assertReview(REJECTED, INTERVIEW, false, CLOSED_CHANGE); }
@Test void activeToClosedApplies()          { assertKind(APPLIED, REJECTED, false, APPLY); }
```

- [ ] **Step 2: Run and confirm they fail.** Run `./mvnw -q test -Dtest=StageRulesTest`. Expected: FAIL (compile errors).
- [ ] **Step 3: Implement `StageRules.decide`.** Check in this order: same stage → NO_CHANGE; locked → REVIEW(EDITED_FIELD); current closed → REVIEW(CLOSED_CHANGE); proposed NOT_SUBMITTED, or proposed APPLIED while current is mid-process → REVIEW(REGRESSION); otherwise APPLY.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: `feat: stage model and transition rules`.

---

### Task 3: Repositories

**Files:**
- Create: `.../application/Application.java`, `.../application/ApplicationAction.java`, `.../application/StageChange.java`, `.../application/ApplicationRepository.java`, `.../application/ActionRepository.java`, `.../application/HistoryRepository.java`, `.../mail/EmailRecord.java`, `.../mail/EmailRepository.java`, `.../review/ReviewItem.java`, `.../review/ReviewRepository.java`, `.../sync/SyncState.java`, `.../sync/SyncStateRepository.java`, `.../notify/NotificationLog.java`
- Test: `src/test/java/com/placementtracker/application/ApplicationRepositoryTest.java`, `.../mail/EmailRepositoryTest.java`, `.../notify/NotificationLogTest.java`

**Interfaces:**
- Produces (all `@Repository`, using Spring `JdbcClient`, timestamps as ISO-8601 UTC text):
  - `record Application(long id, String company, String companyKey, String role, Stage stage, int interviewRounds, LocalDate appliedOn, String notes, Set<String> lockedFields)`
  - `ApplicationRepository`: `long insert(String company, String companyKey, String role, Stage stage, LocalDate appliedOn, String notes)`, `Optional<Application> find(long id)`, `List<Application> findByCompanyKey(String key)`, `List<Application> findAll()`, `void updateStage(long id, Stage stage)`, `void incrementInterviewRounds(long id)`, `void updateFields(long id, Map<String,Object> fields)` (company/role/notes/appliedOn; recomputes `company_key` when company changes), `void lock(long id, String field)`, `Optional<Long> findByThreadId(String account, String threadId)` (via `emails.application_id`), `void mergeInto(long targetId, long sourceId)` (moves emails/actions/history, deletes the source)
  - `record ApplicationAction(long id, long applicationId, String description, OffsetDateTime dueAt, Status status, Stage stage)` with `enum Status { OPEN, DONE, CLOSED }`
  - `ActionRepository`: `long insert(long appId, String description, OffsetDateTime dueAt, Stage stage, Long sourceEmailId)`, `boolean existsOpen(long appId, String description, OffsetDateTime dueAt)`, `List<ApplicationAction> findOpen()`, `List<ApplicationAction> findForApplication(long appId)`, `void markDone(long id)`, `void closeOpenForStage(long appId, Stage stage)`, `void closeAllOpen(long appId)`
  - `HistoryRepository`: `void record(long appId, Stage from, Stage to, String source, Long emailId)`, `List<StageChange> forApplication(long appId)`
  - `enum EmailStatus { PENDING, FILTERED, NOT_PLACEMENT, PROCESSED, REVIEW, FAILED, DUPLICATE }`; `record EmailRecord(long id, MailAccount account, String messageId, String threadId, String dedupeKey, String from, String subject, Instant receivedAt, String webLink, Long applicationId, EmailStatus status, int attempts)`
  - `EmailRepository`: `Optional<EmailRecord> insertIfNew(MailMessage m)` (empty when `(account,message_id)` already exists), `boolean existsDedupeKey(String key, long excludingId)`, `void setStatus(long id, EmailStatus s)`, `void linkApplication(long id, long appId)`, `int recordFailure(long id, String error)` (increments and returns attempts), `List<EmailRecord> findRetryable(int maxAttempts)` (status FAILED with attempts < max), `Optional<EmailRecord> find(long id)`
  - `ReviewRepository`: `long open(Long emailId, Long appId, ReviewReason reason, String suggestionJson)`, `List<ReviewItem> findOpen()`, `Optional<ReviewItem> find(long id)`, `void resolve(long id)`
  - `SyncStateRepository`: `SyncState get(MailAccount a)`, `void markSuccess(MailAccount a, Instant highWater, Instant at)`, `void markError(MailAccount a, SyncStatus s, String error)`, where `enum SyncStatus { OK, ERROR, NEEDS_RECONNECT, NOT_CONNECTED }`
  - `NotificationLog`: `boolean markIfNew(String key)` (true only the first time a key is seen)
  - `MailMessage` and `MailAccount` are defined in Task 4. Create them here first if Task 4 hasn't run yet; the signatures are fixed there.

- [ ] **Step 1: Write failing tests.** Run against a temp-dir SQLite (`@SpringBootTest` or `@JdbcTest` with Flyway):

```java
@Test void insertAndFindRoundTrips()
@Test void lockedFieldsAreLoaded()            // lock(id,"stage") → find(id).lockedFields() contains "stage"
@Test void updateCompanyRecomputesKey()
@Test void mergeMovesEmailsActionsHistory()
@Test void insertIfNewIgnoresSameMessageId()  // second insert → Optional.empty()
@Test void retryableExcludesMaxedAttempts()   // attempts 3 with max 3 → not returned
@Test void markIfNewIsTrueOnce()              // NotificationLog: true, then false
```

- [ ] **Step 2: Run and confirm they fail.** Run `./mvnw -q test -Dtest='ApplicationRepositoryTest,EmailRepositoryTest,NotificationLogTest'`. Expected: FAIL.
- [ ] **Step 3: Implement the repositories.** `company_key` comes from `CompanyNames.key(...)` (Task 6). Until Task 6 exists, add a minimal `CompanyNames.key` that lowercases and trims; Task 6 replaces its body.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: `feat: JDBC repositories for applications, actions, emails, review, sync state`.

---

### Task 4: Mail model and pre-filter

**Files:**
- Create: `.../mail/MailAccount.java`, `.../mail/MailMessage.java`, `.../mail/MailSource.java`, `.../mail/MailAuthException.java`, `.../mail/MailSourceException.java`, `.../mail/PreFilter.java`, `.../mail/DedupeKeys.java`
- Test: `src/test/java/com/placementtracker/mail/PreFilterTest.java`, `.../mail/DedupeKeysTest.java`

**Interfaces:**
- Produces:
  - `enum MailAccount { GMAIL, OUTLOOK }`
  - `record MailMessage(MailAccount account, String messageId, String threadId, String internetMessageId, String from, String subject, Instant receivedAt, String bodyText, String webLink, Set<String> labels)`. `labels` holds normalised hints such as `SPAM`, `PROMOTIONS`, `SOCIAL`.
  - `interface MailSource { MailAccount account(); boolean isConnected(); void connect() throws MailAuthException; List<MailMessage> fetchSince(Instant since) throws MailAuthException, MailSourceException; Optional<MailMessage> fetchOne(String messageId) throws MailAuthException, MailSourceException; }`. `fetchSince` returns results ordered by `receivedAt` ascending. `fetchOne` is used to retry failed emails.
  - `PreFilter`: `boolean shouldClassify(MailMessage m)`
  - `DedupeKeys`: `static String of(MailMessage m)`. Uses `internetMessageId` when present. Otherwise it's a SHA-256 of the normalised sender address + subject (minus `Fwd:`/`FW:`/`Re:` prefixes) + `receivedAt` truncated to 10 minutes.

- [ ] **Step 1: Write failing tests.** The pre-filter must be conservative (§5.4):

```java
@Test void keywordInSubjectPasses()          // "Your application to Northwind Bank" → true
@Test void atsSenderPassesWithoutKeyword()   // from "noreply@myworkday.com", subject "Update" → true
@Test void keywordInBodyPasses()             // body contains "online assessment" → true
@Test void spamWithKeywordStillPasses()      // labels {SPAM}, subject "Interview invitation" → true
@Test void socialWithoutKeywordFiltered()    // labels {SOCIAL}, "X liked your post" → false
@Test void promoWithoutKeywordFiltered()     // labels {PROMOTIONS}, "50% off trainers" → false
@Test void plainEmailWithoutSignalFiltered() // "Lunch tomorrow?" → false

// DedupeKeysTest
@Test void sameInternetMessageIdSameKey()
@Test void forwardedPrefixIgnored()          // "Fwd: Interview invite" == "Interview invite", same sender, 3 min apart
```

- [ ] **Step 2: Run and confirm they fail.** Run `./mvnw -q test -Dtest='PreFilterTest,DedupeKeysTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.** `shouldClassify` returns true if the subject or the first 4,000 body characters contain a keyword (case-insensitive): `application, applied, apply, placement, internship, intern, industrial, year in industry, assessment, online test, test invitation, interview, assessment centre, candidate, offer, recruit, talent, hiring, careers, vacancy, position, role, shortlist, next steps, unfortunately, thank you for your interest, invitation`. It also returns true if the sender domain ends with one of: `myworkday.com, workday.com, successfactors.com, successfactors.eu, oraclecloud.com, taleo.net, greenhouse.io, lever.co, smartrecruiters.com, icims.com, hirevue.com, tal.net, eightfold.ai, avature.net, brightnetwork.co.uk, gradcracker.com, ratemyplacement.co.uk, higherin.com, linkedin.com, indeed.com, targetjobs.co.uk, prospects.ac.uk`, or with a domain from `tracker.prefilter.extra-domains`. Put the lists in `application.yml` so they can be tuned without a code change. Otherwise it returns false.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: `feat: mail model, conservative pre-filter, dedupe keys`.

---

### Task 5: Claude classifier

**Files:**
- Create: `.../classify/Classification.java`, `.../classify/SuggestedAction.java`, `.../classify/Relevance.java`, `.../classify/EmailClassifier.java`, `.../classify/ClaudeEmailClassifier.java`, `.../classify/ClassificationException.java`, `.../classify/AnthropicConfig.java`, `src/main/resources/prompts/classify-system.txt`
- Test: `src/test/java/com/placementtracker/classify/ClaudeEmailClassifierTest.java`, `.../classify/ClassificationTest.java`

**Interfaces:**
- Consumes: `MailMessage` (Task 4), `Stage` (Task 2), `TrackerProperties.ai()` (Task 1).
- Produces:
  - `enum Relevance { YES, NO, UNSURE }`
  - `record SuggestedAction(String description, String dueAt)`, where `dueAt` is an ISO-8601 date or date-time with offset, or null.
  - `record Classification(Relevance relevance, String company, String role, Stage stage, List<SuggestedAction> actions, double confidence, String reason)`, with Jackson `@JsonPropertyDescription` on every field so the SDK-derived schema documents itself. It has a method `OffsetDateTime dueAtOf(SuggestedAction a)`, where a date-only value becomes 23:59 `Europe/London`.
  - `interface EmailClassifier { Classification classify(MailMessage m) throws ClassificationException; }`
  - `ClaudeEmailClassifier implements EmailClassifier`. It takes `AnthropicClient` (bean from `AnthropicConfig`, using the key lookup from Global Constraints) and `TrackerProperties`.

- [ ] **Step 1: Write failing tests.** Mock the `AnthropicClient` / messages service with Mockito, or wrap the SDK call behind a small package-private `ClaudeCall` functional interface and fake that:

```java
@Test void sendsSenderSubjectDateAndBodyOnly()     // captured request user message contains from/subject/date/body; no attachments
@Test void usesConfiguredModel()                   // params model == "claude-haiku-5-5"
@Test void returnsTypedClassification()            // fake returns structured Classification → passed through
@Test void refusalThrowsClassificationException()  // stop_reason "refusal" → ClassificationException
@Test void apiErrorThrowsClassificationException() // SDK RateLimitException / InternalServerException → ClassificationException(retryable=true)
@Test void bodyTruncatedTo20kChars()

// ClassificationTest
@Test void dateOnlyDueIsEndOfDayLondon()           // "2026-11-07" → 2026-11-07T23:59+00:00 (GMT in November)
@Test void dateTimeWithOffsetKept()                // "2026-10-30T17:00+01:00" unchanged
```

- [ ] **Step 2: Run and confirm they fail.** Run `./mvnw -q test -Dtest='ClaudeEmailClassifierTest,ClassificationTest'`. Expected: FAIL.
- [ ] **Step 3: Write the system prompt** in `classify-system.txt`. It encodes the owner's rules from §5.4, and must say:
  - Only placement / year-in-industry and internship applications count. Full-time graduate jobs and part-time jobs are `NO`.
  - Job alerts, "jobs you might like", general recruiter outreach not tied to a specific role, careers-fair and event invites, and newsletters are `NO`.
  - A recruiter or agency putting the owner forward for a specific placement role is `YES`.
  - "Finish your application" reminders are `YES` with stage `NOT_SUBMITTED` and the action "Finish your <company> application".
  - Use the stage the email implies. `UNSURE` (not `NO`) whenever in doubt.
  - Extract every action with a deadline/event time and its timezone if stated.
  - `company` is the hiring company, not the ATS vendor or job board (e.g. "Northwind Bank", never "Workday").
- [ ] **Step 4: Implement `classify`.** Build the request with `MessageCreateParams.builder().model(props.ai().model()).maxTokens(4096L).system(prompt).outputConfig(Classification.class).addUserMessage(renderEmail(m))` (structured-output builder per the Java SDK docs). Check `stopReason` (refusal or `max_tokens` → throw). Map SDK exceptions to `ClassificationException` with a `retryable` flag: 429/5xx/connection → retryable; 4xx → not retryable, still counted as a failed attempt.
- [ ] **Step 5: Run the tests.** Expected: PASS.
- [ ] **Step 6: Commit.** Message: `feat: Claude email classifier with structured output`.

---

### Task 6: Application matching

**Files:**
- Create: `.../matching/CompanyNames.java` (replaces the Task 3 stub body), `.../matching/ApplicationMatcher.java`, `.../matching/MatchResult.java`
- Test: `src/test/java/com/placementtracker/matching/CompanyNamesTest.java`, `.../matching/ApplicationMatcherTest.java`

**Interfaces:**
- Consumes: `Application`, `ApplicationRepository.findByCompanyKey`, `findByThreadId` (Task 3); `Classification` (Task 5).
- Produces:
  - `CompanyNames.key(String name)`
  - `sealed interface MatchResult permits Existing, New, Ambiguous` with `record Existing(long applicationId)`, `record New()`, `record Ambiguous(List<Long> candidateIds)`
  - `ApplicationMatcher.match(Classification c, MailMessage m)`

- [ ] **Step 1: Write failing tests.**

```java
// CompanyNamesTest
@Test void legalSuffixesIgnored()  // "Northwind Bank plc", "Northwind Bank Ltd", "The Northwind Bank" → same key
@Test void punctuationAndCaseIgnored()  // "Brightline-Energy UK" == "brightline energy"

// ApplicationMatcherTest
@Test void threadAlreadyLinkedWins()                       // thread linked to app 7 → Existing(7) even if company text differs
@Test void noApplicationForCompanyIsNew()
@Test void singleOpenSameCompanyMatchesWhenRoleBlankOrSimilar()  // "Technology Placement" vs "Technology Placement 2027" → Existing
@Test void twoOpenSameCompanyDifferentRolesIsAmbiguous()   // role doesn't clearly match either → Ambiguous([a,b])
@Test void singleOpenDifferentRoleIsAmbiguous()            // "Data Placement" vs "HR Placement" → Ambiguous
@Test void closedOnlyApplicationsAreConsideredButAmbiguous() // only a REJECTED app at company, new email → Ambiguous (could be a new round)
@Test void missingCompanyIsAmbiguous()                     // c.company() blank → Ambiguous(List.of())
```

- [ ] **Step 2: Run and confirm they fail.** Run `./mvnw -q test -Dtest='CompanyNamesTest,ApplicationMatcherTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.** `key`: lowercase, strip punctuation, drop a leading "the" and trailing tokens from {plc, ltd, limited, llp, inc, llc, group, uk, holdings}, then collapse spaces. Role similarity: token Jaccard ≥ 0.5 after lowercasing and dropping {placement, internship, intern, year, industrial, the, and, 2026, 2027}. A blank role on either side counts as similar. Order: thread link → company candidates → rules above.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: `feat: company normalisation and application matching`.

---

### Task 7: Email processor

**Files:**
- Create: `.../processing/EmailProcessor.java`, `.../processing/ProcessOutcome.java`
- Test: `src/test/java/com/placementtracker/processing/EmailProcessorTest.java`

**Interfaces:**
- Consumes: Tasks 2, 3, 5 and 6.
- Produces: `ProcessOutcome process(EmailRecord email, MailMessage m, Classification c)` (`@Transactional`), returning `enum ProcessOutcome { NOT_PLACEMENT, CREATED, UPDATED, REVIEW }`. It also exposes `List<Long> newActionIds()`, which returns and clears the actions created since the last call (used by notifications in Task 14).

- [ ] **Step 1: Write failing tests.** Use a real temp DB and real repositories; only the classification is hand-built.

```java
@Test void noRelevanceMarksNotPlacement()
@Test void unsureGoesToReviewLowConfidence()
@Test void lowConfidenceYesGoesToReview()          // 0.6 < 0.7
@Test void newApplicationCreatedWithHistoryAndActions()  // stage ONLINE_TEST, 1 action due → app, history(null→ONLINE_TEST, EMAIL), OPEN action, email linked
@Test void notSubmittedCreatesEntryWithFinishAction()
@Test void forwardMoveClosesPreviousStageActions() // ONLINE_TEST w/ OPEN action → INTERVIEW: old action CLOSED, new stage, history row
@Test void interviewInviteIncrementsRounds()       // INTERVIEW → INTERVIEW with a new action → interviewRounds 1→2, NO stage history row
@Test void regressionGoesToReviewAndChangesNothing()
@Test void lockedStageChangeBecomesSuggestion()    // review EDITED_FIELD, suggestion_json contains proposed stage, app unchanged
@Test void closingStageClosesAllOpenActions()
@Test void duplicateActionNotAddedTwice()          // same description+due from a second email
@Test void ambiguousMatchGoesToReview()            // suggestion_json lists candidate ids
@Test void emailAlwaysLinkedWhenApplicationKnown() // even for REVIEW decisions on an existing app
```

- [ ] **Step 2: Run and confirm they fail.** Run `./mvnw -q test -Dtest=EmailProcessorTest`. Expected: FAIL.
- [ ] **Step 3: Implement `process`.** It follows §5.4–§5.7 using `StageRules` and `ApplicationMatcher`. `suggestion_json` is the serialised `Classification` plus `candidateIds` and `applicationId` when relevant. Interview rounds: the first entry into INTERVIEW sets 1. A further INTERVIEW classification that brings a new action increments by 1. Actions are only added to non-closed applications.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: `feat: apply classifications to applications with stage rules`.

---

### Task 8: Please-check (review) service

**Files:**
- Create: `.../review/ReviewService.java`
- Test: `src/test/java/com/placementtracker/review/ReviewServiceTest.java`

**Interfaces:**
- Consumes: `ReviewRepository`, `EmailProcessor`, the repositories in Task 3.
- Produces (each resolves the item):
  - `void confirm(long reviewId, Classification edited)`, where `edited` may be the original suggestion. It applies it to the matched or new application while bypassing the confidence check and stage locks (the owner is deciding). On a confirmed lock suggestion, the field stays locked with the new value.
  - `void dismissNotPlacement(long reviewId)`, which sets the email to NOT_PLACEMENT.
  - `void attachTo(long reviewId, long applicationId)`, which links the email and applies the suggestion's stage/actions to that application through `StageRules` with `stageLocked=false`. Regressions still need an explicit `confirm`.

- [ ] **Step 1: Write failing tests.**

```java
@Test void confirmCreatesApplicationFromLowConfidenceItem()
@Test void confirmAppliesRegressionWhenOwnerAccepts()   // REGRESSION item confirmed → stage moved back, history source USER
@Test void confirmEditedFieldSuggestionUpdatesLockedStage()
@Test void dismissMarksEmailNotPlacement()
@Test void attachLinksEmailAndAddsActions()
@Test void resolvedItemsLeaveOpenList()
```

- [ ] **Step 2: Run and confirm they fail.** Run `./mvnw -q test -Dtest=ReviewServiceTest`. Expected: FAIL.
- [ ] **Step 3: Implement.** Put the "apply without gates" path in `EmailProcessor` as `applyConfirmed(EmailRecord, Classification, Long applicationId)` so both services share one write path.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: `feat: please-check review actions`.

---

### Task 9: Sync service and scheduler

**Files:**
- Create: `.../sync/SyncService.java`, `.../sync/SyncScheduler.java`, `.../sync/SyncReport.java`
- Test: `src/test/java/com/placementtracker/sync/SyncServiceTest.java`

**Interfaces:**
- Consumes: `List<MailSource>` beans (fakes in tests), `PreFilter`, `EmailClassifier`, `EmailProcessor`, `EmailRepository`, `SyncStateRepository`, `ReviewRepository`, `DedupeKeys`.
- Produces:
  - `SyncReport syncAll()`. It's synchronized, so a second call while one is running waits for that run and returns its report.
  - `record SyncReport(int fetched, int classified, int created, int updated, int newReviewItems, List<Long> newActionIds, Map<MailAccount,SyncStatus> statuses)`
  - `SyncScheduler` runs `syncAll` at a fixed delay of `tracker.sync.interval-minutes` with an initial delay of 1 minute, and publishes `SyncCompletedEvent(SyncReport)`.

- [ ] **Step 1: Write failing tests.** Use a `FakeMailSource` that can throw on demand, a `FakeClassifier` and a real DB.

```java
@Test void firstRunFetchesFromBackfillDate()          // fetchSince called with 2026-06-01T00:00Z
@Test void laterRunFetchesFromHighWaterMinusOneDay()  // overlap; already-seen message ids skipped via insertIfNew
@Test void filteredEmailsNeverReachClassifier()
@Test void sync_skipsDuplicateAcrossAccounts()        // same dedupe key in GMAIL and OUTLOOK → second marked DUPLICATE, one application
@Test void authFailureKeepsHighWaterAndMarksReconnect() // MailAuthException → status NEEDS_RECONNECT, high_water unchanged; next run (no throw) processes the missed mail once
@Test void aiFailureRetriedThenReviewed()             // classifier throws 3 runs in a row → attempts 3, status REVIEW, review item AI_FAILED; other emails in the same run still PROCESSED
@Test void oneSourceFailingDoesNotBlockOther()
@Test void reportCountsNewReviewItemsAndActions()
```

- [ ] **Step 2: Run and confirm they fail.** Run `./mvnw -q test -Dtest=SyncServiceTest`. Expected: FAIL.
- [ ] **Step 3: Implement `syncAll`.** For each connected source:
  1. `since = highWater == null ? backfillFrom : highWater.minus(1 day)`; `fetchSince(since)`. On `MailAuthException`, mark the source NEEDS_RECONNECT and continue to the next source. On `MailSourceException`, mark it ERROR and continue.
  2. For each message: `insertIfNew` (skip if empty). If the dedupe key exists on another email → DUPLICATE. Else if `!shouldClassify` → FILTERED. Else classify and process. A `ClassificationException` → `recordFailure`, and at max attempts → open AI_FAILED review and set status REVIEW.
  3. After the whole listing succeeds, `markSuccess(account, max receivedAt seen, now)`.
  4. After all sources, retry `findRetryable(maxAttempts)`, re-fetching each email with `MailSource.fetchOne(messageId)` and running it through step 2's classify/process path.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: `feat: sync engine with catch-up, retries and per-account status`.

---

### Task 10: Gmail source

**Files:**
- Create: `.../mail/gmail/GmailMailSource.java`, `.../mail/gmail/DpapiDataStoreFactory.java`, `.../mail/gmail/GmailMessageMapper.java`, `.../mail/HtmlToText.java`, `.../dev/DevCommands.java` (a `CommandLineRunner` holding the dev-only flags `tracker.dev.gmail-smoke`, and later `outlook-smoke` (Task 11A) and `seed` (Task 13). Each is `@ConditionalOnProperty` and does nothing unless set.)
- Test: `src/test/java/com/placementtracker/mail/gmail/GmailMessageMapperTest.java`, `.../mail/gmail/DpapiDataStoreFactoryTest.java`, `src/test/resources/gmail/*.json` (synthetic Gmail API message payloads)

**Interfaces:**
- Consumes: `MailSource` (Task 4), `DataDir`.
- Produces: the `GmailMailSource` bean (`account() == GMAIL`). `GmailMessageMapper.toMailMessage(com.google.api.services.gmail.model.Message)`. `HtmlToText.convert(String html)`.

- [ ] **Step 1: Write failing tests.**

```java
@Test void prefersTextPlainPart()
@Test void fallsBackToHtmlConvertedToText()          // multipart/alternative with html only
@Test void decodesBase64UrlBodies()
@Test void mapsCategoryLabels()                      // CATEGORY_PROMOTIONS → PROMOTIONS, SPAM → SPAM, CATEGORY_SOCIAL → SOCIAL
@Test void webLinkPointsAtMessage()                  // https://mail.google.com/mail/u/0/#all/<id>
@Test void readsInternetMessageIdHeader()
// DpapiDataStoreFactoryTest (@EnabledOnOs(WINDOWS))
@Test void roundTripsCredentialAndFileIsNotPlaintext() // stored bytes don't contain the token string
```

- [ ] **Step 2: Run and confirm they fail.** Run `./mvnw -q test -Dtest='GmailMessageMapperTest,DpapiDataStoreFactoryTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - **`DpapiDataStoreFactory extends AbstractDataStoreFactory`:** its data stores extend `AbstractMemoryDataStore`. They load by decrypting `<dataDir>/tokens/gmail/<id>.bin` with `Crypt32Util.cryptUnprotectData` and override `save()` to encrypt with `Crypt32Util.cryptProtectData`.
  - **`connect()`:** `GoogleAuthorizationCodeFlow` with client secrets from `<dataDir>/google-client-secret.json`, scope `gmail.readonly`, access type `offline`, then `AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("owner")`.
  - **`fetchSince`:** use query `after:<epochSeconds> -in:sent -in:drafts -in:trash` with `includeSpamTrash(true)`, page through `users.messages.list`, then `users.messages.get(format=FULL)` each, sorted by `internalDate`.
  - **Errors:** a `TokenResponseException` or 401 → `MailAuthException`; any other IO error → `MailSourceException`.
  - **`HtmlToText`:** uses Jsoup `.text()` with line breaks kept for `<br>`/`<p>`.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Manual check with the owner.** Walk them through: Google Cloud Console → new project → enable Gmail API → OAuth consent screen (External; add their Gmail as a test user; then **Publish app** to "In production" so refresh tokens don't expire after 7 days, accepting the unverified-app warning for personal use) → Credentials → OAuth client ID of type *Desktop app* → download it as `<dataDir>\google-client-secret.json`. Then run `./mvnw -q spring-boot:run -Dspring-boot.run.arguments=--tracker.dev.gmail-smoke=true`. That hidden dev flag connects, fetches the last 2 days and logs the subjects only, then exits. Expected: a browser consent screen, then subjects in the log.
- [ ] **Step 6: Commit.** Message: `feat: Gmail mail source with DPAPI-encrypted token store`.

---

### Task 11A: Outlook source via Microsoft Graph (only if Task 0 = allowed)

**Files:**
- Create: `.../mail/outlook/OutlookMailSource.java`, `.../mail/outlook/GraphMessageMapper.java`
- Test: `src/test/java/com/placementtracker/mail/outlook/GraphMessageMapperTest.java`

**Interfaces:**
- Consumes: `MailSource`, `DataDir`, `HtmlToText`; property `tracker.outlook.client-id` (from Task 0's decision file).
- Produces: the `OutlookMailSource` bean (`account() == OUTLOOK`), enabled by `@ConditionalOnProperty("tracker.outlook.client-id")`.

- [ ] **Step 1: Write failing tests.**

```java
@Test void mapsTextBodyAndWebLink()
@Test void junkFolderBecomesSpamLabel()
@Test void excludedFoldersAreDropped()   // parentFolderId == sentitems/drafts/deleteditems ids → filtered out by mapper helper
```

- [ ] **Step 2: Run and confirm they fail.** Run `./mvnw -q test -Dtest=GraphMessageMapperTest`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - **Credential:** `InteractiveBrowserCredential` (azure-identity) with client id, tenant `organizations`, redirect `http://localhost`, and `TokenCachePersistenceOptions` named `placement-tracker`. Persist the `AuthenticationRecord` to `<dataDir>/tokens/outlook-auth-record.json` so later runs authenticate silently. Disable automatic interactive auth during scheduled syncs: a silent failure → `MailAuthException`.
  - **Client and folders:** `GraphServiceClient(credential, "Mail.Read")`. Resolve the well-known folder ids for `sentitems`, `drafts`, `deleteditems` and `junkemail` once.
  - **`fetchSince`:** `me().messages()` with filter `receivedDateTime ge <since>`, select `id,conversationId,internetMessageId,from,subject,receivedDateTime,body,webLink,parentFolderId`, header `Prefer: outlook.body-content-type="text"`, ordered by `receivedDateTime`, iterated with `PageIterator`.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Manual check with the owner.** Use `--tracker.dev.outlook-smoke=true` (same pattern as Task 10). Expected: Microsoft sign-in, then subjects in the log.
- [ ] **Step 6: Commit.** Message: `feat: Outlook mail source via Microsoft Graph`.

### Task 11B: Forwarded university mail (only if Task 0 = blocked)

**Files:**
- Create: `.../mail/ForwardedMessageParser.java`. Modify: `GmailMessageMapper` (or a post-processing step in `GmailMailSource`).
- Test: `src/test/java/com/placementtracker/mail/ForwardedMessageParserTest.java`

**Interfaces:**
- Produces: `Optional<MailMessage> ForwardedMessageParser.unwrap(MailMessage m, String forwardingFrom)`. It returns the original sender, subject and date (body without the forward header) when `m.from()` equals `tracker.forwarding.from-address`, with `account` still `GMAIL`.

- [ ] **Step 1: Write failing tests** for an Outlook auto-forward and a manual forward:

```java
@Test void unwrapsOutlookForwardHeaderBlock()     // "From: Northwind Careers <careers@northwind.com>\nSent: ...\nSubject: ..." → from/subject/date of original
@Test void unwrapsGmailStyleForward()             // "---------- Forwarded message ---------"
@Test void ignoresMailNotFromForwardingAddress()
@Test void fallsBackToOuterFieldsWhenNoHeaderFound()  // still returns a message, so nothing is dropped
```

- [ ] **Step 2: Run and confirm they fail.** Run `./mvnw -q test -Dtest=ForwardedMessageParserTest`. Expected: FAIL.
- [ ] **Step 3: Implement** and apply it in `GmailMailSource.fetchSince`. When the owner's university forwards via a redirect that keeps the original `From`, nothing needs unwrapping. That's fine, and dedupe (Task 9) covers copies.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: `feat: unwrap forwarded university mail`.

---

### Task 12: JSON API

**Files:**
- Create: `.../web/DashboardController.java`, `.../web/ApplicationController.java`, `.../web/ReviewController.java`, `.../web/SyncController.java`, `.../web/AccountsController.java`, `.../web/LocalOnlyFilter.java`, `.../web/dto/*.java`
- Test: `src/test/java/com/placementtracker/web/*ControllerTest.java`, `.../web/LocalOnlyFilterTest.java`

**Interfaces:**
- Consumes: the repositories, `ReviewService`, `SyncService`, `List<MailSource>`, `SyncStateRepository`.
- Produces (JSON; all mutating calls require `X-Tracker: 1`):
  - `GET /api/dashboard` returns `{ accounts:[{account,status,lastSuccessAt,error}], chart:[{stage,count}], needsAction:[{actionId,applicationId,company,role,description,dueAt,urgency}], pleaseCheck:[{reviewId,reason,subject,from,receivedAt,suggestion}], board:{APPLIED:[card],ONLINE_TEST:[…],INTERVIEW:[…],ASSESSMENT_CENTRE:[…],OUTCOME:[…]} }`.
    - `chart` covers on-board stages APPLIED, ONLINE_TEST, INTERVIEW, ASSESSMENT_CENTRE, OFFER and REJECTED, with WITHDRAWN counted into REJECTED's grey "Closed" slice and labelled `CLOSED`.
    - `urgency`: `OVERDUE` (past), `URGENT` (≤1 day), `SOON` (≤3 days), `LATER`.
    - `needsAction` includes NOT_SUBMITTED apps' actions and is sorted by `dueAt`, nulls last.
    - `card = {applicationId, company, role, stage, interviewRounds, hasOpenAction, urgency}`.
  - `GET /api/applications/{id}` returns the fields, `lockedFields`, actions, and history with `{fromStage,toStage,changedAt,source,email:{subject,webLink}}`.
  - `POST /api/applications`, body `{company, role, stage, appliedOn, notes}`, history source USER.
  - `PATCH /api/applications/{id}`, with any of company/role/stage/notes/appliedOn. Each changed field is locked. A stage change records history with source USER and closes the previous stage's open actions.
  - `POST /api/applications/{id}/actions` `{description, dueAt}`; `POST /api/actions/{id}/done`.
  - `POST /api/applications/{targetId}/merge` `{sourceId}`.
  - `POST /api/review/{id}/confirm` (optional body: an edited Classification); `POST /api/review/{id}/dismiss`; `POST /api/review/{id}/attach` `{applicationId}`.
  - `POST /api/sync` returns `202`, runs `syncAll` asynchronously and returns immediately.
  - `GET /api/accounts`; `POST /api/accounts/{account}/connect` runs `MailSource.connect()` asynchronously, which opens the system browser.

- [ ] **Step 1: Write failing tests** (MockMvc):

```java
@Test void dashboardGroupsBoardAndCountsChart()
@Test void needsActionSortedByDueWithUrgency()       // fixed Clock: due in 20h → URGENT, 60h → SOON, past → OVERDUE
@Test void notSubmittedOnlyInNeedsAction()           // not in board, not in chart
@Test void patchStageLocksAndRecordsUserHistory()
@Test void markDoneRemovesFromNeedsAction()
@Test void mutatingWithoutHeaderIs403()              // LocalOnlyFilterTest
@Test void foreignHostHeaderIs403()                  // Host: evil.example → 403 even on GET
```

- [ ] **Step 2: Run and confirm they fail.** Run `./mvnw -q test -Dtest='*ControllerTest,LocalOnlyFilterTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** the controllers, DTOs (records) and `LocalOnlyFilter` (`OncePerRequestFilter`). Inject a `Clock` bean for urgency so it's testable.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: `feat: local JSON API with host and header guard`.

---

### Task 13: Web UI

**Files:**
- Create: `src/main/resources/static/index.html`, `static/app.js`, `static/styles.css`, `static/setup.html`, `static/setup.js`
- Reference: `.superpowers/brainstorm/main-screen-v2.html` (the approved mockup; it's git-ignored, so copy the look, not the file)

**Interfaces:**
- Consumes: the Task 12 API. Every `fetch` that mutates sends `X-Tracker: 1`.

- [ ] **Step 1: Build `index.html`, `app.js` and `styles.css`** to match the approved mockup (§6):
  - The top bar with "Last checked …", **Check now** and **+ Add**.
  - The top row: the "Your applications" donut (inline SVG, total in the centre, legend with counts, hover tooltip "Online test: 3 of 24 (13%)") on the left, and Needs action (orange URGENT, yellow SOON, red-outlined OVERDUE, each with a ✓ Done button) on the right.
  - The Please check list under the top row, only when non-empty. Each item has *Confirm*, *Edit & confirm*, *Not a placement email* and *Attach to…* (a dropdown of applications).
  - The board with five columns, with header swatches using the chart colours. Cards show "Interview (round 2)" when rounds > 1.
  - Clicking a card opens a details panel: editable fields (a lock icon on locked ones), actions (add/done), history with links that open the original email, and Merge into….
  - Light and dark modes come from CSS variables (reuse the mockup's tokens). The page refreshes every 60 s and after any action.
- [ ] **Step 2: Build `setup.html` and `setup.js`.** Per account: status (Connected / Needs reconnecting / Not connected), a **Connect** button, and help text pointing to the README setup steps. `index.html` links to it, and redirects there on first run when no account is connected.
- [ ] **Step 3: Check it in a browser.** Run `./mvnw -q spring-boot:run` with `--tracker.dev.seed=true`. That hidden flag inserts the mockup's made-up applications into an empty DB. Open `http://127.0.0.1:8765`. Expected: it matches the mockup layout in light and dark mode. Card click, edit, Done, Add and the Please check buttons all work, and the network tab shows `X-Tracker` on the POSTs.
- [ ] **Step 4: Commit.** Message: `feat: tracker web UI (board, chart, to-dos, please check, setup)`.

---

### Task 14: Notifications, reminders and the tray icon

**Files:**
- Create: `.../notify/Notifier.java`, `.../notify/TrayNotifier.java`, `.../notify/ReminderService.java`, `.../notify/SyncNotificationListener.java`, `.../notify/TrayIconManager.java`
- Test: `src/test/java/com/placementtracker/notify/ReminderServiceTest.java`, `.../notify/SyncNotificationListenerTest.java`

**Interfaces:**
- Consumes: `NotificationLog`, `ActionRepository`, `ApplicationRepository`, `SyncCompletedEvent` (Task 9), `SyncService`, `Clock`.
- Produces:
  - `interface Notifier { void notify(String title, String message); }`, implemented by `TrayNotifier` (`TrayIcon.displayMessage`, `MessageType.INFO`).
  - `ReminderService.run()`, scheduled every 15 min plus once at startup.
  - `TrayIconManager`, which on `ApplicationReadyEvent` adds a tray icon drawn in code (a 16×16 `BufferedImage`, so no binary asset) with the menu *Open tracker* / *Check now* / *Quit*. Clicking the icon or a notification opens `http://127.0.0.1:8765` via `Desktop.browse`. If `SystemTray.isSupported()` is false, it logs a warning and the app keeps running.

- [ ] **Step 1: Write failing tests** (fake `Notifier`, fixed `Clock`):

```java
@Test void threeDayReminderOnce()                 // due in 70h → one "3 days" notification; second run → none
@Test void oneDayReminderOnce()                   // due in 20h → one "tomorrow" notification
@Test void lateStartSendsOnlyOneDayReminder()     // first run when due in 20h → 1-day only, never the 3-day one
@Test void remindersNotResentAfterRestart()       // new ReminderService instance, same DB → nothing
@Test void doneClosedAndOverdueNeverRemind()
@Test void newActionsBatchedIntoOneNotification() // SyncCompletedEvent with 3 new action ids → 1 notification listing them
@Test void newReviewItemsBatchedIntoOneNotification() // "3 emails need checking"
@Test void noNotificationWhenNothingNew()
```

- [ ] **Step 2: Run and confirm they fail.** Run `./mvnw -q test -Dtest='ReminderServiceTest,SyncNotificationListenerTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.** The keys are `action:<id>:3d`, `action:<id>:1d` and `action:<id>:new`, all through `NotificationLog.markIfNew`. When sending the 1-day reminder, also mark `3d`. Reminder text: `"<Company>: <description> — due <EEE d MMM HH:mm>"`.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Manual check.** Run the app with `--tracker.dev.seed=true`. Expected: a tray icon appears and *Check now* triggers a sync. A seeded action due tomorrow shows a Windows notification, and clicking it opens the tracker.
- [ ] **Step 6: Commit.** Message: `feat: windows notifications, deadline reminders and tray icon`.

---

### Task 15: Classification evaluation set

**Files:**
- Create: `src/test/resources/eval/synthetic/*.json`, `src/test/java/com/placementtracker/eval/EvalCase.java`, `.../eval/ClassificationEvalTest.java`, `.../eval/PreFilterRecallTest.java`
- Note: `src/test/resources/eval/private/` is git-ignored, for any real emails the owner chooses to add.

**Interfaces:**
- Produces: `record EvalCase(String id, MailMessage email, Relevance expectedRelevance, String expectedCompany, Stage expectedStage)`, with the JSON in the same shape.

- [ ] **Step 1: Write at least 40 synthetic cases** covering every source type (career-site ATS, job board, university portal, recruiter) × every stage, including NOT_SUBMITTED "finish your application". Include at least 12 negatives: job alerts, "jobs you might like", generic recruiter outreach, a careers-fair invite, a graduate-scheme application (NO by the §5.4 rule), a newsletter, and a promo containing the word "offer". All companies are made up.
- [ ] **Step 2: Write `PreFilterRecallTest`** (runs in the normal build, no network): every case with `expectedRelevance == YES` must pass `PreFilter.shouldClassify`. Run `./mvnw -q test -Dtest=PreFilterRecallTest`. Expected: PASS. If it fails, widen the pre-filter lists in Task 4's YAML and re-run.
- [ ] **Step 3: Write `ClassificationEvalTest`.** It's tagged `eval` and excluded from the default Surefire run, and it uses the real `ClaudeEmailClassifier`. It prints a table of relevance accuracy, company accuracy (by `CompanyNames.key`) and stage accuracy, plus each miss. It fails if YES-recall is below 0.95 or stage accuracy is below 0.85. Run it with `./mvnw -q test -Peval` (add the `eval` profile that includes the tag). It needs `ANTHROPIC_API_KEY`, and at Haiku 5.5 prices ($0.10/$0.50 per million tokens) a run costs well under $0.05. Expected: PASS with the table printed. If it fails, adjust `classify-system.txt` and re-run.
- [ ] **Step 4: Commit.** Message: `test: classification eval set and pre-filter recall check`.

---

### Task 16: README, packaging and final checks

**Files:**
- Create: `README.md`, `docs/screenshots/` (screenshots taken with seeded fake data only)

- [ ] **Step 1: Write `README.md`.** Cover:
  - what it does (one paragraph and a screenshot), the architecture (a small diagram of sync → pre-filter → Claude → rules → SQLite → UI/notifications) and the tech stack;
  - setup: JDK 21+, the Google Cloud steps (Task 10), the Entra steps (Task 0/11A) or the forwarding steps (11B), the Anthropic API key (env var or `config.properties`), `./mvnw package`, then `java -jar target/placement-tracker-*.jar`;
  - expected AI cost: Haiku 5.5 at $0.10/$0.50 per million tokens, so a 1,000-email backfill costs roughly $0.30;
  - privacy: what's sent to Anthropic and that everything else stays local;
  - optional auto-start: a shortcut to a `start-tracker.bat` (`javaw -jar …`) placed in `shell:startup`, and how to remove it;
  - running the tests and the eval.
- [ ] **Step 2: Full build.** Run `./mvnw -q clean verify`. Expected: BUILD SUCCESS, all non-eval tests pass.
- [ ] **Step 3: Secret scan.** Run `git grep -n -I -E "sk-ant-|refresh_token|client_secret|BEGIN PRIVATE KEY"`. Expected: no matches outside README placeholder text. Also run `git status --ignored` and confirm that `tracker.db`, tokens and `config.properties` aren't tracked.
- [ ] **Step 4: End-to-end run with the owner's real accounts.** `java -jar target/placement-tracker-*.jar` → setup page → connect Gmail (and Outlook, or confirm forwarding) → the first sync backfills from 1 Jun 2026. The owner then checks that the applications they remember are all present (the anti-goal in §4), that stages look right, and that Please check is short and sensible. Note any misses as new eval cases (in `eval/private/` if they're real emails).
- [ ] **Step 5: Commit.** Message: `docs: README, setup guide and packaging`.
