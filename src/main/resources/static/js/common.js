// Small helpers shared by the home page and the editor page.

const NAME_KEY = 'syncsphere.displayName';
export const NAME_PATTERN = /^[\p{L}\p{N} ._'-]{1,40}$/u; // same rule as JoinRequest.java

/** The display name is remembered in this browser only. It is NOT an authenticated identity. */
export function getDisplayName() {
  try {
    return localStorage.getItem(NAME_KEY) || '';
  } catch {
    return ''; // storage can be blocked (private mode); the user will simply be asked again
  }
}

export function setDisplayName(name) {
  try {
    localStorage.setItem(NAME_KEY, name);
  } catch {
    // ignore: the name is still used for this page view
  }
}

/** fetch() wrapper that throws a readable Error for non-2xx responses (RFC 7807 bodies are understood). */
export async function fetchJson(url, options = {}) {
  const response = await fetch(url, {
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    ...options,
  });
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    const detail = body && (body.errors ? body.errors.join('; ') : body.detail);
    throw new Error(detail || `HTTP ${response.status}`);
  }
  return body;
}

/** Shows which application instance served this page (useful in the two-instance Docker demo). */
export async function showInstanceBadge(element) {
  try {
    const info = await fetchJson('/api/instance');
    element.textContent = `instance: ${info.instanceId}`;
  } catch {
    element.textContent = 'instance: unknown';
  }
}

export function formatTime(isoString) {
  return new Date(isoString).toLocaleString();
}
