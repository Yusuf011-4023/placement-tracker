# Windows Installer and Auto-start Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Install Placement Tracker like a normal Windows app, with a setup .exe, a Start menu entry, a desktop icon and Uninstall in Settings → Apps. Once installed it starts automatically at login, and clicking the icon always just opens the tracker page.

**Architecture:**
- `jpackage` (JDK 24+, which supports WiX v4/v5) wraps the existing Spring Boot jar and a bundled Java runtime into a per-user installer, so no admin rights are needed.
- The app gains two small behaviours:
  - **Single-instance launch:** a second launch opens the browser and exits; a normal launch opens the browser once ready.
  - **Auto-start:** an HKCU `Run` registry entry, which the installed app turns on at first launch, with a toggle on the setup page.
- A PowerShell script builds the installer.

**Tech Stack:** Java 21 app code; JNA `Advapi32Util` (jna-platform is already a dependency) for the registry; jpackage from a JDK 24+ (owner: portable Temurin 25 at a path passed in, never committed); WiX Toolset 5.0.2 (installed); PowerShell 5.1 for the build script.

**Spec:** `docs/superpowers/specs/2026-10-29-placement-tracker-design.md`. Owner decisions on 2026-10-30 and 2026-11-01:
- a real installer, not an app folder;
- auto-start at login;
- done after the speed-ups (merged as bfe0ab5).

## Global Constraints

- The server stays bound to `127.0.0.1:8765`, and `LocalOnlyFilter` is unchanged. Mutating requests need the `X-Tracker: 1` header, as now.
- The data folder (`%LOCALAPPDATA%\PlacementTracker`: DB, tokens, logs, `config.properties`, `blocked-senders.txt`) is never inside the install folder. Uninstalling never deletes it.
- Tests never open a browser, write the registry or touch the real data dir. The test properties gain `tracker.open-browser=false`, and the registry sits behind an interface that tests fake.
- No personal paths in committed files. The JDK location is a script parameter or environment variable.
- The UI never uses `innerHTML`.
- `start-tracker.bat` keeps working for development.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. `./mvnw test` stays green (251 at start).

## Review Focus

1. Launching the installed app while the tracker already runs: the browser opens on the existing instance, the launch exits cleanly, and there is no port-in-use crash and no second tray icon.
2. Auto-start at login (`--autostart`): the tracker starts silently, with no browser tab, and the tray icon appears.
3. Something else, not the tracker, holds port 8765. The launch must not "open the browser and exit" against a stranger. It only treats the port as ours when `/api/dashboard` answers with the expected JSON; otherwise it starts normally and fails with the usual clear port error.
4. Toggling auto-start off removes the registry value. Toggling on writes the current exe path, quoted. Running outside an installed app (no `jpackage.app-path`) reports it as unavailable and never writes.
5. An upgrade installed to a new path: on next start, an enabled Run value pointing elsewhere is rewritten to the current exe.

---

### Task 1: Single-instance launch and open the browser

**Files:**
- Create: `src/main/java/com/placementtracker/launch/LaunchHelper.java`, `src/main/java/com/placementtracker/launch/BrowserOpener.java`
- Modify: `src/main/java/com/placementtracker/PlacementTrackerApplication.java`, `src/test/resources/application.properties`, `src/main/resources/application.yml`
- Test: `src/test/java/com/placementtracker/launch/LaunchHelperTest.java`, `BrowserOpenerTest.java`

**Interfaces:**
- Produces: `public final class LaunchHelper`:
  - `static boolean isAutostart(String[] args)`: true when any arg equals `--autostart`.
  - `static boolean trackerAlreadyRunning(URI dashboard, Duration timeout)`: true only for an HTTP 200 whose JSON body has the top-level keys `accounts` and `chart`. It sends `Host: 127.0.0.1:8765`. Any exception or other response gives false.
  - `static final URI DASHBOARD = URI.create("http://127.0.0.1:8765/api/dashboard")` and `static final URI HOME = URI.create("http://127.0.0.1:8765/")`.
- Produces: `@Component BrowserOpener` listens for `ApplicationReadyEvent`. It opens `HOME` with `Desktop.browse` when `tracker.open-browser` is true (default true), the app was not started with `--autostart` (read via `ApplicationArguments`), and the desktop supports BROWSE. Failures are logged at WARN and never thrown.
- `PlacementTrackerApplication.main`: if `trackerAlreadyRunning(DASHBOARD, 1 s)`, open `HOME` (unless `--autostart`; a second autostart just exits) and return without starting Spring. Otherwise run as now.

