// Placement Tracker dashboard. Plain JS, no framework: fetch the dashboard JSON, then rebuild the page with
// the DOM helpers from common.js. Every piece of data goes in through textContent (see el()).

const STAGE_LABELS = {
  NOT_SUBMITTED: "Not submitted", APPLIED: "Applied", ONLINE_TEST: "Online test", INTERVIEW: "Interview",
  ASSESSMENT_CENTRE: "Assessment centre", OFFER: "Offer", REJECTED: "Rejected", WITHDRAWN: "Withdrawn",
};
// Chart slices use the same colours as the board column headers.
const CHART_STYLE = {
  APPLIED: { name: "Applied", color: "var(--series-1)" },
  ONLINE_TEST: { name: "Online test", color: "var(--series-2)" },
  INTERVIEW: { name: "Interview", color: "var(--series-3)" },
  ASSESSMENT_CENTRE: { name: "Assessment centre", color: "var(--series-4)" },
  OFFER: { name: "Offer", color: "var(--series-5)" },
  CLOSED: { name: "Rejected / withdrawn", color: "var(--closed)" },
};
const BOARD_COLUMNS = [
  { key: "APPLIED", name: "Applied", color: "var(--series-1)" },
  { key: "ONLINE_TEST", name: "Online test", color: "var(--series-2)" },
  { key: "INTERVIEW", name: "Interview", color: "var(--series-3)" },
  { key: "ASSESSMENT_CENTRE", name: "Assessment centre", color: "var(--series-4)" },
  { key: "OUTCOME", name: "Outcome", color: null },
];
const REASON_LABELS = {
  LOW_CONFIDENCE: "Not sure", AMBIGUOUS_MATCH: "Which application?", REGRESSION: "Moves an application backwards",
  CLOSED_CHANGE: "Changes a closed application", EDITED_FIELD: "Changes something you edited",
  AI_FAILED: "Could not be read",
};
const EDITABLE_STAGES = Object.keys(STAGE_LABELS);
const REFRESH_MS = 60 * 1000;
const SYNC_POLL_MS = 2000;

const $ = (id) => document.getElementById(id);
const stageLabel = (stage) => STAGE_LABELS[stage] || stage;

let dashboard = null; // last data from /api/dashboard
let lastRendered = ""; // JSON of the data currently drawn, so an unchanged poll does not rebuild the page
let firstLoad = true;
let refreshTimer = null;
let openApplicationId = null; // application shown in the details panel, if any

// ---------- loading ----------

async function refresh() {
  clearTimeout(refreshTimer);
  try {
    dashboard = await api("GET", "/api/dashboard");
    if (firstLoad && needsSetup(dashboard)) {
      location.replace("setup.html");
      return;
    }
    firstLoad = false;
    renderAll();
  } catch (e) {
    // api() already showed a toast; keep the old data on screen and try again later
  }
  const syncing = dashboard && dashboard.syncing;
  refreshTimer = setTimeout(refresh, syncing ? SYNC_POLL_MS : REFRESH_MS);
}

/** First run: nothing is connected and there is nothing to show yet. */
function needsSetup(data) {
  const anyConnected = data.accounts.some((a) => a.connected);
  const anyApplications = data.chart.some((slice) => slice.count > 0);
  return !anyConnected && !anyApplications;
}

/** After the owner changes something: reload the dashboard and, if open, the details panel. */
async function afterAction() {
  await refresh();
  if (openApplicationId !== null) await showApplication(openApplicationId);
}

function renderAll() {
  renderTopBar();
  const snapshot = JSON.stringify(dashboard);
  if (snapshot === lastRendered) return;
  lastRendered = snapshot;
  renderBanners();
  renderChart();
  renderNeedsAction();
  renderPleaseCheck();
  renderBoard();
}

// ---------- top bar and banners ----------

function renderTopBar() {
  const times = dashboard.accounts.map((a) => a.lastSuccessAt).filter(Boolean).sort();
  $("last-checked").textContent = "Last checked " + timeAgo(times.length ? times[times.length - 1] : null);
  const button = $("check-now");
  button.disabled = dashboard.syncing;
  button.textContent = dashboard.syncing ? "Checking…" : "Check now";
}

function renderBanners() {
  const box = $("banners");
  clear(box);
  if (!dashboard.aiConfigured) {
    box.append(el("div", { class: "banner" },
      el("b", { text: "AI key missing" }),
      " — emails are queued and will be read once you add your Anthropic API key (see README)"));
  }
  for (const account of dashboard.accounts) {
    if (account.status === "NEEDS_RECONNECT") {
      box.append(el("div", { class: "banner" },
        el("b", { text: account.account + " needs reconnecting. " }),
        el("a", { href: "setup.html", text: "Open setup" })));
    } else if (account.status === "ERROR" && account.error) {
      box.append(el("div", { class: "banner", text: "Last check failed: " + account.error }));
    }
  }
}

