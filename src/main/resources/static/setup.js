// Setup page: shows the Gmail connection status and starts the sign-in.
const POLL_MS = 2000;
// The Google sign-in happens in another window, so allow time; the server gives up after 5 minutes.
const GIVE_UP_AFTER_MS = 5.5 * 60 * 1000;

const statusLine = document.getElementById("status");
const connectButton = document.getElementById("connect");
let pollTimer = null;

function needsReconnect(account) {
  return account.status === "NEEDS_RECONNECT";
}

function describe(account) {
  // Checked before `connected`: a token Google has rejected is still on disk, but it no longer works.
  if (needsReconnect(account)) return "Needs reconnecting";
  if (account.connected) return "Connected";
  return "Not connected";
}

function showAccount(account) {
  const ok = account.connected && !needsReconnect(account);
  statusLine.textContent = describe(account);
  statusLine.className = ok ? "ok" : "muted";
  connectButton.textContent = ok || needsReconnect(account) ? "Reconnect" : "Connect Gmail";
}

async function loadGmail() {
  const accounts = await api("GET", "/api/accounts");
  return accounts.find((a) => a.account === "GMAIL");
}

async function refresh() {
  try {
    const gmail = await loadGmail();
    if (gmail) showAccount(gmail);
    return gmail;
  } catch (e) {
    statusLine.textContent = "Cannot read status";
    return null;
  }
}

function stopPolling() {
  clearInterval(pollTimer);
  connectButton.disabled = false;
}

/** After pressing Connect, ask the server every couple of seconds until it reports connected or a failure. */
function pollUntilConnected() {
  const started = Date.now();
  clearInterval(pollTimer);
  connectButton.disabled = true;
  statusLine.textContent = "Waiting for you to finish signing in…";
  pollTimer = setInterval(async () => {
    const gmail = await refresh();
    if (gmail && gmail.lastConnectError) {
      stopPolling();
      statusLine.textContent = "Sign-in failed: " + gmail.lastConnectError;
      statusLine.className = "muted";
    } else if (gmail && gmail.connected && !needsReconnect(gmail)) {
      stopPolling();
      toast("Gmail connected. Opening the tracker…");
      setTimeout(() => (location.href = "index.html"), 1500);
    } else if (Date.now() - started > GIVE_UP_AFTER_MS) {
      stopPolling();
      toast("Sign-in did not finish. Press Connect to try again.");
      refresh();
    }
  }, POLL_MS);
}

connectButton.addEventListener("click", async () => {
  try {
    await api("POST", "/api/accounts/GMAIL/connect");
    pollUntilConnected();
  } catch (e) {
    // api() already showed the error toast; keep it on the page too (e.g. the client secret file is missing)
    statusLine.textContent = e.message;
    statusLine.className = "muted";
  }
});

refresh();
