package com.syncsphere.document;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * REST API for things that are naturally request/response: creating, listing and loading documents.
 * Live editing does NOT go through here; it uses STOMP (see CollaborationController).
 *
 * <p>The {@code {id}} path variable is declared as {@link UUID}, so Spring rejects malformed IDs
 * with HTTP 400 before this code runs (handled in {@code RestExceptionHandler}).
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping
    public ResponseEntity<DocumentSnapshot> create(@Valid @RequestBody CreateDocumentRequest request) {
        DocumentSnapshot created = documentService.createDocument(request.title(), request.contentOrEmpty());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping
    public List<DocumentSummary> list() {
        return documentService.listRecentDocuments();
    }

    @GetMapping("/{id}")
    public DocumentSnapshot get(@PathVariable UUID id) {
        return documentService.getDocument(id);
    }
}