// ---------- donut chart ----------

function renderChart() {
  const svg = document.querySelector("#donut svg");
  const donut = $("donut");
  const legend = $("legend");
  const tip = $("tip");
  svg.querySelectorAll("path, circle").forEach((n) => n.remove());
  clear(legend);

  const slices = dashboard.chart.map((s) => ({ ...CHART_STYLE[s.stage], n: s.count }));
  const total = slices.reduce((sum, s) => sum + s.n, 0);
  $("total").textContent = total;

  if (total === 0) {
    // Nothing to slice yet: draw an empty ring so the chart still has a shape
    const ring = document.createElementNS("http://www.w3.org/2000/svg", "circle");
    ring.setAttribute("r", 60);
    ring.setAttribute("fill", "none");
    ring.setAttribute("style", "stroke: var(--line); stroke-width: 24");
    svg.append(ring);
  }

  const outer = 72, inner = 48;
  const point = (radius, angle) => [radius * Math.sin(angle), -radius * Math.cos(angle)];
  let start = 0;
  for (const s of slices) {
    const li = el("li", {}, el("span", { class: "swatch", style: "background:" + s.color }), s.name,
      el("span", { class: "n", text: s.n }));
    legend.append(li);
    if (s.n === 0) continue;

    // One donut segment = outer arc, line in, inner arc back. A full circle is clamped just short of 2π.
    const end = start + Math.min((s.n / total) * Math.PI * 2, Math.PI * 2 - 0.001);
    const large = end - start > Math.PI ? 1 : 0;
    const [x1, y1] = point(outer, start), [x2, y2] = point(outer, end);
    const [x3, y3] = point(inner, end), [x4, y4] = point(inner, start);
    const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
    path.setAttribute("d",
      `M${x1} ${y1} A${outer} ${outer} 0 ${large} 1 ${x2} ${y2} L${x3} ${y3} A${inner} ${inner} 0 ${large} 0 ${x4} ${y4}Z`);
    path.style.fill = s.color;
    svg.append(path);
    start = end;

    const label = `${s.name}: ${s.n} of ${total} (${Math.round((s.n / total) * 100)}%)`;
    const on = (event) => {
      donut.classList.add("hovering"); path.classList.add("active"); li.classList.add("active");
      tip.textContent = label; tip.style.opacity = 1;
      if (event && event.clientX) {
        tip.style.left = event.clientX + 12 + "px"; tip.style.top = event.clientY + 12 + "px";
      } else {
        const box = li.getBoundingClientRect();
        tip.style.left = box.right + 8 + "px"; tip.style.top = box.top + "px";
      }
    };
    const off = () => {
      donut.classList.remove("hovering"); path.classList.remove("active"); li.classList.remove("active");
      tip.style.opacity = 0;
    };
    path.addEventListener("mousemove", on);
    path.addEventListener("mouseleave", off);
    li.addEventListener("mouseenter", () => on());
    li.addEventListener("mouseleave", off);
  }
}

// ---------- dates ----------

const DATE_FORMAT = { weekday: "short", day: "numeric", month: "short" };

/** Whole calendar days from today to the date (0 = today, 1 = tomorrow, negative = past). */
function daysUntil(iso, now = new Date()) {
  const due = new Date(iso);
  const midnight = (d) => new Date(d.getFullYear(), d.getMonth(), d.getDate());
  return Math.round((midnight(due) - midnight(now)) / 86400000);
}

function whenLabel(item) {
  if (!item.dueAt) return "No date";
  if (item.urgency === "OVERDUE") return "Overdue";
  const days = daysUntil(item.dueAt);
  if (days <= 0) return "Today";
  if (days === 1) return "Tomorrow";
  if (days <= 3) return `In ${days} days`;
  return new Date(item.dueAt).toLocaleDateString("en-GB", DATE_FORMAT);
}

// ---------- needs action ----------

