package org.booklore.config.security.filter;

import org.booklore.config.security.JwtUtils;
import org.booklore.mapper.custom.BookLoreUserTransformer;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.repository.UserRepository;
import org.booklore.service.ApiTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** API tokens on the streaming endpoints: the reader app plays audiobooks with one. */
@ExtendWith(MockitoExtension.class)
class StreamingApiTokenAuthenticatorTest {

    private static final String TOKEN = "blt_abc123";

    @Mock private ApiTokenService apiTokenService;
    @Mock private BookLoreUserTransformer transformer;
    @Mock private JwtUtils jwtUtils;
    @Mock private UserRepository userRepository;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private StreamingApiTokenAuthenticator authenticator() {
        return new StreamingApiTokenAuthenticator(apiTokenService, transformer);
    }

    private MockHttpServletRequest get(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }

    private void tokenBelongsToUser() {
        BookLoreUserEntity entity = new BookLoreUserEntity();
        when(apiTokenService.authenticate(TOKEN)).thenReturn(Optional.of(entity));
        when(transformer.toDTO(entity)).thenReturn(BookLoreUser.builder().id(5L).build());
    }

    @Test
    void aTokenInTheHeaderSignsInAsItsOwner() throws Exception {
        tokenBelongsToUser();
        var response = new MockHttpServletResponse();

        var outcome = authenticator().authenticate(TOKEN, null, get("/api/v1/audiobooks/1/stream"), response);

        assertThat(outcome).isEqualTo(StreamingApiTokenAuthenticator.Outcome.AUTHENTICATED);
        assertThat(((BookLoreUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getId()).isEqualTo(5L);
    }

    @Test
    void aTokenInTheUrlIsRefused() throws Exception {
        var response = new MockHttpServletResponse();

        var outcome = authenticator().authenticate(null, TOKEN, get("/api/v1/audiobooks/1/stream"), response);

        assertThat(outcome).isEqualTo(StreamingApiTokenAuthenticator.Outcome.REJECTED);
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(apiTokenService);
    }

    @Test
    void aTokenCantWrite() throws Exception {
        var response = new MockHttpServletResponse();

        var outcome = authenticator().authenticate(TOKEN, null, new MockHttpServletRequest("POST", "/api/v1/epub/1/file/x"), response);

        assertThat(outcome).isEqualTo(StreamingApiTokenAuthenticator.Outcome.REJECTED);
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void aRevokedTokenIsRefused() throws Exception {
        when(apiTokenService.authenticate(TOKEN)).thenReturn(Optional.empty());
        var response = new MockHttpServletResponse();

        assertThat(authenticator().authenticate(TOKEN, null, get("/api/v1/audiobooks/1/cover"), response))
                .isEqualTo(StreamingApiTokenAuthenticator.Outcome.REJECTED);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void aLoginTokenIsLeftToTheJwtCheck() throws Exception {
        assertThat(authenticator().authenticate("eyJhbGciOi.x.y", null, get("/api/v1/audiobooks/1/stream"), new MockHttpServletResponse()))
                .isEqualTo(StreamingApiTokenAuthenticator.Outcome.NOT_AN_API_TOKEN);
        verifyNoInteractions(apiTokenService);
    }

    @Test
    void theAudiobookFilterPlaysATrackForAnApiToken() throws Exception {
        tokenBelongsToUser();
        var filter = new AudiobookStreamingJwtFilter(jwtUtils, userRepository, transformer, authenticator());
        var request = get("/api/v1/audiobooks/12/track/3/stream");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        var chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
        verify(jwtUtils, never()).validateAccessToken(any());
    }
}
