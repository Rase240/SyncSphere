// Pure text helpers used by the editor. No DOM, no network, so they can be unit-tested with Node
// (see src/test/js/merge.test.mjs).
//
// IMPORTANT: this is NOT operational transformation or a CRDT. It is a small, best-effort
// "three-way merge" for the common case where two people edit different parts of the document.
// Overlapping edits are never merged automatically; the caller keeps the user's text as a draft instead.

/**
 * Finds the single region that differs between two strings by trimming their common prefix and suffix.
 * Returns { start, oldEnd, newEnd }: before[start, oldEnd) was replaced by after[start, newEnd).
 * Example: diffRange("hello world", "hello brave world") -> { start: 6, oldEnd: 6, newEnd: 12 }
 */
export function diffRange(before, after) {
  const shorter = Math.min(before.length, after.length);
  let prefix = 0;
  while (prefix < shorter && before[prefix] === after[prefix]) {
    prefix++;
  }
  let suffix = 0;
  while (suffix < shorter - prefix
         && before[before.length - 1 - suffix] === after[after.length - 1 - suffix]) {
    suffix++;
  }
  return { start: prefix, oldEnd: before.length - suffix, newEnd: after.length - suffix };
}

/**
 * Re-applies the user's local change on top of a newer server version.
 *
 *   base   - the server text the user started from
 *   local  - what the user has now (base + their unsent change)
 *   latest - the newer server text (base + somebody else's change)
 *
 * Returns the merged text, or null when the two changes touch the same region (a real conflict).
 */
export function rebaseLocalChange(base, local, latest) {
  if (local === base) return latest;  // the user changed nothing: just take the server version
  if (latest === base) return local;  // nobody else changed anything

  const mine = diffRange(base, local);
  const theirs = diffRange(base, latest);
  const myText = local.slice(mine.start, mine.newEnd);

  if (mine.oldEnd <= theirs.start) {
    // My change lies completely before theirs: positions before it are identical in base and latest.
    return latest.slice(0, mine.start) + myText + latest.slice(mine.oldEnd);
  }
  if (mine.start >= theirs.oldEnd) {
    // My change lies completely after theirs: shift my positions by how much their change grew/shrank.
    const shift = theirs.newEnd - theirs.oldEnd;
    return latest.slice(0, mine.start + shift) + myText + latest.slice(mine.oldEnd + shift);
  }
  return null; // overlapping edits
}

/**
 * Moves a caret position from oldText to newText so it stays next to the same characters.
 * Text inserted before the caret pushes it right; text removed before it pulls it left.
 */
export function transformCaret(oldText, newText, caret) {
  const change = diffRange(oldText, newText);
  if (caret <= change.start) return caret;
  if (caret >= change.oldEnd) return caret + (change.newEnd - change.oldEnd);
  return change.newEnd; // caret was inside the replaced region: put it at the end of the new text
}

/** Converts a character offset into a 1-based { line, column } for display. */
export function lineAndColumn(text, offset) {
  const before = text.slice(0, Math.min(offset, text.length));
  const lines = before.split('\n');
  return { line: lines.length, column: lines[lines.length - 1].length + 1 };
}