function renderNeedsAction() {
  const box = $("needs");
  clear(box);
  const items = dashboard.needsAction;
  $("needs-label").textContent = `Needs action (${items.length})`;
  if (items.length === 0) {
    box.append(el("p", { class: "empty", text: "Nothing to do right now." }));
    return;
  }
  for (const item of items) {
    const css = { OVERDUE: "overdue", URGENT: "urgent", SOON: "soon" }[item.urgency] || "";
    box.append(el("div", { class: "action " + css },
      el("div", { class: "when", text: whenLabel(item) }),
      el("button", { class: "linkish what", type: "button", onclick: () => showApplication(item.applicationId) },
        el("b", { text: `${item.company}: ${item.description}` }),
        el("span", { text: item.role || "" })),
      el("button", { class: "btn small", type: "button", text: "✓ Done", onclick: () => markDone(item.actionId) })));
  }
}

async function markDone(actionId) {
  try {
    await api("POST", `/api/actions/${actionId}/done`);
    await afterAction();
  } catch (e) { /* toast already shown */ }
}

// ---------- please check ----------

/**
 * Fills a picker (Attach to…, Merge into…) with every application, including not-submitted and closed ones,
 * which the board leaves out. Fetched fresh each time, and again when the picker gets focus.
 */
async function fillApplicationPicker(select, placeholder, excludeId) {
  let all;
  try {
    all = await api("GET", "/api/applications");
  } catch (e) {
    return; // toast already shown
  }
  const chosen = select.value;
  clear(select);
  select.append(el("option", { value: "", text: placeholder }));
  for (const a of all) {
    if (a.id === excludeId) continue;
    const label = `${a.company} (${a.role || "no role"})${a.stage === "APPLIED" ? "" : " · " + stageLabel(a.stage)}`;
    select.append(el("option", { value: a.id, text: label, selected: String(a.id) === chosen }));
  }
}

function renderPleaseCheck() {
  const section = $("check");
  const list = $("check-list");
  clear(list);
  const items = dashboard.pleaseCheck;
  section.hidden = items.length === 0;
  $("check-label").textContent = `Please check (${items.length})`;
  for (const item of items) list.append(checkItem(item));
}

/** The classification the AI suggested, or null (for example when the AI call failed). */
function classificationOf(item) {
  const s = item.suggestion;
  if (!s) return null;
  return s.classification || (s.stage !== undefined ? s : null);
}

function checkItem(item) {
  const c = classificationOf(item);
  const suggestion = c
    ? `Suggested: ${c.company || "unknown company"}${c.role ? " (" + c.role + ")" : ""} → ${stageLabel(c.stage)}`
    : "No suggestion available. Use Edit & confirm to fill it in.";
  const attach = el("select", { "aria-label": "Attach to an application", onchange: (e) => {
    const id = Number(e.target.value);
    if (id) reviewAction(item.reviewId, "attach", { applicationId: id });
  } }, el("option", { value: "", text: "Attach to…" }));
  fillApplicationPicker(attach, "Attach to…");
  attach.addEventListener("focus", () => fillApplicationPicker(attach, "Attach to…"));

  return el("div", { class: "check-item" },
    el("div", {}, el("span", { class: "reason", text: REASON_LABELS[item.reason] || item.reason }),
      el("b", { text: item.subject || "(no subject)" })),
    el("div", { class: "meta", text: [item.from, item.receivedAt ? new Date(item.receivedAt).toLocaleString("en-GB") : ""].filter(Boolean).join(" · ") }),
    el("div", { class: "suggest", text: suggestion }),
    el("div", { class: "buttons" },
      el("button", { class: "btn small", type: "button", text: "Confirm", disabled: c === null,
        onclick: () => reviewAction(item.reviewId, "confirm") }),
      el("button", { class: "btn small", type: "button", text: "Edit & confirm", onclick: () => editAndConfirm(item, c) }),
      el("button", { class: "btn small", type: "button", text: "Not a placement email",
        onclick: () => reviewAction(item.reviewId, "dismiss") }),
      attach));
}

async function reviewAction(reviewId, action, body) {
  try {
    await api("POST", `/api/review/${reviewId}/${action}`, body);
    await afterAction();
  } catch (e) { /* toast already shown */ }
}

// ---------- dialogs: stage <select> options, Add, Edit & confirm ----------

function fillStageOptions(select, selected) {
  clear(select);
  for (const stage of EDITABLE_STAGES) {
    select.append(el("option", { value: stage, text: stageLabel(stage), selected: stage === selected }));
  }
}

function editAndConfirm(item, c) {
  const dialog = $("edit-dialog");
  const form = $("edit-form");
  form.elements.company.value = (c && c.company) || "";
  form.elements.role.value = (c && c.role) || "";
  fillStageOptions($("edit-stage"), (c && c.stage) || "APPLIED");
  form.onsubmit = async (event) => {
    event.preventDefault();
    const edited = {
      relevance: "YES",
      company: form.elements.company.value.trim(),
      role: form.elements.role.value.trim() || null,
      stage: form.elements.stage.value,
      actions: (c && c.actions) || [],
      confidence: 1.0,
      reason: "Confirmed by you",
    };
    dialog.close();
    await reviewAction(item.reviewId, "confirm", edited);
  };
  dialog.showModal();
}

