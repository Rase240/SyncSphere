// Unit tests for the browser merge helpers. Uses Node's built-in test runner (Node 18+), no npm packages:
//   node --test src/test/js/merge.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { diffRange, lineAndColumn, rebaseLocalChange, transformCaret } from '../../main/resources/static/js/merge.js';

test('diffRange finds the single changed region', () => {
  assert.deepEqual(diffRange('hello world', 'hello brave world'), { start: 6, oldEnd: 6, newEnd: 12 });
  assert.deepEqual(diffRange('abc', 'abc'), { start: 3, oldEnd: 3, newEnd: 3 });
  assert.deepEqual(diffRange('abcdef', 'abXYef'), { start: 2, oldEnd: 4, newEnd: 4 });
});

test('non-overlapping edits are merged: mine before theirs', () => {
  const base = 'Title\n\nBody text';
  const mine = 'My Title\n\nBody text';
  const theirs = 'Title\n\nBody text, extended';
  assert.equal(rebaseLocalChange(base, mine, theirs), 'My Title\n\nBody text, extended');
});

test('non-overlapping edits are merged: mine after theirs', () => {
  const base = 'one two three';
  const mine = 'one two three four';
  const theirs = 'zero one two three';
  assert.equal(rebaseLocalChange(base, mine, theirs), 'zero one two three four');
});

test('overlapping edits are reported as a conflict (null)', () => {
  const base = 'The quick fox';
  assert.equal(rebaseLocalChange(base, 'The slow fox', 'The brown fox'), null);
});

test('trivial cases', () => {
  assert.equal(rebaseLocalChange('a', 'a', 'b'), 'b'); // no local change
  assert.equal(rebaseLocalChange('a', 'ab', 'a'), 'ab'); // no remote change
});

test('transformCaret keeps the caret next to the same text', () => {
  // Insert before the caret -> caret moves right by the inserted length.
  assert.equal(transformCaret('hello world', 'XXhello world', 6), 8);
  // Insert after the caret -> caret stays.
  assert.equal(transformCaret('hello world', 'hello world!!', 3), 3);
  // Delete before the caret -> caret moves left.
  assert.equal(transformCaret('hello world', 'world', 8), 2);
});

test('lineAndColumn is 1-based', () => {
  assert.deepEqual(lineAndColumn('ab\ncd', 0), { line: 1, column: 1 });
  assert.deepEqual(lineAndColumn('ab\ncd', 4), { line: 2, column: 2 });
});
