// Document page: connects over STOMP, keeps the textarea in sync with the server, shows presence.
//
// Synchronization model (whole-document snapshots + revision numbers):
//   * The server is the single source of truth. Every accepted edit gets the next revision number.
//   * We send the FULL text together with the revision it was based on (baseRevision).
//   * At most ONE edit is "in flight" at a time. Typing while waiting is collected and sent afterwards.
//   * Our own edit comes back to us on the document topic (same clientEditId) = acknowledgement.
//   * If someone else's edit won, the server rejects ours with REVISION_CONFLICT and the latest snapshot.
//     We then try a simple three-way merge (merge.js); if the edits overlap, we keep the user's text
//     in a "rejected draft" panel instead of losing it or silently overwriting the other person.
import { fetchJson, getDisplayName, NAME_PATTERN, setDisplayName, showInstanceBadge } from './common.js';
import { lineAndColumn, rebaseLocalChange, transformCaret } from './merge.js';

const SEND_DEBOUNCE_MS = 250;     // wait for a short pause in typing before sending
const CURSOR_THROTTLE_MS = 150;   // at most ~7 cursor updates per second
const ACK_TIMEOUT_MS = 10_000;    // give up waiting for an acknowledgement after this
const RESYNC_INTERVAL_MS = 15_000; // safety net against missed Pub/Sub messages
const MAX_CONTENT_LENGTH = 50_000;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const documentId = new URLSearchParams(window.location.search).get('id') || '';

const els = {
  title: document.getElementById('doc-title'),
  connection: document.getElementById('connection-status'),
  instance: document.getElementById('instance-badge'),
  saveState: document.getElementById('save-state'),
  editor: document.getElementById('editor'),
  charCount: document.getElementById('char-count'),
  participants: document.getElementById('participants'),
  participantCount: document.getElementById('participant-count'),
  activity: document.getElementById('activity'),
  banner: document.getElementById('banner'),
  draftPanel: document.getElementById('draft-panel'),
  draftText: document.getElementById('draft-text'),
  draftRestore: document.getElementById('draft-restore'),
  draftCopy: document.getElementById('draft-copy'),
  draftDismiss: document.getElementById('draft-dismiss'),
  nameDialog: document.getElementById('name-dialog'),
  nameForm: document.getElementById('name-form'),
  nameInput: document.getElementById('name-input'),
  nameError: document.getElementById('name-error'),
};

const state = {
  displayName: '',
  joined: false,
  me: null,               // our Participant record, assigned by the server
  serverRevision: -1,     // newest authoritative revision we know about
  serverContent: '',      // the text at serverRevision
  inFlight: null,         // { clientEditId, baseRevision, baseContent, content } while waiting for the server
  lostInFlight: null,     // an in-flight edit whose outcome we never learned (disconnect / timeout)
  participants: new Map(), // participantId -> { ...participant, cursor }
  lastCursorSent: '',
};

let client = null;
let sendTimer = null;
let ackTimer = null;
let cursorTimer = null;

// ---------------------------------------------------------------------------------------------
// Connection lifecycle
// ---------------------------------------------------------------------------------------------

function connect() {
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
  client = new StompJs.Client({
    brokerURL: `${scheme}://${window.location.host}/ws`,
    reconnectDelay: 2000,        // stompjs reconnects automatically
    heartbeatIncoming: 10_000,
    heartbeatOutgoing: 10_000,
    onConnect: onConnected,
    onWebSocketClose: onDisconnected,
    onStompError: (frame) => logActivity(`Server error: ${frame.headers.message}`, 'error'),
  });
  setConnection('connecting');
  client.activate();
}

function onConnected() {
  setConnection('connected');
  const topic = `/topic/documents/${documentId}`;
  // Subscribe BEFORE joining so that no update between "snapshot taken" and "subscribed" is missed.
  // Anything that arrives twice is harmless: updates with an old revision are ignored.
  client.subscribe(topic, (message) => onDocumentUpdated(JSON.parse(message.body)));
  client.subscribe(`${topic}/presence`, (message) => onPresenceChanged(JSON.parse(message.body)));
  client.subscribe(`${topic}/cursors`, (message) => onCursorMoved(JSON.parse(message.body)));
  client.subscribe('/user/queue/snapshot', (message) => onJoined(JSON.parse(message.body)));
  client.subscribe('/user/queue/errors', (message) => onServerError(JSON.parse(message.body)));
  publish('join', { displayName: state.displayName });
}

function onDisconnected() {
  if (state.joined) {
    logActivity('Connection lost. Reconnecting…', 'warn');
  }
  setConnection('reconnecting');
  state.joined = false;
  if (state.inFlight) {
    state.lostInFlight = state.inFlight; // the server may or may not have applied it
    state.inFlight = null;
    clearTimeout(ackTimer);
  }
  els.editor.readOnly = true;
}

