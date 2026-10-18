package com.syncsphere.web;

import com.syncsphere.collaboration.PresenceService;
import com.syncsphere.document.DocumentController;
import com.syncsphere.document.DocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice test (no database, no Redis): the services are replaced by mocks that throw the exact
 * exceptions observed when the postgres / redis containers were stopped, and we check the HTTP mapping.
 *
 * <p>Mocks are appropriate here because the behaviour under test is the controller advice, not the services.
 */
@WebMvcTest(DocumentController.class)
class DataStoreOutageResponseTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentService documentService;

    @MockitoBean
    private PresenceService presenceService;

    @Test
    void databaseUnreachableBeforeTheTransactionStartsReturns503() throws Exception {
        when(documentService.getDocument(any())).thenThrow(
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"));

        mockMvc.perform(get("/api/documents/" + UUID.randomUUID()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("A data store is temporarily unavailable. Please retry."));
    }

    @Test
    void redisUnreachableReturns503() throws Exception {
        when(presenceService.listParticipants(any())).thenThrow(new RedisConnectionFailureException("refused"));

        mockMvc.perform(get("/api/documents/" + UUID.randomUUID() + "/participants"))
                .andExpect(status().isServiceUnavailable());
    }
}
