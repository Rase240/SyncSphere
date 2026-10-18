package com.syncsphere.document;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/documents}. Validated by {@code @Valid} in {@link DocumentController};
 * a violation produces HTTP 400 before any service code runs.
 *
 * @param content optional initial text; {@code null} means an empty document
 */
public record CreateDocumentRequest(
        @NotBlank(message = "title must not be blank")
        @Size(max = Document.MAX_TITLE_LENGTH, message = "title must be at most 120 characters")
        @Pattern(regexp = Document.NO_NUL_CHARACTERS, message = "title must not contain NUL characters")
        String title,

        @Size(max = Document.MAX_CONTENT_LENGTH, message = "content must be at most 50000 characters")
        @Pattern(regexp = Document.NO_NUL_CHARACTERS, message = "content must not contain NUL characters")
        String content) {

    public String contentOrEmpty() {
        return content == null ? "" : content;
    }
}
