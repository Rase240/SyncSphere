# Five-minute demo

A reproducible script for showing SyncSphere in an interview or a screen recording. Every step below was run on
the Docker Compose stack during the acceptance review; the expected output is what was actually observed.

## Setup (once, before the demo)

```bash
docker compose up -d --build
docker compose ps
```

All four services should show `(healthy)`. Use two **different browsers** (or one normal and one private window)
so each gets its own display name. Arrange them side by side.

## Script

| # | Do | Point out | What you should see |
|---|---|---|---|
| 1 | Window A: open <http://localhost:8080>, name "Alice", create "Interview demo" | REST `POST /api/documents`; the badge says `instance: app-1` | Editor opens, "All changes saved · revision 0" |
| 2 | Copy the URL, change `8080` → `8081`, open it in window B, name "Bob" | B is on a *different server* (`instance: app-2`) | Both participant lists show Alice · app-1 and Bob · app-2 |
| 3 | Type a line in A | Edit → PostgreSQL → Redis Pub/Sub → app-2 → B | B updates within moments; B's activity log: "Alice edited → revision 1 (via app-1)" |
| 4 | Click/select text in B | Cursor events are relayed but never stored | A shows "Bob … Ln x, Col y (n selected)" |
| 5 | Terminal: `docker compose exec redis redis-cli SUBSCRIBE syncsphere:sync-events`, then type in A | The actual JSON events travelling between instances | `DOCUMENT_UPDATED` / `CURSOR_MOVED` messages with `originInstanceId` |
| 6 | Terminal: `docker compose restart app-1` | Recovery from snapshots; nothing acknowledged is lost | A shows "reconnecting", then "Reconnected; resynchronized at revision N" with the same text |
| 7 | Terminal: `docker compose exec postgres psql -U syncsphere -d syncsphere -c "select title, revision from documents;"` | PostgreSQL is the source of truth; revision = number of accepted edits | The document and its current revision |
| 8 | Optional: `docker compose stop redis`, type in A, `docker compose start redis` | Graceful degradation | A keeps saving; B catches up from a snapshot within ~15 s after Redis returns ("Caught up from revision …") |

## Showing a revision conflict reliably

Two people typing at the "same moment" by hand rarely collide, because updates usually arrive in well under a
second. Two reproducible ways to show the conflict rule:

- **Automated** (best for interviews): run
  `./mvnw test -Dtest='CollaborationWebSocketTest#staleEditIsRejectedPrivatelyWithTheLatestSnapshot'` and walk
  through the test code.
- **In the browser**: open DevTools → Console in window B and paste the snippet below (replace the ID). It opens
  a second STOMP connection and sends an edit based on revision 0. Expected output:
  `{"code":"REVISION_CONFLICT", "message":"Your edit was based on revision 0 but the document is at revision N.", ...}`;
  the document is not changed.

```js
const id = 'PASTE-DOCUMENT-ID';
const c = new StompJs.Client({ brokerURL: `ws://${location.host}/ws`, reconnectDelay: 0 });
c.onConnect = () => {
  c.subscribe('/user/queue/errors', (m) => { console.log(m.body); c.deactivate(); });
  c.subscribe('/user/queue/snapshot', () => c.publish({ destination: `/app/documents/${id}/edit`,
    body: JSON.stringify({ baseRevision: 0, content: 'stale edit', clientEditId: 'demo-stale-1' }) }));
  c.publish({ destination: `/app/documents/${id}/join`, body: JSON.stringify({ displayName: 'Stale Demo' }) });
};
c.activate();
```

## Screenshots worth taking

Only `docs/images/editor-instance-2.jpg` is currently in the repository. These would demonstrate the project
best (take them at about 1280×720):

1. **Two windows side by side** on `:8080` and `:8081` editing the same document — both instance badges and both
   participant lists visible. This is the single most important image.
2. **The rejected-draft panel** after an overlapping edit, next to the activity-log line explaining the conflict.
3. **Terminal with `redis-cli SUBSCRIBE`** showing a `DOCUMENT_UPDATED` event with `originInstanceId`.
4. **`docker compose ps`** with all four services `(healthy)`.
5. **The activity log after `docker compose restart app-1`**: "Connection lost. Reconnecting…" followed by
   "Reconnected; resynchronized at revision N".
6. **Test output**: `Tests run: 47, Failures: 0` and `BUILD SUCCESS`.

## What not to claim during the demo

No latency or throughput figures were measured. The merge is a simple, single-region three-way merge in the
browser, not OT or a CRDT. The display name is not an authenticated identity.
