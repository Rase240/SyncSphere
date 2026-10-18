# API and messaging reference

All examples below were taken from the running application (Docker Compose, two instances).

## 1. HTTP API

Base URL: `http://localhost:8080` (instance 1) or `http://localhost:8081` (instance 2). JSON in and out.

### `POST /api/documents` — create a document

Request:

```json
{ "title": "Design review", "content": "optional initial text" }
```

| Field | Rules |
|---|---|
| `title` | required, not blank, at most 120 characters; surrounding whitespace is removed before saving |
| `content` | optional (`null` → empty), at most 50,000 characters |

Response `201 Created`, header `Location: http://localhost:8080/api/documents/{id}`:

```json
{
  "id": "0893a64e-10d1-4ab7-af15-55db4b8b3728",
  "title": "Design review",
  "content": "optional initial text",
  "revision": 0,
  "createdAt": "2026-10-18T19:07:06.912795Z",
  "updatedAt": "2026-10-18T19:07:06.912795Z"
}
```

### `GET /api/documents` — list

Up to 100 documents, most recently edited first. Content is not included.

```json
[ { "id": "0893a64e-...", "title": "Design review", "revision": 3, "updatedAt": "2026-10-18T19:10:00Z" } ]
```

### `GET /api/documents/{id}` — snapshot

Same shape as the create response. This is the authoritative state; clients use it for periodic resync.

### `GET /api/documents/{id}/participants` — presence

```json
[
  { "participantId": "c9fb596b-45ec-46bf-9d1c-996fceb0cb50", "displayName": "Bob",
    "color": "#9a6324", "instanceId": "app-2", "joinedAt": "2026-10-18T19:49:03.308327254Z" }
]
```

Ordered by `joinedAt`. Participants whose instance stopped heart-beating are removed before responding.

### `GET /api/instance`

```json
{ "instanceId": "app-1" }
```

### Actuator

| Path | Meaning |
|---|---|
| `/actuator/health` | Overall status plus components (`db`, `redis`, `diskSpace`, ...) |
| `/actuator/health/readiness` | `UP` only when `db` and `redis` are UP — used by the Docker health check |
| `/actuator/health/liveness` | `UP` while the application is running |

### Error responses (RFC 7807)

| Situation | Status | Example `detail` |
|---|---|---|
| Validation failed | 400 | `Request validation failed` + `"errors": ["title: title must not be blank"]` |
| Body missing / not JSON | 400 | `Request body is missing or is not valid JSON` |
| `{id}` is not a UUID | 400 | `Invalid value for 'id': expected a UUID` |
| Document does not exist | 404 | `Document 00000000-0000-0000-0000-000000000000 does not exist` |
| PostgreSQL or Redis unreachable | 503 | `A data store is temporarily unavailable. Please retry.` |

```json
{ "type": "about:blank", "title": "Bad Request", "status": 400, "detail": "Request validation failed",
  "instance": "/api/documents", "errors": ["title: title must not be blank"] }
```

## 2. WebSocket / STOMP

### Connection

- URL: `ws://localhost:8080/ws` — plain WebSocket (no SockJS), STOMP 1.2.
- The handshake is rejected with HTTP 403 if the browser's `Origin` header is not allowed
  (`SYNCSPHERE_ALLOWED_ORIGINS`); verified with `Origin: http://evil.example` → 403, `http://localhost:8080` → 101.
- The server assigns each connection a random principal; its name is the participant ID.
- Heartbeats: the server offers 10 s / 10 s. A connection that stops sending heartbeats is closed and the
  participant is removed from presence.
- Maximum inbound frame: 512 KB.

### Client → server (SEND)

| Destination | Payload | Rules |
|---|---|---|
| `/app/documents/{id}/join` | `{"displayName":"Alice"}` | 1–40 chars, letters/digits/space/`. _ ' -`; document must exist |
| `/app/documents/{id}/edit` | `{"baseRevision":4,"content":"...","clientEditId":"3d02d926-..."}` | must have joined this document; `baseRevision ≥ 0`; content ≤ 50,000 chars; `clientEditId` 1–64 of `[A-Za-z0-9-]` |
| `/app/documents/{id}/cursor` | `{"revision":7,"selectionStart":194,"selectionEnd":194}` | must have joined; offsets 0–50,000; `selectionEnd ≥ selectionStart` |

`{id}` must be a UUID. Any other SEND destination is dropped by `StompDestinationGuard` (logged as a warning,
no reply).

### Server → client (SUBSCRIBE)

| Destination | Who receives it | Message |
|---|---|---|
| `/topic/documents/{id}` | everyone subscribed to the document, on every instance | `DOCUMENT_UPDATED` |
| `/topic/documents/{id}/presence` | same | `PRESENCE_CHANGED` |
| `/topic/documents/{id}/cursors` | same | `CURSOR_MOVED` |
| `/user/queue/snapshot` | only the connection that sent `join` | `JoinedMessage` |
| `/user/queue/errors` | only the connection whose message failed | `ErrorMessage` |

Other SUBSCRIBE destinations are dropped. `/user/queue/...` is translated by Spring to a per-session queue, so
one connection cannot read another connection's private messages.

