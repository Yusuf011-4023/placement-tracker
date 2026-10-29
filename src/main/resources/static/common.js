// Small helpers shared by index.html and setup.html: DOM building, API calls and toasts.
// Nothing here uses innerHTML. Email subjects and company names are untrusted text, so every
// piece of data goes in through textContent or setAttribute.

/**
 * Builds an element. `props` may hold: class, text, and any other attribute; on* keys are event listeners.
 * Children can be nodes, strings or null/false (skipped).
 */
function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === null || value === undefined || value === false) continue;
    if (key === "class") node.className = value;
    else if (key === "text") node.textContent = value;
    else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? "" : value);
  }
  for (const child of children) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child); // append() turns a string into a text node, never into HTML
  }
  return node;
}

/** Removes all children of a node. */
function clear(node) {
  node.replaceChildren();
}

/**
 * Calls the local API. Anything that changes data carries X-Tracker: 1 (the server rejects it otherwise).
 * Throws an Error carrying the server's {"error": ...} message, and also shows it as a toast,
 * so no failure is ever silent.
 */
async function api(method, path, body) {
  const headers = {};
  if (method !== "GET") headers["X-Tracker"] = "1";
  if (body !== undefined) headers["Content-Type"] = "application/json";
  let response;
  try {
    response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (networkError) {
    toast("Cannot reach the tracker. Is it still running?");
    throw networkError;
  }
  const text = await response.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch (parseError) {
    data = null;
  }
  if (!response.ok) {
    const message = (data && data.error) || `Request failed (${response.status})`;
    toast(message);
    throw new Error(message);
  }
  return data;
}

/** Shows a small non-blocking message in the corner for a few seconds. */
function toast(message) {
  let box = document.getElementById("toasts");
  if (!box) {
    box = el("div", { id: "toasts", "aria-live": "polite" });
    document.body.append(box);
  }
  const item = el("div", { class: "toast", role: "status", text: message });
  box.append(item);
  setTimeout(() => item.remove(), 6000);
}

/** "2 min ago" style text for an ISO timestamp. */
function timeAgo(iso, now = new Date()) {
  if (!iso) return "never";
  const minutes = Math.round((now - new Date(iso)) / 60000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  return `${Math.round(hours / 24)} days ago`;
}
