# Placement Tracker: Design

*Date: 29 October 2026 · Status: draft for approval (builder check done; answers added)*

## 1. Intent

A student applying for industrial placements loses track of applications because the emails (confirmations, test invites, interview requests, outcomes) are buried among everything else in their inboxes. They have forgotten companies they'd already applied to.

The Placement Tracker is a personal app on their Windows laptop. It reads their **Gmail** and **university Outlook (Microsoft 365)** inboxes, works out which emails are about placement applications, and keeps one up-to-date record per application. It puts **what needs action** first and sends **Windows notifications** so nothing is forgotten.

It is also a **CV/portfolio project**. The owner will publish it on GitHub and explain it in interviews, so code quality, clear structure and a good README matter as much as features.

## 2. Goals

- Every placement application found in either inbox appears in the tracker, with no silent misses.
- Each application shows its correct current stage.
- The owner sees immediately what needs doing (tests, interviews to book, deadlines).
- The owner is notified on the laptop when a new action appears or a deadline approaches.
- The owner can correct anything and add applications by hand.
- The codebase is something the owner can confidently explain in a placement interview.

## 3. Non-goals

- Multiple users, accounts or hosting. It's single-user and local only.
- Sending, deleting, moving or marking emails as read. Access is **read-only**.
- Mobile app or access from other devices.
- Writing cover letters, applying to jobs, or scraping job boards.
- Demo mode or fake-data mode (considered and declined).

## 4. Anti-goals (failure even if it "works")

The owner said they'd abandon the tracker if it **misses applications** or **gets stages wrong**. So:

- **Recall over precision.** If the system is unsure whether an email is placement-related, it must surface it for review, never drop it.
- **No silent stage regressions.** The system never moves an application to an earlier stage on its own.
- **No gaps.** Downtime (laptop off, API outage, auth expiry) must never cause emails to be skipped permanently. The next run catches up.
- **No leaks.** Personal email content, OAuth tokens and API keys must never be committed to the public GitHub repo.

## 5. How it works

