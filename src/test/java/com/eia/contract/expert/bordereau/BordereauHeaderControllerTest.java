package com.eia.contract.expert.bordereau;

import com.eia.contract.expert.BusinessKeys;
import com.eia.contract.expert.ContractRepository;
import com.eia.contract.expert.Fixtures;
import com.eia.contract.expert.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.eia.contract.expert.Fixtures.CONTRACT_ID;
import static com.eia.contract.expert.Fixtures.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BordereauHeaderController.class)
class BordereauHeaderControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ContractRepository contracts;

    @MockitoBean
    private BordereauHeaderRepository bordereaux;

    @MockitoBean
    private BusinessKeys keys;

    private void contractInStatus(String status) {
        when(contracts.findById(TENANT, CONTRACT_ID)).thenReturn(Optional.of(Fixtures.contract(status, 1)));
    }

    private static String body() {
        return """
                {
                  "bordereauType": "CLAIMS",
                  "periodFrom": "2026-01-01",
                  "periodTo": "2026-03-31",
                  "sourceFormat": "CSV",
                  "lineCount": 10,
                  "validLineCount": 9,
                  "exceptionCount": 1,
                  "totalAmount": "240000"
                }
                """;
    }

    @Test
    void listsBordereaux() throws Exception {
        contractInStatus("BOUND");
        when(bordereaux.findAll(TENANT, CONTRACT_ID)).thenReturn(List.of(Fixtures.bordereauHeader()));

        mvc.perform(get("/internal/v1/contracts/{id}/bordereaux", CONTRACT_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bordereauNumber").value("BRD-2026-00001"))
                .andExpect(jsonPath("$[0].bordereauType").value("CLAIMS"))
                .andExpect(jsonPath("$[0].lineCount").value(10))
                .andExpect(jsonPath("$[0].totalAmount").value("240000"));
    }

    @Test
    void returnsOneBordereau() throws Exception {
        contractInStatus("BOUND");
        when(bordereaux.get(eq(TENANT), eq(CONTRACT_ID), any(UUID.class)))
                .thenReturn(Fixtures.bordereauHeader());

        mvc.perform(get("/internal/v1/contracts/{id}/bordereaux/{bordereauId}", CONTRACT_ID,
                        "99999999-9999-7999-8999-999999999999")
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    @Test
    void reportsUnknownBordereau() throws Exception {
        contractInStatus("BOUND");
        when(bordereaux.get(eq(TENANT), eq(CONTRACT_ID), any(UUID.class)))
                .thenThrow(new ResourceNotFoundException("Bordereau",
                        UUID.fromString("99999999-9999-7999-8999-999999999999")));

        mvc.perform(get("/internal/v1/contracts/{id}/bordereaux/{bordereauId}", CONTRACT_ID,
                        "99999999-9999-7999-8999-999999999999")
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void registersBordereauWithGeneratedNumber() throws Exception {
        contractInStatus("BOUND");
        when(keys.next(TENANT, "BRD", 2026)).thenReturn("BRD-2026-00042");
        when(bordereaux.create(any(ContractBordereauHeader.class))).thenReturn(Fixtures.bordereauHeader());

        mvc.perform(post("/internal/v1/contracts/{id}/bordereaux", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bordereauNumber").value("BRD-2026-00001"));

        ArgumentCaptor<ContractBordereauHeader> captor = ArgumentCaptor.forClass(ContractBordereauHeader.class);
        verify(bordereaux).create(captor.capture());
        assertThat(captor.getValue().bordereauNumber()).isEqualTo("BRD-2026-00042");
        assertThat(captor.getValue().status()).isEqualTo("RECEIVED");
        assertThat(captor.getValue().currency()).isEqualTo("USD");
    }

    @Test
    void rejectsInvertedPeriod() throws Exception {
        contractInStatus("BOUND");

        mvc.perform(post("/internal/v1/contracts/{id}/bordereaux", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body().replace("\"periodTo\": \"2026-03-31\"", "\"periodTo\": \"2025-12-31\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));

        verify(bordereaux, never()).create(any());
    }

    @Test
    void rejectsValidLinesAboveLineCount() throws Exception {
        contractInStatus("BOUND");

        mvc.perform(post("/internal/v1/contracts/{id}/bordereaux", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body().replace("\"validLineCount\": 9", "\"validLineCount\": 11")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_LINE_COUNT"));
    }

    @Test
    void rejectsUnknownSourceFormat() throws Exception {
        contractInStatus("BOUND");

        mvc.perform(post("/internal/v1/contracts/{id}/bordereaux", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body().replace("\"CSV\"", "\"PAPYRUS\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SOURCE_FORMAT"));
    }

    @Test
    void rejectsCurrencyMismatch() throws Exception {
        contractInStatus("BOUND");

        mvc.perform(post("/internal/v1/contracts/{id}/bordereaux", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body().replace("\"totalAmount\"", "\"currency\": \"EUR\", \"totalAmount\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BORDEREAU_CURRENCY_MISMATCH"));
    }

    @Test
    void rejectsBordereauWhenContractIsUnknown() throws Exception {
        when(contracts.findById(TENANT, CONTRACT_ID)).thenReturn(Optional.empty());

        mvc.perform(post("/internal/v1/contracts/{id}/bordereaux", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