- [ ] **Step 1: Write the failing tests.**
  - `LaunchHelperTest`:
    - `autostartFlagDetected`;
    - `runningWhenDashboardAnswersWithTrackerJson`, using a JDK `com.sun.net.httpserver.HttpServer` on an ephemeral port that serves `{"accounts":[],"chart":[]}`;
    - `notRunningWhenSomethingElseAnswers` (200 with `<html>`);
    - `notRunningWhenNothingListens`, finishing within the timeout;
    - `notRunningOnNon200`.
    - Tests pass the test server's URI; the method takes the URI as a parameter.
  - `BrowserOpenerTest`:
    - `doesNotOpenWhenDisabled`;
    - `doesNotOpenOnAutostart`;
    - `opensWhenEnabledAndInteractive`.
    - Use a seam such as a `Consumer<URI>` opener injected in a package-private constructor, so no real browser opens.
- [ ] **Step 2: Run** `./mvnw test -Dtest='LaunchHelperTest,BrowserOpenerTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.** Add `tracker.open-browser: true` to `application.yml` and `tracker.open-browser=false` to the test properties.
- [ ] **Step 4: Run** `./mvnw test`. Expected: all green.
- [ ] **Step 5: Commit** `feat: open the tracker page on launch and reuse a running instance`.

### Task 2: Start automatically at login

**Files:**
- Create: `src/main/java/com/placementtracker/launch/AutostartService.java`, `src/main/java/com/placementtracker/launch/RunKey.java` (interface), `src/main/java/com/placementtracker/launch/WindowsRunKey.java` (JNA implementation)
- Create: `src/main/java/com/placementtracker/web/AutostartController.java`
- Modify: `src/main/resources/static/setup.html`, `setup.js`, `README.md`
- Test: `src/test/java/com/placementtracker/launch/AutostartServiceTest.java`, `src/test/java/com/placementtracker/web/AutostartControllerTest.java`

**Interfaces:**
- Produces: `interface RunKey { Optional<String> read(String name); void write(String name, String command); void delete(String name); }`. `WindowsRunKey` uses `Advapi32Util` on `HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run`.
- Produces: `@Service AutostartService`, with value name `"PlacementTracker"`:
  - `boolean available()`: true when the system property `jpackage.app-path` is set (the jpackage launcher sets it to the installed exe).
  - `boolean enabled()`: the Run value is present.
  - `void setEnabled(boolean on)`: on → write `"\"" + exe + "\" --autostart"`; off → delete. If not available, it throws `IllegalStateException`.
  - On `ApplicationReadyEvent`, when available:
    - if the marker file `<data dir>/autostart-initialised` is absent, enable and create the marker (first launch of the installed app: on by default);
    - else, if enabled and the stored command differs from the expected one (moved or upgraded), rewrite it.
  - Never throws at startup; errors are logged at WARN.
  - Constructor seams for tests: `RunKey`, a `Supplier<Optional<String>>` for the exe path, and `DataDir`.
- Produces: `GET /api/autostart` returns `{"available": bool, "enabled": bool}`. `PUT /api/autostart` takes `{"enabled": bool}` with `X-Tracker: 1` and returns the same shape. It returns 409 `{"error": "Auto-start is only available in the installed app"}` when not available.
- The Windows registry bean is only created on Windows (`@ConditionalOnOs` or an equivalent check). Elsewhere, and in tests, a no-op or fake `RunKey` is used. Tests must never write the real registry.

- [ ] **Step 1: Write the failing tests.**
  - `AutostartServiceTest`, with a fake `RunKey` (a map), temp `DataDir` and exe `C:\Apps\Placement Tracker\Placement Tracker.exe`:
    - `firstLaunchEnablesAndWritesMarker`, with command exactly `"C:\Apps\Placement Tracker\Placement Tracker.exe" --autostart`;
    - `secondLaunchRespectsUserTurningItOff` (marker present, no value → stays off);
    - `movedExeIsRewritten`;
    - `notAvailableNeverWrites`;
    - `disableDeletesValue`.
  - `AutostartControllerTest`:
    - GET shape;
    - PUT toggles;
    - PUT without `X-Tracker` is rejected (the existing filter behaviour);
    - PUT when unavailable gives 409.
- [ ] **Step 2: Run** them. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Setup page.** Add a section "Start automatically when I log in" with a checkbox:
  - its state comes from `GET /api/autostart`, and changing it sends a PUT;
  - when not available, it shows disabled with the note "Available in the installed app".
  - Use `textContent` and `el()` only.
- [ ] **Step 5: README.** Add an "Auto-start" note: it is on by default in the installed app, toggled on the setup page, and should be turned off before uninstalling (otherwise Windows keeps a dead startup entry, harmless and removable in Task Manager → Startup apps).
- [ ] **Step 6: Run** `./mvnw test`. Expected: all green.
- [ ] **Step 7: Commit** `feat: start Placement Tracker automatically at login (installed app)`.

### Task 3: Installer build

**Files:**
- Create: `installer/build-installer.ps1`, `installer/IconGen.java`, `installer/placement-tracker.ico` (generated, committed)
- Modify: `README.md`, `.gitignore` (if needed)

**Interfaces:**
- Consumes: `TrayIconManager.drawIcon()`'s design. The icon generator redraws it at 16, 32, 48 and 256 px in a standalone single-file program; it does not depend on app classes.
- Produces: `installer/build-installer.ps1 -JdkHome <path> [-AppVersion x.y.z] [-SkipTests]`:
  - `-JdkHome` defaults to `$env:JPACKAGE_JDK`, else `$env:JAVA_HOME`. Fail with a clear message unless `<JdkHome>\bin\jpackage.exe --version` reports a major version of 24 or higher.
  - WiX: use `wix.exe` from PATH, else `C:\Program Files\WiX Toolset v5.0\bin` (prepended to PATH for the run); otherwise fail with "Install WiX 5: winget install --id WiXToolset.WiXCLI --version 5.0.2.0", plus the extension commands.
  - Run `.\mvnw.cmd clean package` (adding `-DskipTests` with `-SkipTests`).
  - Stage only the app jar into `target\installer-input`.
  - Run jpackage with:
    `--type exe --name "Placement Tracker" --app-version <AppVersion, default: pom version without -SNAPSHOT> --vendor "Yusuf Aslam" --description "Tracks placement applications from Gmail" --input target\installer-input --main-jar <jar name> --icon installer\placement-tracker.ico --win-per-user-install --win-menu --win-menu-group "Placement Tracker" --win-shortcut --win-upgrade-uuid 6f1c2b8e-4d3a-4b7e-9a51-2c0e8f3d7a14 --dest target\installer`.
  - Print the path of the produced `.exe`.

- [ ] **Step 1: IconGen.** `java installer/IconGen.java installer/placement-tracker.ico` writes a multi-size ICO with PNG-compressed entries (header, directory, PNG payloads). Run it and commit the `.ico`. Verify by loading it back: `ImageIO` cannot read ICO, so check the header bytes `00 00 01 00` and an entry count of 4 in a small check inside IconGen (`--verify`).
- [ ] **Step 2: Script.** Write `build-installer.ps1` as specified. It must work in Windows PowerShell 5.1: no `&&`, no ternary.
- [ ] **Step 3: README.** Add an "Install as a Windows app" section covering:
  - prerequisites: a JDK 24+ for jpackage only, and WiX 5 with the Util and UI extensions;
  - the build command;
  - where the .exe lands;
  - what installing does (per-user, no admin, Start menu, desktop icon, auto-start, data folder untouched);
  - how to uninstall.
  - Keep the existing `start-tracker.bat` instructions as "for development".
- [ ] **Step 4: Run** `./mvnw test`. Expected: green. The installer build itself is run by the controller, not the subagent.
- [ ] **Step 5: Commit** `build: Windows installer via jpackage + WiX 5`.

## After the tasks (controller)

- Build the installer with the owner's portable JDK 25 and check the `.exe` is produced.
- With the owner's go-ahead: stop the dev instance, install, launch from the Start menu, and check:
  - the page opens;
  - the tray icon appears;
  - a second launch only opens the browser;
  - the Run value exists;
  - data and applications are intact.
- Final whole-branch review, then a PR. Merging is the owner's call.