### 5.1 Running on the laptop
- A Java **Spring Boot** application runs in the background and shows a **Windows system-tray icon**.
- The tray menu has: *Open tracker* (opens the UI in the default browser), *Check now*, *Quit*.
- The web UI is served **only on `127.0.0.1`**, never on a network interface.
- *(Builder's choice:)* whether it starts automatically when Windows logs in. If so, make it optional and document how to turn it off.

### 5.2 Connecting the inboxes
- **Gmail:** Google OAuth desktop flow with the read-only Gmail scope (`gmail.readonly`).
- **University Outlook:** Microsoft Graph with delegated `Mail.Read`, via MSAL for Java.
- A first-run setup page walks the owner through connecting each account and shows whether each is connected or needs reconnecting.
- Refresh tokens are stored outside the repo and protected (see §7).

### 5.3 Checking for mail
- Automatic check every **15–30 minutes** while the app is running (builder picks within the range; configurable), plus a **"Check now"** button.
- **First run backfills from 1 August 2026.**
- **Folders scanned (my call, given "no silent misses"):** every folder except Sent, Drafts and Trash/Deleted. That includes Gmail Spam and the Promotions/Updates tabs, and Outlook Junk, "Other" and user folders, because recruiter and applicant-tracking emails often land there. The local pre-filter keeps the extra volume from reaching the AI.
- The app records which messages it has processed (per-account message IDs and a high-water mark). Each run processes everything new since the last *successful* run, so nothing is skipped after downtime or failures. Failed messages are retried on the next run.
- A status line shows when the last check finished, per account, and any failure (for example "Outlook needs reconnecting").

### 5.4 Understanding the emails
Two stages:

1. **Cheap pre-filter (local, no AI):** discards obvious noise such as promotional/newsletter categories, social notifications and known non-job senders. The filter must be **conservative**: when in doubt, pass the email on to step 2. The owner applies through company career sites (Workday, Oracle, SuccessFactors etc.), job boards (LinkedIn, Indeed, RateMyPlacement, Bright Network, Gradcracker…), their university careers portal, and recruiters/agencies, so formats vary widely.
2. **Claude via the Anthropic API:** for each remaining email, returns structured output:
   - is it about a placement/internship/graduate application? (yes / no / unsure)
   - company, role title
   - stage implied by the email
   - any deadline or event date/time
   - the action required, if any (one short sentence)
   - confidence
- **What counts as an application (owner's rule):**
  - **Counts:** any application the owner submitted for a placement role, including via a recruiter or agency putting them forward for a specific placement role.
  - **Ignored (classified "no", not sent to *Please check*):** job alerts and "jobs you might like" emails, and anything not tied to the owner's placement applications (general recruiter outreach not linked to a role, careers-fair and event invites, newsletters).
  - **"Finish your application" reminders** (started but not submitted) create an entry in a **Not yet submitted** state with a to-do "Finish your <company> application" (plus deadline if given) in *Needs action*. When a submission confirmation arrives, the entry moves to Applied. *(My call:)* Not-yet-submitted entries appear only in *Needs action* and the card details, not on the board or in the chart, until they're submitted.
  - *(My call:)* "Placement" means industrial placements / year-in-industry roles **and internships**. Full-time graduate jobs and part-time/casual jobs don't count.
  - These rules define the labels in the evaluation set (§8).
- *(My call:)* Use a small, cheap Claude model for classification (e.g. Claude Haiku 5.5), with structured/JSON output. Check current pricing and model IDs at build time and keep the model configurable.
- Only the email's sender, subject, date and body text are sent. Attachments are not sent.

### 5.5 Applications and stages
- **One record per application** (company + role). Multiple emails about the same application are linked to that one record.
- **Stages:** Applied, Online test, Interview, Assessment centre (the **active** stages), then Offer / Rejected / Withdrawn (the **closed** stages).
- Closed applications stay visible (so the owner can see "already applied there"), with rejected/withdrawn shown muted.
- **Stage rules (my call):** real recruitment processes aren't linear (test after a phone screen, a final interview after an assessment centre, several interview rounds), so the active stages after Applied are **not strictly ordered**:
  - The AI may move an application between Online test, Interview and Assessment centre in any direction, because each new invite reflects the current step. Each application keeps a count of interview rounds, derived from its history, shown on the card as e.g. "Interview (round 2)".
  - A **regression** is only: moving an application from a later active stage back to Applied, or moving it out of a closed stage. Regressions never happen automatically and go to *Please check*.
  - Closed → closed (e.g. Offer → Withdrawn, Rejected → Offer) always goes to *Please check*.
- *(Builder's choice:)* the exact matching logic for linking an email to an existing application (company name normalisation, role similarity, thread IDs, sender domain). It must be tested, and when unsure it should go to *Please check* rather than create a duplicate or merge wrongly.
- **Automatic changes:** the AI may make any non-regression change above, or move an active application to a closed stage. Anything that would be a regression goes to *Please check* and changes nothing.
- **Actions (my call):** an application can have **several open actions**, each with its own description and optional deadline/event date (one email can bring a test deadline and an interview slot together). An action leaves *Needs action* when:
  - the owner ticks it **Done** (a tick control on each action item and in the card details), or
  - a later email moves the application to a new stage, which closes the actions from the previous stage, or
  - the application is closed.
  An action whose deadline passes without being done stays on the list, marked **Overdue**. Done, closed and overdue actions never trigger reminders.
- **History:** every stage change is recorded with the date, the source (which email, or "edited by you") and the old → new stage.

### 5.6 "Please check" queue
Emails go here instead of being dropped or applied when:
- the AI says "unsure", or confidence is below a threshold;
- it can't confidently match an email to an existing application versus a new one;
- the email implies a stage regression;
- the AI call failed repeatedly for that email.

Each item offers one-click actions: *Confirm* (accept the suggestion), *Edit and confirm*, *Not a placement email*, and *Attach to existing application*.

### 5.7 Manual control
- The owner can edit any field of an application (company, role, stage, deadline, action, notes) and add applications by hand.
- **Manual edits always win:** a field the owner has edited is never silently overwritten by the AI. *(My call:)* if a newer email would change an edited field (e.g. the owner set the stage to Interview and a rejection email arrives), the change goes to *Please check* as a suggestion instead of being applied, so the application can still stay up to date. New emails always attach to the application and appear in its history.
- *(Builder's choice:)* whether the owner can delete or merge applications. Merging duplicates is recommended.

### 5.8 Notifications
- A Windows notification (via the system tray) when:
  - a new action appears (e.g. test invite, interview to book);
  - a deadline is **3 days** away and again **1 day** away;
  - an item lands in *Please check* (*my call:* batched into one notification per check, not one per email).
- Clicking a notification opens the tracker.
- No duplicate notifications for the same event.

## 6. The main screen (approved mockup: layout C v2)

Reference mockup: `.superpowers/brainstorm/main-screen-v2.html`.

- **Top bar:** app name, "Last checked …" status, **Check now**, **+ Add**.
- **Top row, left: "Your applications"** donut chart with the total in the centre, one segment per stage (Applied, Online test, Interview, Assessment centre, Offer, Rejected), a legend with counts, and a hover tooltip ("Online test: 3 of 24 (13%)"). Rejected is neutral grey. Withdrawn is folded into the grey "Closed" segment if needed (*my call*).
- **Top row, right: "Needs action"**: items sorted by deadline, colour-coded (orange: due within 1 day; yellow: within 3 days).
- **"Please check" list**: shown only when there are items. *(My call:)* placed directly under the top row so it can't be missed.
- **Board:** one column per stage (Applied, Online test, Interview, Assessment centre, Outcome). Column headers use the same colours as the chart. Cards needing action are highlighted. Closed cards are muted.
- **Clicking a card** opens its details: all fields (editable), the stage history with source emails, and links to open each original email in Gmail/Outlook on the web.
- Light and dark mode follow the Windows setting.
- *(Builder's choice:)* search/filter on the board, and the front-end approach (plain HTML/CSS/JS or a light library). It should stay simple enough for the owner to explain.

## 7. Technical decisions

| Area | Decision | Whose |
|---|---|---|
| Language | **Java** (owner is most confident in Java; JDK 23 installed; target Java 21+) | Owner |
| Framework | **Spring Boot**, local web UI in the browser + system-tray icon | Owner approved recommendation |
| Storage | Local **SQLite** database file in the user's app-data folder, not in the repo | My call |
| Build | Maven via the Maven wrapper (`mvnw`), since Maven isn't installed globally | My call |
| Gmail | Google API Java client, `gmail.readonly` | My call |
| Outlook | Microsoft Graph Java SDK + MSAL4J, delegated `Mail.Read` | My call |
| AI | Anthropic Java SDK, small Claude model, structured output | Owner chose AI; model is my call |
| Secrets | Anthropic API key from an environment variable or a local config file outside the repo. OAuth tokens stored in the user's app-data folder (encrypted at rest or in Windows Credential Manager; builder's choice) | My call |
| Repo hygiene | `.gitignore` covers the database, tokens, config, `.superpowers/` and any real-email fixtures | My call |

## 8. Accuracy and testing

- **Unit and integration tests** for the parts with real logic: the pre-filter, email-to-application matching, stage transitions (forward-only, manual-edit precedence), backfill/catch-up and notification de-duplication.
- **Classification evaluation set:** a collection of example placement emails covering all stages and all four sources (career sites, job boards, uni portal, recruiters), plus non-placement look-alikes. Synthetic examples are committed. Any real examples stay in a git-ignored folder. A test run reports how many company/stage extractions were correct, so changes to prompts or models can't silently make accuracy worse.
- The Gmail, Outlook and Anthropic clients sit behind interfaces so tests run without network access.

## 9. Constraints and risks

- **University tenant consent (main risk):** many university Microsoft 365 tenants block or require admin approval for third-party apps requesting `Mail.Read`. IMAP is not a real alternative, because Microsoft 365's OAuth IMAP needs the same consent. **The first build step is a small throwaway check:** register an Entra app and try to sign in and read one email with `Mail.Read`. If the university blocks it, the fallback is auto-forwarding the university mailbox to Gmail (**the owner has agreed to this**). In that case the Graph integration isn't built, and the Gmail side must extract the original sender, subject and date from forwarded emails and de-duplicate messages that appear in both places. Gmail is unaffected either way.
- **Google OAuth "testing" mode:** an unverified Google Cloud app in testing mode has refresh tokens that expire after about 7 days. The builder should document the setup (publishing the app as "In production" for personal use, or handling re-authorisation smoothly) and surface "needs reconnecting" clearly.
- **Cost:** the Anthropic API is pay-as-you-go and requires its own account (separate from a Claude subscription). The pre-filter keeps volume down. The README should explain the expected cost.
- **Privacy:** email text of likely-relevant messages is sent to Anthropic for processing. Nothing else leaves the laptop.
- **Node.js is not installed.** Nothing in the design requires it.

## 10. Left to the builder

- Package and class structure, database schema and migration tool.
- Front-end approach (see §6), and how the donut chart is drawn (inline SVG as in the mockup is fine).
- Exact sync interval within 15–30 min, the confidence threshold, and the prompt wording.
- Auto-start on login (§5.1), delete/merge (§5.7) and board search/filter (§6).
- README contents. It should include setup steps for Google Cloud, Microsoft Entra app registration and an Anthropic API key, plus screenshots.
