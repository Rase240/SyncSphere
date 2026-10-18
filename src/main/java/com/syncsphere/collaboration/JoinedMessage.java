package com.syncsphere.collaboration;

import com.syncsphere.document.DocumentSnapshot;

import java.util.List;

/**
 * Sent privately to a client (on {@code /user/queue/snapshot}) after it joins a document:
 * the authoritative current state it should start from.
 *
 * @param you          the participant record the server created for this connection
 * @param participants everyone currently connected, including {@code you}
 */
public record JoinedMessage(DocumentSnapshot document, Participant you, List<Participant> participants) {
}
