package com.syncsphere.collaboration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * STOMP payload sent to {@code /app/documents/{documentId}/join}.
 * The display name is restricted to letters, digits, spaces and . _ ' - so it is safe to show anywhere.
 */
public record JoinRequest(
        @NotBlank(message = "displayName must not be blank")
        @Size(max = 40, message = "displayName must be at most 40 characters")
        @Pattern(regexp = "^[\\p{L}\\p{N} ._'-]+$",
                message = "displayName may only contain letters, digits, spaces and . _ ' -")
        String displayName) {
}
