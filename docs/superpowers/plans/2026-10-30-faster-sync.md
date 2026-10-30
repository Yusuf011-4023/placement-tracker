# Faster Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a large catch-up (first run, or after a long time offline) fast and visibly progressing, and send far fewer emails to Claude, without missing any placement email.

**Architecture:** There are three independent changes to the existing pipeline. (1) `SyncService` classifies each fetched batch before it fetches the next, and exposes live progress to the dashboard. (2) `GmailMailSource` fetches message bodies on a small thread pool, still behind the shared `RequestThrottle`. (3) `PreFilter` matches keywords as whole words and drops mail from blocked senders: generic job-alert senders in `application.yml`, plus the owner's personal list in `%LOCALAPPDATA%\PlacementTracker\blocked-senders.txt`. A read-only dev command replays stored emails through the new filter to show that no relevant email is lost.

**Tech Stack:** Java 21, Spring Boot 4.1.2, JdbcClient/SQLite, Google Gmail API client, plain JS UI. Tests use JUnit 5, AssertJ and Mockito, as now.

**Spec:** `docs/superpowers/specs/2026-10-29-placement-tracker-design.md`. Its anti-goal still binds: never miss an application or get a stage wrong. Recall beats speed.

**Evidence (owner's real data, 2026-10-30):**
- About 5,900 emails since 1 June. 2,959 reached Claude; 43 were placement-related.
- The candidate blocklist would have removed 2,031 of the 2,959 Claude calls and 0 of the 43 relevant emails.
- The fetch ran at about 0.8 messages/s, sequentially, against a 10/s throttle, so parallel fetching is the speed win.
- The board stayed empty for hours because classification waited for all 20 batches.

## Global Constraints

- Gmail stays read-only (`gmail.readonly`). No new scopes. Never send, delete or modify mail.
- Tests never touch the real `%LOCALAPPDATA%\PlacementTracker` (the test properties already set a temp `tracker.data-dir`).
- Processing order is unchanged: within a run, emails are classified and applied oldest `received_at` first. The `EmailProcessor` stale-email guard stays as is.
- A retryable classifier error (429/5xx/connection/auth/billing) never uses up an attempt. The circuit breaker opens after `BREAKER_THRESHOLD` (3) consecutive failures and then stops classification for the rest of the run.
- The high-water mark never moves past a message that was not stored.
- The UI never uses `innerHTML`; build nodes with the existing `el()` helper and `textContent`.
- No personal data in committed files. The owner's personal sender list lives only in the data dir.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- `./mvnw test` stays green (226 tests at the start of this plan).

## Review Focus

1. Several batches in one run, with a FAILED email under the attempt cap: it must be attempted at most once per run. Otherwise one run burns all 3 attempts.
2. Parallel fetch where one message in the middle fails: only the contiguous oldest prefix before the failure is returned. Otherwise the high-water mark skips the failed message forever.
3. A breaker that opens in batch 1 of a 20-batch run: later batches are still fetched and stored, nothing more is classified, and nothing is lost (all stay PENDING).
4. A keyword change that drops an email the old filter kept. `PreFilterRecallTest` (synthetic eval set) must still pass, and the replay command must report 0 relevant dropped.
5. A blocked-senders file that is missing, empty, or has comments, blank lines, mixed case or surrounding spaces: it must load or be ignored without crashing startup.

---

### Task 1: Classify each batch as it arrives, and show progress

**Files:**
- Modify: `src/main/java/com/placementtracker/sync/SyncService.java`
- Modify: `src/main/java/com/placementtracker/web/DashboardController.java`, `src/main/java/com/placementtracker/web/dto/Dtos.java`
- Modify: `src/main/resources/static/app.js` (and `styles.css` only if needed)
- Test: `src/test/java/com/placementtracker/sync/SyncServiceTest.java`, plus the dashboard controller test that builds `Dashboard`

**Interfaces:**
- Produces: `public record SyncProgress(int downloaded, int checked)` in `com.placementtracker.sync`. Also `public Optional<SyncProgress> SyncService.progress()`, which is present only while a pass runs. `downloaded` = messages fetched this run; `checked` = emails classified this run.
- Produces: the `Dtos.Dashboard` record gains `Progress progress` (nullable), where `public record Progress(int downloaded, int checked)`. The JSON is `"progress": {"downloaded": n, "checked": n}` or `null`.

- [ ] **Step 1: Write the failing tests** in `SyncServiceTest`:
  - `classifiesEachBatchBeforeFetchingNext`: a fake source returns two full batches (`batchLimit()` = 2, then a short one). Record the order of `fetchSince` and `classify` calls. Assert both batch-1 emails are classified before the second `fetchSince` call.
  - `retryableFailedEmailAttemptedOncePerRun`: a stored FAILED email with attempts 0 (cap 3), a classifier that throws a non-retryable `ClassificationException` for it, and a source returning three batches. After `syncAll()`, its attempts == 1.
  - `breakerOpenStillStoresLaterBatches`: the classifier always throws a retryable exception, and three batches of 2 come back. After the run, all 6 emails are stored, the classifier was called exactly `BREAKER_THRESHOLD` times, and none of the 6 is FAILED with attempts > 0.
  - `progressVisibleOnlyWhileRunning`: inside the classifier stub, capture `syncService.progress()`. Assert it is present with `downloaded >= 1`, and that after `syncAll()` returns it is `Optional.empty()`.
  - The existing tests (including `backlogThenKeyAppliesInDateOrder`) keep passing unchanged.
- [ ] **Step 2: Run** `./mvnw test -Dtest=SyncServiceTest`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement.** In `fetchAndStore`, after each batch is stored and the mark saved, call `processWaiting(run)` before fetching the next batch. After all sources, `runPass` still calls `processWaiting(run)` once. That covers the backlog when nothing new was fetched, or a source that is not connected.
  - Keep a run-wide `Set<Long> attempted` in `Run`. `processWaiting` skips any id already in it and adds each id it handles or skips. A message that could not be re-fetched is added too, so it isn't retried every batch.
  - After each `processWaiting`, clear `run.fetchedMessages`, so at most one batch of bodies is held in memory.
  - `Run` counters read by `progress()` (`fetched`, `classified`) must be safely visible across threads, for example as `volatile` fields or `AtomicInteger`. `SyncService` holds a `volatile Run current`, set at the start of `runPass` and cleared in `syncAll`'s `finally`.
  - Log one INFO line per batch: `"{}: batch {} stored {} messages; {} checked by AI so far this run"`.
- [ ] **Step 4: Dashboard.** `DashboardController` passes `syncService.progress().map(p -> new Progress(p.downloaded(), p.checked())).orElse(null)`. Update every `new Dashboard(...)` call site, including tests.
- [ ] **Step 5: UI.** In `app.js` `renderTopBar`, while `dashboard.syncing`, the button stays "Checking…". Next to `#last-checked`, show the text `Checking your emails: {downloaded} downloaded · {checked} checked by AI` when `dashboard.progress` is not null; otherwise keep the existing "Last checked …" text. Format numbers with `toLocaleString()`. The board already re-renders on each poll while syncing, so new applications appear during the run.
- [ ] **Step 6: Run** `./mvnw test`. Expected: all green.
- [ ] **Step 7: Commit** `feat: classify each fetched batch immediately and show sync progress`.

### Task 2: Parallel Gmail body fetch

**Files:**
- Modify: `src/main/java/com/placementtracker/mail/gmail/GmailMailSource.java`
- Modify: `src/main/java/com/placementtracker/config/TrackerProperties.java` (Gmail record), `src/main/resources/application.yml`
- Test: `src/test/java/com/placementtracker/mail/gmail/GmailFetchTest.java` (and `GmailMailSourceTest` if constructors change)

**Interfaces:**
- Produces: `TrackerProperties.Gmail` gains `int fetchThreads`, with `DEFAULT_FETCH_THREADS = 8` used when the value is missing or non-positive, in the same pattern as the existing defaults. The YAML gets `tracker.gmail.fetch-threads: 8` with a comment: "bodies fetched at once; the throttle still caps the total rate".
- Produces: package-visible `List<MailMessage> fetchAll(Gmail gmail, List<String> ids) throws IOException` in `GmailMailSource`. It fetches the ids concurrently and returns the messages for the longest contiguous prefix of `ids` that all succeeded, in `ids` order, with 404s skipped (a 404 does not break the prefix). If the first non-404 failure is at index 0, it throws that `IOException`. Task 3 uses it.
- Produces: `public List<MailMessage> fetchMany(List<String> messageIds) throws MailAuthException, MailSourceException`. This is the public wrapper over `fetchAll`, with the same error translation as `fetchOne`. It is for the Task 3 replay command, not part of `MailSource`.

- [ ] **Step 1: Write the failing tests** in `GmailFetchTest`, with whatever fake transport or `Gmail` stub that test already uses:
  - `fetchesBodiesConcurrently`: 8 ids, where each `get` waits on a shared `CountDownLatch(4)` (counted down on entry, awaited with a 2 s timeout). The fetch finishes well under 8 × that timeout, which proves at least 4 ran at once. Results come back oldest first.
  - `middleFailureKeepsOnlyOlderPrefix`: ids 1..5 oldest first, and `get` of id 3 returns HTTP 500 (after any back-off). The result is exactly messages 1 and 2.
  - `notFoundDoesNotBreakPrefix`: id 2 returns 404. The result is 1, 3, 4, 5.
  - `firstFailureThrows`: id 1 returns 500, so `fetchSince` throws `MailSourceException`.
  - `authFailureAnywhereThrowsAuth`: id 4 returns 401, so `MailAuthException`.
- [ ] **Step 2: Run** `./mvnw test -Dtest=GmailFetchTest`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement.** `fetchSince` builds the list of ids to fetch: oldest first, skipping `alreadyStored`, at most `batchLimit`. It then calls `fetchAll`, which uses a fixed pool of `fetchThreads` threads created per call and closed in `finally`. Each task does `throttle.acquire()` then `get(...)`. An auth-type failure (as `translate` classifies it) at any index is rethrown as `MailAuthException`. Keep the existing `log.warn` when a partial prefix is returned. `fetchOne` stays sequential.
- [ ] **Step 4: Run** `./mvnw test`. Expected: all green.
- [ ] **Step 5: Commit** `perf: fetch Gmail message bodies in parallel behind the shared throttle`.

### Task 3: Tighter pre-filter, blocked senders and replay check

**Files:**
- Modify: `src/main/java/com/placementtracker/mail/PreFilter.java`, `src/main/java/com/placementtracker/config/PreFilterProperties.java`, `src/main/resources/application.yml`
- Modify: `src/main/java/com/placementtracker/dev/DevCommands.java`, `README.md`
- Test: `src/test/java/com/placementtracker/mail/PreFilterTest.java`, `PreFilterBindingTest.java` (the existing `PreFilterRecallTest` must stay green untouched)

**Interfaces:**
- Consumes: `GmailMailSource.fetchMany(List<String>)` from Task 2; `DataDir` (`dataDir.resolve("blocked-senders.txt")`).
- Produces: `PreFilterProperties` gains `List<String> blockedSenders` (null becomes an empty list). `PreFilter` gets the constructor `PreFilter(PreFilterProperties, DataDir)`. Keep a constructor or factory that tests can use without a data dir, if that is simpler.

**Rules (in order):**
1. **Blocked senders.** Each entry is trimmed and lower-cased; blank lines and lines starting with `#` are ignored.
   - An entry containing `@` blocks that exact sender address.
   - Any other entry is a domain, and blocks a sender whose domain equals it or ends with `"." + entry`.
   - A blocked sender returns `false` before the keyword and ATS checks, even for an ATS domain such as `linkedin.com`.
   - Entries come from `tracker.prefilter.blocked-senders` plus `<data dir>/blocked-senders.txt`. A missing file means no extra entries; an unreadable file logs a WARN and adds none.
2. **Whole-word keywords.** A keyword matches only when it is not directly preceded or followed by a letter or digit (case-insensitive), with a trailing `*` meaning "any word ending". So `role` no longer matches "control", `intern` no longer matches "international", and `recruit*` matches "recruiter". Multi-word keywords match as phrases.
3. **ATS domains** are unchanged.

- [ ] **Step 1: Update `application.yml`.** Change the keyword list to this, in this order: `application*`, `applied`, `apply`, `applying`, `placement*`, `internship*`, `intern`, `interns`, `industrial`, `year in industry`, `assessment*`, `online test`, `test invitation`, `interview*`, `assessment centre`, `candidate*`, `offer`, `recruit*`, `talent`, `hiring`, `career*`, `vacancy`, `vacancies`, `position`, `role`, `shortlist*`, `next steps`, `unfortunately`, `thank you for your interest`, `invitation*`. Add `blocked-senders` (generic job-alert and newsletter senders only), with the comment "job alerts and newsletters, never applications; personal additions go in blocked-senders.txt in the data folder":
  `jobalerts-noreply@linkedin.com`, `newsletters-noreply@linkedin.com`, `invitations@linkedin.com`, `updates-noreply@linkedin.com`, `em.linkedin.com`, `jobalert.indeed.com`, `match.indeed.com`.
- [ ] **Step 2: Write the failing tests** in `PreFilterTest`:
  - `roleDoesNotMatchInsideControl`: subject "Take control of your energy bill", unknown domain → false.
  - `recruitPrefixMatchesRecruiter`: body "A recruiter will be in touch", unknown domain → true.
  - `internDoesNotMatchInternational`: subject "International shipping now free" → false.
  - `blockedAddressBeatsAtsDomain`: from `LinkedIn <jobalerts-noreply@linkedin.com>`, subject "New internship roles for you" → false.
  - `jobsNoreplyLinkedinStillPasses`: from `jobs-noreply@linkedin.com`, subject "Your application was sent to Capgemini" → true.
  - `blockedDomainCoversSubdomains`: entry `match.indeed.com` blocks `donotreply@match.indeed.com` but not `indeedapply@indeed.com`.
  - `blockedSendersFileIsLoaded`: in a temp data dir, `blocked-senders.txt` contains `# comment`, a blank line, `  Shop.Example.COM  ` and `deals@other.example`. Mail from `x@news.shop.example.com` and from `deals@other.example` → false; from `jobs@other.example` with subject "Interview invitation" → true.
  - `missingBlockedSendersFileIsFine`: a temp data dir without the file. Construction succeeds and the keyword behaviour is unchanged.
  - Update `PreFilterBindingTest` to assert `blockedSenders` binds from `application.yml`, so it contains `jobalerts-noreply@linkedin.com`.
- [ ] **Step 3: Run** `./mvnw test -Dtest='PreFilter*'`. Expected: the new tests FAIL.
- [ ] **Step 4: Implement** the rules above in `PreFilter`. Compile keywords to `Pattern`s once, in the constructor.
- [ ] **Step 5: Replay command.** In `DevCommands`, add `tracker.dev.prefilter-replay`. It is read-only, and like `gmail-smoke` it exits when done. Document running it with `--server.port=0 --tracker.sync.scheduler-enabled=false` so it neither clashes with the running tracker nor starts a sync. As a guard, the command refuses to run (it logs an ERROR and exits 1) unless `tracker.sync.scheduler-enabled` is `false`.
  - It selects every GMAIL email with status `NOT_PLACEMENT`, `PROCESSED` or `REVIEW`, or with a non-null `application_id`. Use a `JdbcClient` query in `DevCommands` or a small read-only method on `EmailRepository`.
  - It fetches their bodies with `fetchMany` in chunks of 500 and runs `PreFilter.shouldClassify` on each.
  - It logs one summary line: `prefilter-replay: {n} replayed; {kept} still sent to AI, {dropped} now filtered; relevant (PROCESSED/REVIEW/linked) dropped: {r}`. Then it logs one line per relevant email that would be dropped, with id, sender, subject and date.
  - It writes nothing to the database.
- [ ] **Step 6: README.** In the existing configuration and data-folder section, add:
  - `blocked-senders.txt`: what it does, one address or domain per line, `#` comments, takes effect on restart.
  - The replay command: `./mvnw spring-boot:run -Dspring-boot.run.arguments="--tracker.dev.prefilter-replay=true --server.port=0 --tracker.sync.scheduler-enabled=false"` (Git Bash), and that it changes nothing.
- [ ] **Step 7: Run** `./mvnw test`. Expected: all green, including `PreFilterRecallTest`.
- [ ] **Step 8: Commit** `feat: whole-word pre-filter keywords, blocked senders and a replay check`.

## After the tasks (controller, not a subagent)

- Write the owner's `%LOCALAPPDATA%\PlacementTracker\blocked-senders.txt` from the analysed list. It holds the marketing and job-board domains that had 0 relevant emails, and is never committed.
- Run the replay command against the real mailbox. It must report `relevant ... dropped: 0`. Record the before and after AI-call numbers in the PR description.
