package com.syncsphere.document;

import com.syncsphere.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the revision rules against a real PostgreSQL database (Testcontainers), because the
 * optimistic-locking behaviour depends on the actual SQL UPDATE ... WHERE revision = ? semantics.
 */
class DocumentServiceIntegrationTest extends IntegrationTestBase {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentRepository documentRepository;

    @Test
    void createdDocumentStartsAtRevisionZeroAndCanBeLoaded() {
        DocumentSnapshot created = documentService.createDocument("  Meeting notes  ", "Agenda");

        DocumentSnapshot loaded = documentService.getDocument(created.id());

        assertThat(loaded.title()).isEqualTo("Meeting notes"); // surrounding whitespace removed
        assertThat(loaded.content()).isEqualTo("Agenda");
        assertThat(loaded.revision()).isZero();
        assertThat(loaded.createdAt()).isNotNull();
    }

    @Test
    void acceptedEditIncrementsRevisionAndIsPersisted() {
        DocumentSnapshot created = documentService.createDocument("Plan", "v0");

        DocumentSnapshot first = documentService.applyEdit(created.id(), 0, "v1");
        DocumentSnapshot second = documentService.applyEdit(created.id(), 1, "v2");

        assertThat(first.revision()).isEqualTo(1);
        assertThat(second.revision()).isEqualTo(2);
        // Read the row back through the repository (a new persistence context) to prove it was committed.
        Document stored = documentRepository.findById(created.id()).orElseThrow();
        assertThat(stored.getContent()).isEqualTo("v2");
        assertThat(stored.getRevision()).isEqualTo(2);
        assertThat(stored.getUpdatedAt()).isAfterOrEqualTo(stored.getCreatedAt());
    }

    @Test
    void staleEditIsRejectedAndDoesNotOverwriteNewerContent() {
        DocumentSnapshot created = documentService.createDocument("Shared", "original");
        documentService.applyEdit(created.id(), 0, "Alice's change"); // now at revision 1

        // Bob still thinks the document is at revision 0.
        assertThatThrownBy(() -> documentService.applyEdit(created.id(), 0, "Bob's change"))
                .isInstanceOf(RevisionConflictException.class)
                .hasMessageContaining("stale revision 0");

        DocumentSnapshot current = documentService.getDocument(created.id());
        assertThat(current.content()).isEqualTo("Alice's change");
        assertThat(current.revision()).isEqualTo(1);
    }

    @Test
    void editFromTheFutureIsAlsoRejected() {
        DocumentSnapshot created = documentService.createDocument("Shared", "original");

        assertThatThrownBy(() -> documentService.applyEdit(created.id(), 7, "made-up revision"))
                .isInstanceOf(RevisionConflictException.class);
    }

    @Test
    void identicalContentDoesNotCreateANewRevision() {
        DocumentSnapshot created = documentService.createDocument("Same", "unchanged");

        DocumentSnapshot result = documentService.applyEdit(created.id(), 0, "unchanged");

        assertThat(result.revision()).isZero();
    }

    @Test
    void editingAMissingDocumentFails() {
        UUID missing = UUID.randomUUID();

        assertThatThrownBy(() -> documentService.applyEdit(missing, 0, "x"))
                .isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> documentService.getDocument(missing))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    /**
     * Several writers submit an edit based on the same revision at the same moment (as could happen on
     * different application instances). Exactly one must win; every other one must get a conflict,
     * whether it is caught by the explicit revision check or by the @Version optimistic lock.
     */
    @Test
    void concurrentEditsOnTheSameRevisionProduceExactlyOneWinner() throws Exception {
        DocumentSnapshot created = documentService.createDocument("Race", "start");
        int writers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<String>> outcomes = new ArrayList<>();

        for (int i = 0; i < writers; i++) {
            String content = "writer-" + i;
            Callable<String> attempt = () -> {
                startSignal.await();
                try {
                    documentService.applyEdit(created.id(), 0, content);
                    return "accepted";
                } catch (RevisionConflictException e) {
                    return "conflict";
                }
            };
            outcomes.add(pool.submit(attempt));
        }
        startSignal.countDown();

        List<String> results = new ArrayList<>();
        for (Future<String> outcome : outcomes) {
            results.add(outcome.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(results).containsOnlyOnce("accepted");
        assertThat(results).filteredOn("conflict"::equals).hasSize(writers - 1);
        DocumentSnapshot finalState = documentService.getDocument(created.id());
        assertThat(finalState.revision()).isEqualTo(1);
        assertThat(finalState.content()).startsWith("writer-");
    }

    @Test
    void documentListContainsSummariesNewestFirst() {
        DocumentSnapshot older = documentService.createDocument("Older", "");
        DocumentSnapshot newer = documentService.createDocument("Newer", "");

        List<DocumentSummary> list = documentService.listRecentDocuments();

        List<UUID> ids = list.stream().map(DocumentSummary::id).toList();
        assertThat(ids).contains(older.id(), newer.id());
        assertThat(ids.indexOf(newer.id())).isLessThan(ids.indexOf(older.id()));
    }
}
