package com.syncsphere.document;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data generates the implementation of this interface at startup
 * (findById, save, flush, ... come from {@link JpaRepository}).
 */
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    @Query("""
            select new com.syncsphere.document.DocumentSummary(d.id, d.title, d.revision, d.updatedAt)
            from Document d
            order by d.updatedAt desc
            """)
    List<DocumentSummary> findRecentSummaries(Pageable pageable);
}