function setupAddDialog() {
  const dialog = $("add-dialog");
  const form = $("add-form");
  $("add").addEventListener("click", () => {
    form.reset();
    fillStageOptions($("add-stage"), "APPLIED");
    dialog.showModal();
  });
  $("add-cancel").addEventListener("click", () => dialog.close());
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const f = form.elements;
    const body = { company: f.company.value, role: f.role.value, stage: f.stage.value,
      appliedOn: f.appliedOn.value || null, notes: f.notes.value };
    try {
      const created = await api("POST", "/api/applications", body);
      dialog.close();
      await refresh();
      await showApplication(created.id);
    } catch (e) { /* toast already shown; keep the form open */ }
  });
  $("edit-cancel").addEventListener("click", () => $("edit-dialog").close());
}

// ---------- board ----------

function renderBoard() {
  const board = $("board");
  clear(board);
  // Earliest open to-do per application (needsAction is already sorted by due date), for the card's due line
  const nextAction = new Map();
  for (const a of dashboard.needsAction) if (!nextAction.has(a.applicationId)) nextAction.set(a.applicationId, a);

  for (const column of BOARD_COLUMNS) {
    const cards = dashboard.board[column.key] || [];
    const heading = el("h4", {},
      column.color ? el("span", { class: "swatch", style: "background:" + column.color }) : null,
      column.name, el("span", { class: "count", text: cards.length }));
    board.append(el("div", { class: "col" }, heading, ...cards.map((c) => card(c, nextAction.get(c.applicationId)))));
  }
}

function card(c, next) {
  const classes = ["card"];
  if (c.stage === "OFFER") classes.push("offer");
  else if (c.stage === "REJECTED" || c.stage === "WITHDRAWN") classes.push("closed");
  else if (c.urgency) classes.push({ OVERDUE: "overdue", URGENT: "urgent", SOON: "soon" }[c.urgency] || "");

  let note = null;
  if (c.stage === "INTERVIEW" && c.interviewRounds > 1) note = `Interview (round ${c.interviewRounds})`;
  else if (["OFFER", "REJECTED", "WITHDRAWN"].includes(c.stage)) note = stageLabel(c.stage);

  let due = null;
  if (next && c.urgency && c.urgency !== "LATER") {
    due = next.urgency === "OVERDUE" ? "Overdue" : "Due " + whenLabel(next).toLowerCase();
  } else if (next) {
    due = whenLabel(next);
  }
  return el("button", { class: classes.join(" "), type: "button", onclick: () => showApplication(c.applicationId) },
    el("b", { text: c.company }), c.role || "",
    note ? el("div", { class: "stage-note", text: note }) : null,
    due ? el("div", { class: "due", text: due }) : null);
}

// ---------- details panel ----------

async function showApplication(id) {
  let detail;
  try {
    detail = await api("GET", `/api/applications/${id}`);
  } catch (e) {
    closePanel();
    return;
  }
  openApplicationId = id;
  renderPanel(detail);
}

function closePanel() {
  openApplicationId = null;
  clear($("detail-root"));
}

function renderPanel(d) {
  const root = $("detail-root");
  clear(root);
  const locked = (field) => (d.lockedFields.includes(field)
    ? el("span", { class: "lock", title: "You edited this, so later emails will not overwrite it", text: " 🔒" }) : null);

  const company = el("input", { value: d.company });
  const role = el("input", { value: d.role || "" });
  const stage = el("select", {});
  fillStageOptions(stage, d.stage);
  const appliedOn = el("input", { type: "date", value: d.appliedOn || "" });
  const notes = el("textarea", { rows: 3 });
  notes.value = d.notes || "";

  const save = async () => {
    const body = {};
    if (company.value.trim() !== d.company) body.company = company.value.trim();
    if (role.value.trim() !== (d.role || "")) body.role = role.value.trim();
    if (stage.value !== d.stage) body.stage = stage.value;
    if (appliedOn.value !== (d.appliedOn || "")) body.appliedOn = appliedOn.value;
    if (notes.value !== (d.notes || "")) body.notes = notes.value;
    if (Object.keys(body).length === 0) { toast("Nothing changed"); return; }
    try {
      await api("PATCH", `/api/applications/${d.id}`, body);
      await afterAction();
    } catch (e) { /* toast already shown */ }
  };

  const panel = el("aside", { class: "panel", role: "dialog", "aria-label": "Application details" },
    el("h2", {}, d.company, el("button", { class: "btn small", type: "button", text: "Close", onclick: closePanel })),
    el("label", { class: "field" }, "Company", locked("company"), company),
    el("label", { class: "field" }, "Role", locked("role"), role),
    el("label", { class: "field" }, "Stage", locked("stage"), stage),
    el("label", { class: "field" }, "Applied on", locked("appliedOn"), appliedOn),
    el("label", { class: "field" }, "Notes", locked("notes"), notes),
    el("button", { class: "btn primary", type: "button", text: "Save changes", onclick: save }),
    el("h3", { text: "To-dos" }), actionsSection(d),
    el("h3", { text: "History" }), historySection(d),
    el("h3", { text: "Merge" }), mergeSection(d));

  root.append(el("div", { class: "scrim", onclick: closePanel }), panel);
}