function publish(action, payload) {
  client.publish({ destination: `/app/documents/${documentId}/${action}`, body: JSON.stringify(payload) });
}

// ---------------------------------------------------------------------------------------------
// Incoming messages
// ---------------------------------------------------------------------------------------------

/** Private reply to our join: the authoritative starting point. Also runs after every reconnect. */
function onJoined(message) {
  state.me = message.you;
  state.joined = true;
  els.title.textContent = message.document.title;
  document.title = `${message.document.title} · SyncSphere`;
  state.participants = new Map(message.participants.map((p) => [p.participantId, p]));
  renderParticipants();

  const firstJoin = state.serverRevision < 0;
  adoptSnapshot(message.document);
  els.editor.readOnly = false;
  if (firstJoin) {
    logActivity(`Joined as ${state.me.displayName} at revision ${message.document.revision}.`);
  } else {
    logActivity(`Reconnected; resynchronized at revision ${message.document.revision}.`);
  }
  scheduleSend();
}

/** Someone's edit was accepted (possibly our own). */
function onDocumentUpdated(event) {
  if (state.inFlight && event.clientEditId === state.inFlight.clientEditId) {
    onOwnEditAcknowledged(event);
    return;
  }
  if (event.revision <= state.serverRevision) {
    return; // duplicate or out-of-date: we already have this revision or a newer one
  }
  const base = state.serverContent;
  state.serverRevision = event.revision;
  state.serverContent = event.content;
  logActivity(`${event.editorName} edited → revision ${event.revision} (via ${event.originInstanceId})`);
  if (!state.inFlight) {
    reconcileEditor(base);
  }
  // If an edit of ours is in flight it will now be rejected as stale; we reconcile when that answer arrives.
  updateSaveState();
}

function onOwnEditAcknowledged(event) {
  const acked = state.inFlight;
  state.inFlight = null;
  clearTimeout(ackTimer);
  if (event.revision >= state.serverRevision) {
    state.serverRevision = event.revision;
    state.serverContent = event.content;
  } else {
    // A newer revision (built on top of ours) arrived first; our extra typing is based on acked.content.
    reconcileEditor(acked.content);
  }
  updateSaveState();
  scheduleSend(); // send anything typed while we were waiting
}

function onServerError(error) {
  if (error.code === 'REVISION_CONFLICT' && state.inFlight) {
    const rejected = state.inFlight;
    state.inFlight = null;
    clearTimeout(ackTimer);
    if (error.latest.revision >= state.serverRevision) {
      state.serverRevision = error.latest.revision;
      state.serverContent = error.latest.content;
    }
    const merged = reconcileEditor(rejected.baseContent);
    logActivity(merged
      ? `Edit was based on revision ${rejected.baseRevision}; merged onto revision ${state.serverRevision} and resending.`
      : `Edit conflicted with revision ${state.serverRevision}; your text was kept as a draft.`, 'warn');
    scheduleSend();
    return;
  }

  logActivity(`${error.code}: ${error.message}${error.details ? ' – ' + error.details.join('; ') : ''}`, 'error');
  if (error.code === 'DOCUMENT_NOT_FOUND') {
    showBanner('This document does not exist.', 'error');
    client.deactivate();
    return;
  }
  if (state.inFlight) {
    // The edit was refused for a non-conflict reason (validation, database down, ...). Retry later.
    state.inFlight = null;
    clearTimeout(ackTimer);
    showBanner(`Your last change could not be saved (${error.code}). Retrying…`, 'warn');
    setTimeout(scheduleSend, 3000);
  }
  updateSaveState();
}

function onPresenceChanged(event) {
  const p = event.participant;
  if (event.change === 'JOINED') {
    state.participants.set(p.participantId, p);
    // Our own JOINED event can arrive before our snapshot (state.joined is still false then); skip it.
    if (state.joined && p.participantId !== state.me.participantId) {
      logActivity(`${p.displayName} joined (instance ${p.instanceId}).`);
    }
  } else {
    state.participants.delete(p.participantId);
    logActivity(`${p.displayName} left.`);
  }
  renderParticipants();
}

function onCursorMoved(event) {
  if (state.me && event.participantId === state.me.participantId) {
    return;
  }
  const participant = state.participants.get(event.participantId)
    || { participantId: event.participantId, displayName: event.displayName, color: event.color };
  participant.cursor = { start: event.selectionStart, end: event.selectionEnd };
  state.participants.set(event.participantId, participant);
  renderParticipants();
}

// ---------------------------------------------------------------------------------------------
// Reconciling the textarea with the authoritative server text
// ---------------------------------------------------------------------------------------------

/**
 * Makes the editor show state.serverContent while keeping the user's unsent change.
 * `base` is the server text the editor's current content was derived from.
 * Returns true if the user's change was kept (or there was none), false if it went to the draft panel.
 */
