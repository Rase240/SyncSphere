package com.syncsphere.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Business rules for documents: creation, lookup and revision-checked edits.
 *
 * <p>This is the only class that writes to the documents table, so the conflict rule lives in one place.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final int DOCUMENT_LIST_LIMIT = 100;

    private final DocumentRepository documentRepository;

    public DocumentService(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Transactional
    public DocumentSnapshot createDocument(String title, String initialContent) {
        Document saved = documentRepository.save(new Document(title.strip(), initialContent));
        log.info("Document created documentId={} title=\"{}\"", saved.getId(), saved.getTitle());
        return DocumentSnapshot.from(saved);
    }

    @Transactional(readOnly = true)
    public DocumentSnapshot getDocument(UUID documentId) {
        return documentRepository.findById(documentId)
                .map(DocumentSnapshot::from)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    @Transactional(readOnly = true)
    public List<DocumentSummary> listRecentDocuments() {
        return documentRepository.findRecentSummaries(PageRequest.of(0, DOCUMENT_LIST_LIMIT));
    }

    /**
     * Replaces the document content if, and only if, the client based its edit on the latest revision.
     *
     * <p>There are two layers of protection, and both are needed:
     * <ol>
     *   <li><b>Explicit check.</b> If {@code baseRevision} differs from the stored revision we reject at once.
     *       This catches the common case (the client simply had not seen the newest change yet).</li>
     *   <li><b>Optimistic lock at write time.</b> Two requests (possibly on two different application instances)
     *       can both pass step 1 at the same moment because both read revision N. The {@code @Version}
     *       column makes the UPDATE conditional on {@code revision = N}; only the first commit matches a row,
     *       the second gets an {@link OptimisticLockingFailureException}, which we translate into the same
     *       conflict error. PostgreSQL row locking therefore acts as the single source of truth across instances.</li>
     * </ol>
     *
     * @return the new snapshot; its revision is {@code baseRevision + 1} (unchanged if the content was identical)
     * @throws DocumentNotFoundException  if the document does not exist
     * @throws RevisionConflictException  if {@code baseRevision} is stale
     */
    @Transactional
    public DocumentSnapshot applyEdit(UUID documentId, long baseRevision, String newContent) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        if (document.getRevision() != baseRevision) {
            log.info("Edit rejected (stale) documentId={} baseRevision={} currentRevision={}",
                    documentId, baseRevision, document.getRevision());
            throw new RevisionConflictException(documentId, baseRevision);
        }

        if (document.getContent().equals(newContent)) {
            // Nothing changed, so Hibernate would not issue an UPDATE and the revision stays the same.
            return DocumentSnapshot.from(document);
        }

        document.replaceContent(newContent);
        try {
            // Flush now (instead of at commit) so a lost race surfaces here, where we can translate it.
            documentRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            log.info("Edit rejected (concurrent write) documentId={} baseRevision={}", documentId, baseRevision);
            throw new RevisionConflictException(documentId, baseRevision);
        }

        log.debug("Edit persisted documentId={} revision={}", documentId, document.getRevision());
        return DocumentSnapshot.from(document);
    }
}
