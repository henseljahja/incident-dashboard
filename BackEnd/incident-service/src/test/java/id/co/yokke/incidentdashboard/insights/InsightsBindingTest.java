package id.co.yokke.incidentdashboard.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.yokke.incidentdashboard.domain.model.IncidentFilter;
import id.co.yokke.incidentdashboard.domain.usecase.insights.InsightsUseCase;
import id.co.yokke.incidentdashboard.infrastructure.rest.InsightsController;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The insight endpoints bind the shared filter query string, including the list filters. */
class InsightsBindingTest {

    @Test
    @DisplayName("repeated params, ISO dates, search/source/assignedTo/statusName reach the filter")
    void bindsTheFilterQueryString() throws Exception {
        InsightsUseCase useCase = mock(InsightsUseCase.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new InsightsController(useCase)).build();

        mvc.perform(get("/api/insights/assignees")
                        .param("dateFrom", "2026-01-01").param("dateTo", "2026-03-31")
                        .param("priority", "High").param("priority", "Critical")
                        .param("search", "  vpn ").param("source", "External")
                        .param("assignedTo", "Budi").param("statusName", "Closed Wkaround")
                        .param("scope", "problem").param("limit", "5"))
                .andExpect(status().isOk());

        ArgumentCaptor<IncidentFilter> captor = ArgumentCaptor.forClass(IncidentFilter.class);
        verify(useCase).assignees(captor.capture(), anyInt());
        IncidentFilter filter = captor.getValue();
        assertThat(filter.dateFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(filter.dateTo()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(filter.priorities()).containsExactly("High", "Critical");
        assertThat(filter.search()).isEqualTo("vpn");
        assertThat(filter.sources()).containsExactly("External");
        assertThat(filter.assignees()).containsExactly("Budi");
        assertThat(filter.statusNames()).containsExactly("Closed Wkaround");
        assertThat(filter.scope()).isNotNull();
    }

    @Test
    @DisplayName("an unknown scope is rejected, never silently widened")
    void validationStillApplies() {
        InsightsUseCase useCase = mock(InsightsUseCase.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new InsightsController(useCase)).build();

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> mvc.perform(get("/api/insights/home").param("scope", "bogus")));
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("search and ask require a q parameter")
    void requiresQuery() throws Exception {
        InsightsUseCase useCase = mock(InsightsUseCase.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new InsightsController(useCase)).build();

        mvc.perform(get("/api/insights/ai/search")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/insights/ai/ask")).andExpect(status().isBadRequest());
    }
}
