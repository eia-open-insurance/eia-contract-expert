package com.eia.contract.expert.term;

import com.eia.contract.expert.ContractRepository;
import com.eia.contract.expert.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.eia.contract.expert.Fixtures.CONTRACT_ID;
import static com.eia.contract.expert.Fixtures.TENANT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TermController.class)
class TermControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ContractRepository contracts;

    @MockitoBean
    private TermRepository terms;

    private void contractInStatus(String status) {
        when(contracts.findById(TENANT, CONTRACT_ID)).thenReturn(Optional.of(Fixtures.contract(status, 1)));
    }

    @Test
    void listsTerms() throws Exception {
        contractInStatus("DRAFT");
        when(terms.findAll(TENANT, CONTRACT_ID)).thenReturn(List.of(
                Fixtures.term("CEDING_COMMISSION_PCT", "25.000000", "PERCENT")));

        mvc.perform(get("/internal/v1/contracts/{id}/terms", CONTRACT_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].termCode").value("CEDING_COMMISSION_PCT"))
                .andExpect(jsonPath("$[0].termValue").value("25.000000"))
                .andExpect(jsonPath("$[0].valueType").value("PERCENT"));
    }

    @Test
    void createsPercentTerm() throws Exception {
        contractInStatus("DRAFT");
        when(terms.create(any(ContractTerm.class)))
                .thenReturn(Fixtures.term("CEDING_COMMISSION_PCT", "25.000000", "PERCENT"));

        mvc.perform(post("/internal/v1/contracts/{id}/terms", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"termCode": "CEDING_COMMISSION_PCT", "termValue": "25",
                                 "sourceClause": "FOLLOW_THE_SETTLEMENTS"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.termCode").value("CEDING_COMMISSION_PCT"))
                .andExpect(jsonPath("$.termValue").value("25.000000"))
                .andExpect(jsonPath("$.valueType").value("PERCENT"));
    }

    @Test
    void rejectsUnknownTermCode() throws Exception {
        contractInStatus("DRAFT");

        mvc.perform(post("/internal/v1/contracts/{id}/terms", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"termCode": "MAGIC_TERM", "termValue": "1"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TERM_CODE"));

        verify(terms, never()).create(any());
    }

    @Test
    void rejectsPercentAboveOneHundred() throws Exception {
        contractInStatus("DRAFT");

        mvc.perform(post("/internal/v1/contracts/{id}/terms", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"termCode": "CEDING_COMMISSION_PCT", "termValue": "125"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TERM_VALUE"));
    }

    @Test
    void rejectsMalformedDateValue() throws Exception {
        contractInStatus("DRAFT");

        mvc.perform(post("/internal/v1/contracts/{id}/terms", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"termCode": "SUNSET_DATE", "termValue": "31/12/2026"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TERM_VALUE"));
    }

    @Test
    void rejectsValueTypeMismatch() throws Exception {
        contractInStatus("DRAFT");

        mvc.perform(post("/internal/v1/contracts/{id}/terms", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"termCode": "GOVERNING_LAW", "termValue": "New York",
                                 "valueType": "MONEY"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TERM_VALUE_TYPE_MISMATCH"));
    }

    @Test
    void rejectsUnknownSourceClause() throws Exception {
        contractInStatus("DRAFT");

        mvc.perform(post("/internal/v1/contracts/{id}/terms", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"termCode": "GOVERNING_LAW", "termValue": "New York",
                                 "sourceClause": "NOT_A_CLAUSE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SOURCE_CLAUSE"));
    }

    @Test
    void reportsRequiredAndMissingTermsForTheContractType() throws Exception {
        contractInStatus("PLACED");
        Set<String> present = new LinkedHashSet<>(List.of("GOVERNING_LAW"));
        when(terms.codes(TENANT, CONTRACT_ID)).thenReturn(present);

        mvc.perform(get("/internal/v1/contracts/{id}/terms/requirements", CONTRACT_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractType").value("EXCESS_OF_LOSS"))
                .andExpect(jsonPath("$.required.length()").value(3))
                .andExpect(jsonPath("$.missing.length()").value(2))
                .andExpect(jsonPath("$.missing[0]").value("REINSTATEMENT_PROVISION"))
                .andExpect(jsonPath("$.missing[1]").value("ULTIMATE_NET_LOSS"));
    }

    @Test
    void rejectsTermWhenContractIsLocked() throws Exception {
        contractInStatus("IN_FORCE");

        mvc.perform(post("/internal/v1/contracts/{id}/terms", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"termCode": "GOVERNING_LAW", "termValue": "New York"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTRACT_LOCKED"));
    }
}