function actionsSection(d) {
  const list = el("ul", { class: "list" });
  if (d.actions.length === 0) list.append(el("li", { class: "muted", text: "No to-dos yet." }));
  for (const a of d.actions) {
    const open = a.status === "OPEN";
    list.append(el("li", {},
      el("span", { class: "grow" + (open ? "" : " done-text"), text: a.description + (a.dueAt ? " · " + new Date(a.dueAt).toLocaleString("en-GB", { dateStyle: "medium", timeStyle: "short" }) : "") }),
      open ? el("button", { class: "btn small", type: "button", text: "✓ Done", onclick: () => markDone(a.id) })
        : el("span", { class: "muted", text: a.status.toLowerCase() })));
  }
  const description = el("input", { placeholder: "New to-do" });
  const due = el("input", { type: "datetime-local", "aria-label": "Due" });
  const add = async () => {
    if (!description.value.trim()) { toast("Describe the to-do first"); return; }
    const body = { description: description.value.trim(), dueAt: due.value ? new Date(due.value).toISOString() : null };
    try {
      await api("POST", `/api/applications/${d.id}/actions`, body);
      await afterAction();
    } catch (e) { /* toast already shown */ }
  };
  return el("div", {}, list, el("div", { class: "inline" }, description, due,
    el("button", { class: "btn", type: "button", text: "Add", onclick: add })));
}

function historySection(d) {
  const list = el("ul", { class: "list" });
  if (d.history.length === 0) list.append(el("li", { class: "muted", text: "No history yet." }));
  for (const h of d.history) {
    const change = (h.fromStage ? stageLabel(h.fromStage) + " → " : "") + stageLabel(h.toStage);
    const when = new Date(h.changedAt).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" });
    const from = h.source === "USER" ? "edited by you" : "from an email";
    // Only https links are rendered as links, so a hostile web link can never be a javascript: URL
    const link = h.email && h.email.webLink && h.email.webLink.startsWith("https://")
      ? el("a", { href: h.email.webLink, target: "_blank", rel: "noopener noreferrer", text: h.email.subject || "Open email" })
      : (h.email ? el("span", { text: h.email.subject || "" }) : null);
    list.append(el("li", {}, el("span", { class: "grow" }, `${change} · ${when} · ${from}`, link ? " · " : "", link)));
  }
  return list;
}

function mergeSection(d) {
  const target = el("select", { "aria-label": "Merge into" }, el("option", { value: "", text: "Merge into…" }));
  fillApplicationPicker(target, "Merge into…", d.id);
  target.addEventListener("focus", () => fillApplicationPicker(target, "Merge into…", d.id));
  const merge = async () => {
    const targetId = Number(target.value);
    if (!targetId) { toast("Choose an application to merge into"); return; }
    if (!confirm(`Merge "${d.company}" into the chosen application? This cannot be undone.`)) return;
    try {
      await api("POST", `/api/applications/${targetId}/merge`, { sourceId: d.id });
      closePanel();
      await refresh();
    } catch (e) { /* toast already shown */ }
  };
  return el("div", {}, el("p", { class: "muted", text: "Duplicate of another application? Move its emails and to-dos there." }),
    el("div", { class: "inline" }, target, el("button", { class: "btn", type: "button", text: "Merge", onclick: merge })));
}

// ---------- start ----------

$("check-now").addEventListener("click", async () => {
  $("check-now").disabled = true;
  $("check-now").textContent = "Checking…";
  try {
    await api("POST", "/api/sync");
  } catch (e) { /* toast already shown */ }
  await refresh();
});
document.addEventListener("keydown", (e) => { if (e.key === "Escape" && openApplicationId !== null) closePanel(); });
setupAddDialog();
refresh();
