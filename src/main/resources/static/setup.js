// Setup page: shows the Gmail connection status and starts the sign-in.
const POLL_MS = 2000;
const GIVE_UP_AFTER_MS = 3 * 60 * 1000; // the Google sign-in happens in another window, so allow time

const statusLine = document.getElementById("status");
const connectButton = document.getElementById("connect");
let pollTimer = null;

function describe(account) {
  if (account.connected) return "Connected";
  if (account.status === "NEEDS_RECONNECT") return "Needs reconnecting";
  return "Not connected";
}

function showAccount(account) {
  statusLine.textContent = describe(account);
  statusLine.className = account.connected ? "ok" : "muted";
  connectButton.textContent = account.connected ? "Reconnect" : "Connect Gmail";
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

/** After pressing Connect, ask the server every couple of seconds until it reports connected. */
function pollUntilConnected() {
  const started = Date.now();
  clearInterval(pollTimer);
  connectButton.disabled = true;
  statusLine.textContent = "Waiting for you to finish signing in…";
  pollTimer = setInterval(async () => {
    const gmail = await refresh();
    if (gmail && gmail.connected) {
      clearInterval(pollTimer);
      connectButton.disabled = false;
      toast("Gmail connected. Opening the tracker…");
      setTimeout(() => (location.href = "index.html"), 1500);
    } else if (Date.now() - started > GIVE_UP_AFTER_MS) {
      clearInterval(pollTimer);
      connectButton.disabled = false;
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
    // api() already showed the error toast
  }
});

refresh();
