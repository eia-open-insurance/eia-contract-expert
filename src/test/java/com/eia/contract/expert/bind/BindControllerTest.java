package com.eia.contract.expert.bind;

import com.eia.contract.expert.ContractRepository;
import com.eia.contract.expert.Fixtures;
import com.eia.contract.expert.layer.LayerRepository;
import com.eia.contract.expert.participant.ParticipantRepository;
import com.eia.contract.expert.security.ContractSecurity;
import com.eia.contract.expert.security.SecurityRepository;
import com.eia.contract.expert.term.TermRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.eia.contract.expert.Fixtures.APPROVER;
import static com.eia.contract.expert.Fixtures.CONTRACT_ID;
import static com.eia.contract.expert.Fixtures.LAYER_ID;
import static com.eia.contract.expert.Fixtures.TENANT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BindController.class)
@Import(ContractBindService.class)
class BindControllerTest {

    private static final Set<String> ALL_TERMS = new LinkedHashSet<>(List.of(
            "GOVERNING_LAW", "REINSTATEMENT_PROVISION", "ULTIMATE_NET_LOSS"));

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ContractRepository contracts;

    @MockitoBean
    private LayerRepository layers;

    @MockitoBean
    private ParticipantRepository participants;

    @MockitoBean
    private TermRepository terms;

    @MockitoBean
    private SecurityRepository securities;

    private void placedWithCompleteData() {
        when(contracts.findById(TENANT, CONTRACT_ID)).thenReturn(Optional.of(Fixtures.contract("PLACED", 2)));
        when(layers.findLive(TENANT, CONTRACT_ID))
                .thenReturn(List.of(Fixtures.layer(LAYER_ID, 1, "0", "1000000")));
        when(participants.activeShareTotals(TENANT, CONTRACT_ID)).thenReturn(List.of(
                new ParticipantRepository.ShareTotal(LAYER_ID, new BigDecimal("100.000000"), 2)));
        when(terms.codes(TENANT, CONTRACT_ID)).thenReturn(ALL_TERMS);
        when(securities.findAll(TENANT, CONTRACT_ID)).thenReturn(List.of());
    }