function reconcileEditor(base) {
  const local = els.editor.value;
  if (local === base) {
    setEditorText(state.serverContent);
    return true;
  }
  const merged = rebaseLocalChange(base, local, state.serverContent);
  if (merged === null) {
    keepRejectedDraft(local);
    setEditorText(state.serverContent);
    return false;
  }
  setEditorText(merged);
  return true;
}

/** Used for the join snapshot and for periodic resynchronization over HTTP. */
function adoptSnapshot(snapshot) {
  if (snapshot.revision < state.serverRevision) {
    // A newer update already arrived on the topic (it can overtake the private snapshot reply). Keep it.
    updateSaveState();
    return;
  }
  let base = state.serverContent;
  const lost = state.lostInFlight;
  if (lost) {
    // If the server has exactly the text we sent, our lost edit was applied after all.
    base = snapshot.content === lost.content ? lost.content : lost.baseContent;
    state.lostInFlight = null;
  }
  state.serverRevision = snapshot.revision;
  state.serverContent = snapshot.content;
  reconcileEditor(base);
  updateSaveState();
}

/** Replaces the textarea content while keeping the caret next to the same characters. */
function setEditorText(text) {
  const editor = els.editor;
  const old = editor.value;
  if (old === text) {
    return;
  }
  const hadFocus = document.activeElement === editor;
  const start = transformCaret(old, text, editor.selectionStart);
  const end = transformCaret(old, text, editor.selectionEnd);
  editor.value = text;
  if (hadFocus) {
    editor.setSelectionRange(start, end);
  }
  updateCharCount();
}

// ---------------------------------------------------------------------------------------------
// Outgoing edits and cursor positions
// ---------------------------------------------------------------------------------------------

function scheduleSend() {
  clearTimeout(sendTimer);
  sendTimer = setTimeout(sendPendingEdit, SEND_DEBOUNCE_MS);
}

function sendPendingEdit() {
  if (!state.joined || state.inFlight || !client.connected) {
    return; // we will be called again after the acknowledgement / reconnect
  }
  const content = els.editor.value;
  if (content === state.serverContent) {
    updateSaveState();
    return;
  }
  if (content.length > MAX_CONTENT_LENGTH) {
    showBanner(`Documents are limited to ${MAX_CONTENT_LENGTH} characters.`, 'error');
    return;
  }
  state.inFlight = {
    clientEditId: crypto.randomUUID(),
    baseRevision: state.serverRevision,
    baseContent: state.serverContent,
    content,
  };
  publish('edit', {
    baseRevision: state.inFlight.baseRevision,
    content,
    clientEditId: state.inFlight.clientEditId,
  });
  ackTimer = setTimeout(onAckTimeout, ACK_TIMEOUT_MS);
  updateSaveState();
}

function onAckTimeout() {
  if (!state.inFlight) {
    return;
  }
  logActivity('No acknowledgement from the server; reloading the latest version.', 'warn');
  state.lostInFlight = state.inFlight;
  state.inFlight = null;
  resyncFromServer();
}

function scheduleCursorUpdate() {
  if (cursorTimer) {
    return;
  }
  cursorTimer = setTimeout(() => {
    cursorTimer = null;
    if (!state.joined || !client.connected) {
      return;
    }
    const { selectionStart, selectionEnd } = els.editor;
    const key = `${state.serverRevision}:${selectionStart}:${selectionEnd}`;
    if (key !== state.lastCursorSent) {
      state.lastCursorSent = key;
      publish('cursor', { revision: Math.max(state.serverRevision, 0), selectionStart, selectionEnd });
    }
  }, CURSOR_THROTTLE_MS);
}

// ---------------------------------------------------------------------------------------------
// Safety net: Redis Pub/Sub does not store messages, so a missed event is possible.
// Every few seconds we compare our revision with the database and catch up if we fell behind.
// ---------------------------------------------------------------------------------------------

async function resyncFromServer() {
  if (!state.joined) {
    return;
  }
  // The two requests are independent on purpose: the document comes from PostgreSQL, the participant
  // list from Redis. A Redis outage must not stop us from catching up on missed document revisions.
  try {
    const snapshot = await fetchJson(`/api/documents/${documentId}`);
    if (!state.inFlight && (snapshot.revision > state.serverRevision || state.lostInFlight)) {
      logActivity(`Caught up from revision ${state.serverRevision} to ${snapshot.revision} using a snapshot.`, 'warn');
      adoptSnapshot(snapshot);
      scheduleSend();
    }
  } catch (error) {
    logActivity(`Document resync failed: ${error.message}`, 'warn');
  }
  try {
    const participants = await fetchJson(`/api/documents/${documentId}/participants`);
    const cursors = new Map([...state.participants].map(([id, p]) => [id, p.cursor]));
    state.participants = new Map(participants.map((p) => [p.participantId, { ...p, cursor: cursors.get(p.participantId) }]));
    renderParticipants();
  } catch {
    // Presence is best-effort; keep showing the last known list.
  }
}

