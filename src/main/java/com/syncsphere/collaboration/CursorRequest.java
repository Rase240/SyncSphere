package com.syncsphere.collaboration;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.syncsphere.document.Document;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * STOMP payload sent to {@code /app/documents/{documentId}/cursor}: the sender's caret/selection
 * as character offsets into the document text at {@code revision}.
 */
public record CursorRequest(
        @NotNull @PositiveOrZero Long revision,
        @NotNull @PositiveOrZero @Max(Document.MAX_CONTENT_LENGTH) Integer selectionStart,
        @NotNull @PositiveOrZero @Max(Document.MAX_CONTENT_LENGTH) Integer selectionEnd) {

    @JsonIgnore
    @AssertTrue(message = "selectionEnd must be >= selectionStart")
    public boolean isRangeOrdered() {
        return selectionStart == null || selectionEnd == null || selectionEnd >= selectionStart;
    }
}