### Message formats (captured from the running system)

`DOCUMENT_UPDATED` — also exactly what travels through Redis:

```json
{
  "type": "DOCUMENT_UPDATED",
  "eventId": "c2224b05-1e86-499d-b836-9b256c1011fa",
  "documentId": "9e7f8a1a-b266-4e54-a6f5-1b071de550da",
  "originInstanceId": "app-2",
  "revision": 8,
  "content": "STANDUP Agenda\n1. Bob's item from instance 2\n...",
  "updatedAt": "2026-10-18T19:49:05.674093940Z",
  "editorParticipantId": "c9fb596b-45ec-46bf-9d1c-996fceb0cb50",
  "editorName": "Bob",
  "clientEditId": "3d02d926-c1a0-4ab0-990b-e3fca2dce9d3"
}
```

`PRESENCE_CHANGED`:

```json
{
  "type": "PRESENCE_CHANGED",
  "eventId": "d6260d3c-dde0-4199-ab88-e711ecd4dd7a",
  "documentId": "9e7f8a1a-b266-4e54-a6f5-1b071de550da",
  "originInstanceId": "app-2",
  "change": "JOINED",
  "participant": { "participantId": "c9fb596b-...", "displayName": "Bob", "color": "#9a6324",
                   "instanceId": "app-2", "joinedAt": "2026-10-18T19:49:03.308327254Z" }
}
```

`CURSOR_MOVED`:

```json
{
  "type": "CURSOR_MOVED",
  "eventId": "f46e38a7-9aef-4b62-b75a-da81dce62336",
  "documentId": "9e7f8a1a-b266-4e54-a6f5-1b071de550da",
  "originInstanceId": "app-2",
  "participantId": "c9fb596b-45ec-46bf-9d1c-996fceb0cb50",
  "displayName": "Bob", "color": "#9a6324",
  "revision": 7, "selectionStart": 194, "selectionEnd": 194
}
```

`JoinedMessage` (on `/user/queue/snapshot`):

```json
{
  "document": { "id": "...", "title": "Interview demo", "content": "...", "revision": 7,
                "createdAt": "...", "updatedAt": "..." },
  "you": { "participantId": "...", "displayName": "Bob", "color": "#9a6324", "instanceId": "app-2", "joinedAt": "..." },
  "participants": [ { "participantId": "...", "displayName": "Alice", "...": "..." } ]
}
```

`ErrorMessage` (on `/user/queue/errors`; `null` fields are omitted):

```json
{
  "code": "REVISION_CONFLICT",
  "message": "Your edit was based on revision 1 but the document is at revision 2.",
  "documentId": "9e7f8a1a-b266-4e54-a6f5-1b071de550da",
  "latest": { "id": "...", "title": "...", "content": "...", "revision": 2, "createdAt": "...", "updatedAt": "..." }
}
```

| `code` | When |
|---|---|
| `REVISION_CONFLICT` | `baseRevision` is not the latest |
| `VALIDATION_FAILED` | Bean Validation failed (`details` lists the messages) |
| `INVALID_MESSAGE` | the body is not valid JSON for the destination |
| `DOCUMENT_NOT_FOUND` | join/edit for a document that does not exist |
| `NOT_JOINED` | edit/cursor before joining that document on this connection |
| `SERVICE_UNAVAILABLE` | PostgreSQL or Redis unreachable |
| `INTERNAL_ERROR` | anything unexpected (logged with a stack trace on the server) |

How `editor.js` reacts: `REVISION_CONFLICT` → three-way merge if the changes do not overlap, otherwise the
rejected-draft panel. `DOCUMENT_NOT_FOUND` → banner and disconnect. Any other error while an edit is in flight
→ the text stays in the editor, a banner is shown, and sending is retried after 3 seconds. Every error is also
written to the activity log.

## 3. Redis

| Key / channel | Type | Content |
|---|---|---|
| `syncsphere:sync-events` | Pub/Sub channel | JSON `SyncEvent` (the three formats above) |
| `syncsphere:presence:{documentId}` | hash | field = participant ID, value = participant JSON |
| `syncsphere:instance:{instanceId}` | string with 30 s TTL | `"alive"` — instance heartbeat |

Inspect them:

```bash
docker compose exec redis redis-cli SUBSCRIBE syncsphere:sync-events
```

```bash
docker compose exec redis redis-cli --scan --pattern "syncsphere:*"
```

## 4. Database

```sql
CREATE TABLE documents (
    id          UUID         PRIMARY KEY,
    title       VARCHAR(120) NOT NULL,
    content     TEXT         NOT NULL DEFAULT '',
    revision    BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT documents_title_not_blank   CHECK (length(trim(title)) > 0),
    CONSTRAINT documents_content_max_chars CHECK (char_length(content) <= 50000),
    CONSTRAINT documents_revision_positive CHECK (revision >= 0)
);
CREATE INDEX idx_documents_updated_at ON documents (updated_at DESC);
```

Source: `src/main/resources/db/migration/V1__create_documents_table.sql`.