// ---------------------------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------------------------

function renderParticipants() {
  els.participants.replaceChildren();
  els.participantCount.textContent = String(state.participants.size);
  for (const p of state.participants.values()) {
    const item = document.createElement('li');
    const dot = document.createElement('span');
    dot.className = 'dot';
    dot.style.background = p.color || '#888';
    const name = document.createElement('span');
    name.className = 'name';
    const isMe = state.me && p.participantId === state.me.participantId;
    name.textContent = p.displayName + (isMe ? ' (you)' : '');
    const detail = document.createElement('span');
    detail.className = 'meta';
    detail.textContent = describeCursor(p) + (p.instanceId ? ` · ${p.instanceId}` : '');
    item.append(dot, name, detail);
    els.participants.append(item);
  }
}

function describeCursor(participant) {
  if (!participant.cursor) {
    return '';
  }
  const { line, column } = lineAndColumn(els.editor.value, participant.cursor.start);
  const selected = participant.cursor.end - participant.cursor.start;
  return `Ln ${line}, Col ${column}` + (selected > 0 ? ` (${selected} selected)` : '');
}

function updateSaveState() {
  let text;
  if (!state.joined) {
    text = 'Offline – changes will be sent after reconnecting';
  } else if (state.inFlight) {
    text = 'Saving…';
  } else if (els.editor.value !== state.serverContent) {
    text = 'Unsaved changes';
  } else {
    text = `All changes saved · revision ${state.serverRevision}`;
  }
  els.saveState.textContent = text;
}

function updateCharCount() {
  els.charCount.textContent = `${els.editor.value.length} / ${MAX_CONTENT_LENGTH}`;
}

function setConnection(status) {
  els.connection.textContent = status;
  els.connection.dataset.status = status;
  updateSaveState();
}

function logActivity(text, level = 'info') {
  const item = document.createElement('li');
  item.className = level;
  const time = document.createElement('time');
  time.textContent = new Date().toLocaleTimeString();
  item.append(time, document.createTextNode(` ${text}`));
  els.activity.prepend(item);
  while (els.activity.children.length > 50) {
    els.activity.lastElementChild.remove();
  }
}

function showBanner(text, level) {
  els.banner.textContent = text;
  els.banner.className = `banner ${level}`;
  els.banner.hidden = false;
  clearTimeout(showBanner.timer);
  showBanner.timer = setTimeout(() => { els.banner.hidden = true; }, 8000);
}

function keepRejectedDraft(text) {
  els.draftText.value = text;
  els.draftPanel.hidden = false;
}

// ---------------------------------------------------------------------------------------------
// Startup
// ---------------------------------------------------------------------------------------------

function wireEditor() {
  els.editor.addEventListener('input', () => {
    updateCharCount();
    updateSaveState();
    scheduleSend();
    scheduleCursorUpdate();
  });
  for (const type of ['select', 'click', 'keyup']) {
    els.editor.addEventListener(type, scheduleCursorUpdate);
  }
  els.draftCopy.addEventListener('click', () => navigator.clipboard.writeText(els.draftText.value));
  els.draftDismiss.addEventListener('click', () => { els.draftPanel.hidden = true; });
  els.draftRestore.addEventListener('click', () => {
    // An explicit user decision: replace the current text with the draft (sent as a normal edit).
    setEditorText(els.draftText.value);
    els.draftPanel.hidden = true;
    scheduleSend();
  });
  setInterval(resyncFromServer, RESYNC_INTERVAL_MS);
}

function askForNameThenConnect() {
  els.nameInput.value = getDisplayName();
  els.nameDialog.hidden = false;
  els.nameInput.focus();
  els.nameForm.addEventListener('submit', (event) => {
    event.preventDefault();
    const name = els.nameInput.value.trim();
    if (!NAME_PATTERN.test(name)) {
      els.nameError.textContent = 'Use 1–40 letters, digits, spaces or . _ \' -';
      return;
    }
    setDisplayName(name);
    state.displayName = name;
    els.nameDialog.hidden = true;
    connect();
  });
}

if (!UUID_PATTERN.test(documentId)) {
  els.title.textContent = 'Invalid document link';
  showBanner('The link does not contain a valid document ID.', 'error');
} else {
  showInstanceBadge(els.instance);
  wireEditor();
  updateCharCount();
  const savedName = getDisplayName();
  if (NAME_PATTERN.test(savedName)) {
    state.displayName = savedName;
    connect();
  } else {
    askForNameThenConnect();
  }
}
