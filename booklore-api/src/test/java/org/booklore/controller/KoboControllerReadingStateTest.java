package org.booklore.controller;

import org.booklore.model.dto.kobo.KoboReadingState;
import org.booklore.model.dto.kobo.KoboReadingStateList;
import org.booklore.model.dto.kobo.KoboReadingStateRequest;
import org.booklore.model.dto.response.kobo.KoboReadingStateResponse;
import org.booklore.service.kobo.KoboBookAccessService;
import org.booklore.service.kobo.KoboReadingStateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * A Kobo keeps reporting progress for books it holds after they're deleted from Trove, and treats
 * any error as a failed sync. Such progress must be acknowledged, not refused.
 */
@ExtendWith(MockitoExtension.class)
class KoboControllerReadingStateTest {

    @Mock
    private KoboBookAccessService koboBookAccessService;
    @Mock
    private KoboReadingStateService koboReadingStateService;

    @InjectMocks
    private KoboController controller;

    private static KoboReadingState state(String entitlementId) {
        return KoboReadingState.builder().entitlementId(entitlementId).build();
    }

    private static KoboReadingStateResponse saved(List<KoboReadingState> states) {
        return KoboReadingStateResponse.builder()
                .requestResult("Success")
                .updateResults(new ArrayList<>(states.stream()
                        .map(s -> KoboReadingStateResponse.UpdateResult.builder().entitlementId(s.getEntitlementId()).build())
                        .toList()))
                .build();
    }

    @Test
    void progressForADeletedBookIsAcknowledgedButNotSaved() {
        when(koboBookAccessService.canRead(7L)).thenReturn(false);
        when(koboReadingStateService.saveReadingState(anyList())).thenAnswer(inv -> saved(inv.getArgument(0)));

        ResponseEntity<?> response = controller.updateState("7", KoboReadingStateRequest.builder().readingStates(List.of(state("7"))).build());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(koboReadingStateService).saveReadingState(List.of());
        KoboReadingStateResponse body = (KoboReadingStateResponse) response.getBody();
        assertThat(body.getUpdateResults()).extracting(KoboReadingStateResponse.UpdateResult::getEntitlementId).containsExactly("7");
        assertThat(body.getUpdateResults().getFirst().getStatusInfoResult().getResult()).isEqualTo("Success");
    }

    @Test
    void onlyTheReadableBooksInABatchAreSaved() {
        when(koboBookAccessService.canRead(7L)).thenReturn(true);
        when(koboBookAccessService.canRead(8L)).thenReturn(false);
        when(koboReadingStateService.saveReadingState(anyList())).thenAnswer(inv -> saved(inv.getArgument(0)));

        ResponseEntity<?> response = controller.updateState("7", KoboReadingStateRequest.builder()
                .readingStates(List.of(state("7"), state("8"))).build());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KoboReadingState>> savedStates = ArgumentCaptor.forClass(List.class);
        verify(koboReadingStateService).saveReadingState(savedStates.capture());
        assertThat(savedStates.getValue()).extracting(KoboReadingState::getEntitlementId).containsExactly("7");
        assertThat(((KoboReadingStateResponse) response.getBody()).getUpdateResults())
                .extracting(KoboReadingStateResponse.UpdateResult::getEntitlementId).containsExactlyInAnyOrder("7", "8");
    }

    @Test
    void stateOfADeletedBookIsEmptyRatherThanAnError() {
        when(koboBookAccessService.canRead(7L)).thenReturn(false);

        ResponseEntity<?> response = controller.getState("7");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat((KoboReadingStateList) response.getBody()).isEmpty();
        verifyNoInteractions(koboReadingStateService);
    }
}
