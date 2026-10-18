package com.syncsphere.document;

import java.time.Instant;
import java.util.UUID;

/**
 * One row in the document list. It intentionally excludes {@code content}: the list query uses a JPQL
 * constructor expression (see {@link DocumentRepository#findRecentSummaries}) so the potentially large
 * text column is never read just to render titles.
 */
public record DocumentSummary(UUID id, String title, long revision, Instant updatedAt) {
}
