package com.syncsphere.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapped to the {@code documents} table (created by Flyway migration V1).
 *
 * <p>The {@code revision} field is annotated with {@link Version}. That turns on Hibernate's
 * optimistic locking: every UPDATE Hibernate generates looks like
 * {@code UPDATE documents SET ..., revision = 4 WHERE id = ? AND revision = 3}.
 * If another transaction already moved the row to revision 4, zero rows match and Hibernate
 * throws an optimistic-locking exception instead of silently overwriting the newer content.
 */
@Entity
@Table(name = "documents")
public class Document {

    public static final int MAX_TITLE_LENGTH = 120;
    public static final int MAX_CONTENT_LENGTH = 50_000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = MAX_TITLE_LENGTH)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Version
    @Column(nullable = false)
    private long revision;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA; not for application use. */
    protected Document() {
    }

    public Document(String title, String content) {
        this.title = title;
        this.content = content;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Replaces the whole document body. The revision is NOT changed here: Hibernate increments
     * it when the change is flushed to the database.
     */
    public void replaceContent(String newContent) {
        this.content = newContent;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public long getRevision() {
        return revision;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