    @Test
    void reportsBindableWhenEveryPreconditionPasses() throws Exception {
        placedWithCompleteData();

        mvc.perform(get("/internal/v1/contracts/{id}/bind-eligibility", CONTRACT_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bindable").value(true))
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.checks.length()").value(5))
                .andExpect(jsonPath("$.checks[0].code").value("CONTRACT_PLACED"))
                .andExpect(jsonPath("$.checks[0].passed").value(true))
                .andExpect(jsonPath("$.checks[1].code").value("LAYERS_COHERENT"))
                .andExpect(jsonPath("$.checks[2].code").value("SHARES_BALANCED"))
                .andExpect(jsonPath("$.checks[3].code").value("REQUIRED_TERMS_PRESENT"))
                .andExpect(jsonPath("$.checks[4].code").value("SECURITY_NOT_BREACHED"));
    }

    @Test
    void reportsUnbalancedShares() throws Exception {
        placedWithCompleteData();
        when(participants.activeShareTotals(TENANT, CONTRACT_ID)).thenReturn(List.of(
                new ParticipantRepository.ShareTotal(LAYER_ID, new BigDecimal("80.000000"), 1)));

        mvc.perform(get("/internal/v1/contracts/{id}/bind-eligibility", CONTRACT_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bindable").value(false))
                .andExpect(jsonPath("$.checks[2].code").value("SHARES_BALANCED"))
                .andExpect(jsonPath("$.checks[2].passed").value(false));
    }

    @Test
    void reportsMissingRequiredTerm() throws Exception {
        placedWithCompleteData();
        when(terms.codes(TENANT, CONTRACT_ID))
                .thenReturn(new LinkedHashSet<>(List.of("GOVERNING_LAW")));

        mvc.perform(get("/internal/v1/contracts/{id}/bind-eligibility", CONTRACT_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bindable").value(false))
                .andExpect(jsonPath("$.checks[3].passed").value(false));
    }

    @Test
    void reportsSecurityBreach() throws Exception {
        placedWithCompleteData();
        ContractSecurity breach = Fixtures.security("5000000", "1000000", "BREACH");
        when(securities.findAll(TENANT, CONTRACT_ID)).thenReturn(List.of(breach));

        mvc.perform(get("/internal/v1/contracts/{id}/bind-eligibility", CONTRACT_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bindable").value(false))
                .andExpect(jsonPath("$.checks[4].code").value("SECURITY_NOT_BREACHED"))
                .andExpect(jsonPath("$.checks[4].passed").value(false));
    }

    @Test
    void reportsContractThatIsNotPlaced() throws Exception {
        placedWithCompleteData();
        when(contracts.findById(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(Fixtures.contract("QUOTED", 1)));

        mvc.perform(get("/internal/v1/contracts/{id}/bind-eligibility", CONTRACT_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bindable").value(false))
                .andExpect(jsonPath("$.checks[0].passed").value(false));
    }

    @Test
    void bindsWhenPreconditionsPass() throws Exception {
        when(contracts.findByIdForUpdate(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(Fixtures.contract("PLACED", 2)));
        when(layers.findLive(TENANT, CONTRACT_ID))
                .thenReturn(List.of(Fixtures.layer(LAYER_ID, 1, "0", "1000000")));
        when(participants.activeShareTotals(TENANT, CONTRACT_ID)).thenReturn(List.of(
                new ParticipantRepository.ShareTotal(LAYER_ID, new BigDecimal("100.000000"), 2)));
        when(terms.codes(TENANT, CONTRACT_ID)).thenReturn(ALL_TERMS);
        when(securities.findAll(TENANT, CONTRACT_ID)).thenReturn(List.of());
        when(contracts.bind(TENANT, CONTRACT_ID, APPROVER, 2))
                .thenReturn(Fixtures.boundContract());

        mvc.perform(post("/internal/v1/contracts/{id}/bind", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedBy\": \"" + APPROVER + "\", \"expectedVersion\": 2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contract.status").value("BOUND"))
                .andExpect(jsonPath("$.contract.boundBy").value(APPROVER.toString()))
                .andExpect(jsonPath("$.eligibility.bindable").value(true));

        verify(contracts).bind(TENANT, CONTRACT_ID, APPROVER, 2);
    }

    @Test
    void rejectsBindWithoutApprover() throws Exception {
        when(contracts.findByIdForUpdate(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(Fixtures.contract("PLACED", 2)));

        mvc.perform(post("/internal/v1/contracts/{id}/bind", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\": 2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BIND_APPROVAL_REQUIRED"));

        verify(contracts, never()).bind(any(), any(), any(), anyInt());
    }

    @Test
    void rejectsBindWhenSharesAreUnbalanced() throws Exception {
        when(contracts.findByIdForUpdate(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(Fixtures.contract("PLACED", 2)));
        when(layers.findLive(TENANT, CONTRACT_ID))
                .thenReturn(List.of(Fixtures.layer(LAYER_ID, 1, "0", "1000000")));
        when(participants.activeShareTotals(TENANT, CONTRACT_ID)).thenReturn(List.of(
                new ParticipantRepository.ShareTotal(LAYER_ID, new BigDecimal("75.000000"), 1)));
        when(terms.codes(TENANT, CONTRACT_ID)).thenReturn(ALL_TERMS);
        when(securities.findAll(TENANT, CONTRACT_ID)).thenReturn(List.of());

        mvc.perform(post("/internal/v1/contracts/{id}/bind", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedBy\": \"" + APPROVER + "\", \"expectedVersion\": 2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BIND_PRECONDITION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("SHARES_BALANCED"));

        verify(contracts, never()).bind(any(), any(), any(), anyInt());
    }

    @Test
    void rejectsBindWhenRequiredTermIsMissing() throws Exception {
        when(contracts.findByIdForUpdate(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(Fixtures.contract("PLACED", 2)));
        when(layers.findLive(TENANT, CONTRACT_ID)).thenReturn(List.of());
        when(participants.activeShareTotals(TENANT, CONTRACT_ID)).thenReturn(List.of(
                new ParticipantRepository.ShareTotal(null, new BigDecimal("100.000000"), 2)));
        when(terms.codes(TENANT, CONTRACT_ID)).thenReturn(new LinkedHashSet<>(List.of("GOVERNING_LAW")));
        when(securities.findAll(TENANT, CONTRACT_ID)).thenReturn(List.of());

        mvc.perform(post("/internal/v1/contracts/{id}/bind", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedBy\": \"" + APPROVER + "\", \"expectedVersion\": 2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BIND_PRECONDITION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("REQUIRED_TERMS_PRESENT"))
                .andExpect(jsonPath("$.details[0].message").value(
                        org.hamcrest.Matchers.containsString("ULTIMATE_NET_LOSS")));
    }

    @Test
    void rejectsBindWhenNoParticipantIsRecorded() throws Exception {
        when(contracts.findByIdForUpdate(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(Fixtures.contract("PLACED", 2)));
        when(layers.findLive(TENANT, CONTRACT_ID)).thenReturn(List.of());
        when(participants.activeShareTotals(TENANT, CONTRACT_ID)).thenReturn(List.of());
        when(terms.codes(TENANT, CONTRACT_ID)).thenReturn(ALL_TERMS);
        when(securities.findAll(TENANT, CONTRACT_ID)).thenReturn(List.of());

        mvc.perform(post("/internal/v1/contracts/{id}/bind", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedBy\": \"" + APPROVER + "\", \"expectedVersion\": 2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BIND_PRECONDITION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("SHARES_BALANCED"));
    }

    @Test
    void rejectsBindWhenContractIsNotPlaced() throws Exception {
        when(contracts.findByIdForUpdate(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(Fixtures.contract("DRAFT", 0)));
        when(layers.findLive(TENANT, CONTRACT_ID)).thenReturn(List.of());
        when(participants.activeShareTotals(TENANT, CONTRACT_ID)).thenReturn(List.of(
                new ParticipantRepository.ShareTotal(null, new BigDecimal("100.000000"), 1)));
        when(terms.codes(TENANT, CONTRACT_ID)).thenReturn(ALL_TERMS);
        when(securities.findAll(TENANT, CONTRACT_ID)).thenReturn(List.of());

        mvc.perform(post("/internal/v1/contracts/{id}/bind", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedBy\": \"" + APPROVER + "\", \"expectedVersion\": 0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BIND_PRECONDITION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("CONTRACT_PLACED"));
    }

    @Test
    void returnsNotFoundForUnknownContract() throws Exception {
        when(contracts.findById(TENANT, CONTRACT_ID)).thenReturn(Optional.empty());

        mvc.perform(get("/internal/v1/contracts/{id}/bind-eligibility", CONTRACT_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
