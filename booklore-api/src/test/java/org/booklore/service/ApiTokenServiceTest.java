package org.booklore.service;

import org.booklore.exception.APIException;
import org.booklore.model.dto.response.ApiTokenCreatedResponse;
import org.booklore.model.dto.response.ApiTokenSummary;
import org.booklore.model.entity.ApiTokenEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.repository.ApiTokenRepository;
import org.booklore.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ApiTokenServiceTest {

    private static final Long USER_ID = 42L;

    private ApiTokenRepository apiTokenRepository;
    private UserRepository userRepository;
    private ApiTokenService apiTokenService;

    @BeforeEach
    void setUp() {
        apiTokenRepository = mock(ApiTokenRepository.class);
        userRepository = mock(UserRepository.class);
        apiTokenService = new ApiTokenService(apiTokenRepository, userRepository);
    }

    @Test
    void createToken_returnsRawTokenWithBltPrefix_andPersistsOnlyItsHash() {
        BookLoreUserEntity user = BookLoreUserEntity.builder().id(USER_ID).build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(apiTokenRepository.save(any(ApiTokenEntity.class))).thenAnswer(inv -> {
            ApiTokenEntity e = inv.getArgument(0);
            e.setId(1L);
            e.setCreatedAt(Instant.now());
            return e;
        });

        ApiTokenCreatedResponse response = apiTokenService.createToken(USER_ID, "My Reader App");

        assertThat(response.getToken()).startsWith(ApiTokenService.TOKEN_PREFIX);
        assertThat(response.getName()).isEqualTo("My Reader App");
        assertThat(response.getId()).isEqualTo(1L);

        ArgumentCaptor<ApiTokenEntity> captor = ArgumentCaptor.forClass(ApiTokenEntity.class);
        verify(apiTokenRepository).save(captor.capture());
        // The raw token is never persisted verbatim - only its hash.
        assertThat(captor.getValue().getTokenHash()).isNotEqualTo(response.getToken());
        assertThat(captor.getValue().getTokenHash()).hasSize(64); // SHA-256 hex
    }

    @Test
    void createToken_throwsWhenUserNotFound() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> apiTokenService.createToken(USER_ID, "App"))
                .isInstanceOf(APIException.class);
    }

    @Test
    void authenticate_returnsEmptyForTokenWithoutBltPrefix() {
        assertThat(apiTokenService.authenticate("not-an-api-token")).isEmpty();
        assertThat(apiTokenService.authenticate(null)).isEmpty();
        verifyNoInteractions(apiTokenRepository);
    }

    @Test
    void authenticate_returnsEmptyForUnknownToken() {
        when(apiTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());
        assertThat(apiTokenService.authenticate(ApiTokenService.TOKEN_PREFIX + "unknown")).isEmpty();
    }

    @Test
    void authenticate_returnsEmptyForRevokedToken() {
        BookLoreUserEntity user = BookLoreUserEntity.builder().id(USER_ID).build();
        ApiTokenEntity revoked = ApiTokenEntity.builder().id(1L).user(user).revokedAt(Instant.now()).build();
        when(apiTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(revoked));

        assertThat(apiTokenService.authenticate(ApiTokenService.TOKEN_PREFIX + "revoked")).isEmpty();
    }

    @Test
    void authenticate_returnsUserAndTouchesLastUsedAt_forValidActiveToken() {
        // The token holds only a reference to its user; the full user (permissions, libraries,
        // settings) is loaded by id so it can still be read after the transaction ends.
        BookLoreUserEntity reference = BookLoreUserEntity.builder().id(USER_ID).build();
        BookLoreUserEntity user = BookLoreUserEntity.builder().id(USER_ID).username("reader").build();
        ApiTokenEntity active = ApiTokenEntity.builder().id(1L).user(reference).build();
        when(apiTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(active));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        Optional<BookLoreUserEntity> result = apiTokenService.authenticate(ApiTokenService.TOKEN_PREFIX + "valid");

        assertThat(result).containsSame(user);
        ArgumentCaptor<ApiTokenEntity> captor = ArgumentCaptor.forClass(ApiTokenEntity.class);
        verify(apiTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getLastUsedAt()).isNotNull();
    }

    @Test
    void listTokens_excludesRevokedTokens() {
        BookLoreUserEntity user = BookLoreUserEntity.builder().id(USER_ID).build();
        ApiTokenEntity active = ApiTokenEntity.builder().id(1L).user(user).name("Active").createdAt(Instant.now()).build();
        ApiTokenEntity revoked = ApiTokenEntity.builder().id(2L).user(user).name("Revoked").createdAt(Instant.now()).revokedAt(Instant.now()).build();
        when(apiTokenRepository.findAllByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(active, revoked));

        List<ApiTokenSummary> summaries = apiTokenService.listTokens(USER_ID);

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).getName()).isEqualTo("Active");
    }

    @Test
    void revokeToken_deletesWhenOwnedByCaller() {
        BookLoreUserEntity user = BookLoreUserEntity.builder().id(USER_ID).build();
        ApiTokenEntity token = ApiTokenEntity.builder().id(1L).user(user).build();
        when(apiTokenRepository.findById(1L)).thenReturn(Optional.of(token));

        apiTokenService.revokeToken(USER_ID, 1L);

        verify(apiTokenRepository).delete(token);
    }

    @Test
    void revokeToken_throwsForbiddenWhenNotOwnedByCaller() {
        BookLoreUserEntity owner = BookLoreUserEntity.builder().id(USER_ID).build();
        ApiTokenEntity token = ApiTokenEntity.builder().id(1L).user(owner).build();
        when(apiTokenRepository.findById(1L)).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> apiTokenService.revokeToken(999L, 1L))
                .isInstanceOf(APIException.class);
        verify(apiTokenRepository, never()).delete(any());
    }

    @Test
    void revokeToken_throwsNotFoundForMissingToken() {
        when(apiTokenRepository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> apiTokenService.revokeToken(USER_ID, 1L))
                .isInstanceOf(APIException.class);
    }
}
