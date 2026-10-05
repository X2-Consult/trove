package org.booklore.config.security.filter;

import jakarta.servlet.http.Cookie;
import org.booklore.config.security.JwtUtils;
import org.booklore.mapper.custom.BookLoreUserTransformer;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.repository.UserRepository;
import org.booklore.service.security.JwtSecretService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoverJwtFilterTest {

    private JwtUtils jwtUtils;
    private CoverJwtFilter filter;
    private final BookLoreUserEntity user = BookLoreUserEntity.builder().id(7L).username("reader").build();

    @BeforeEach
    void setUp() {
        JwtSecretService secretService = mock(JwtSecretService.class);
        when(secretService.getSecret()).thenReturn("test-secret-that-is-long-enough-for-hs256-signing!!");
        jwtUtils = new JwtUtils(secretService);
        UserRepository users = mock(UserRepository.class);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        BookLoreUserTransformer transformer = mock(BookLoreUserTransformer.class);
        when(transformer.toDTO(any())).thenReturn(BookLoreUser.builder().id(7L).username("reader").build());
        filter = new CoverJwtFilter(jwtUtils, users, transformer);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletResponse request(String uri, String queryToken, String cookieToken) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (queryToken != null) {
            request.setParameter("token", queryToken);
        }
        if (cookieToken != null) {
            request.setCookies(new Cookie("trove_media", cookieToken));
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void mediaCookieOpensCoversThumbnailsAndAuthorPhotos() throws Exception {
        String media = jwtUtils.generateMediaToken(user);
        for (String uri : new String[]{"/api/v1/media/book/5/thumbnail", "/api/v1/media/book/5/cover",
                "/api/v1/media/book/5/audiobook-thumbnail", "/api/v1/media/author/3/photo"}) {
            assertThat(request(uri, null, media).getStatus()).as(uri).isEqualTo(200);
        }
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void mediaCookieDoesNotOpenComicPages() throws Exception {
        assertThat(request("/api/v1/media/book/5/cbx/pages/1", null, jwtUtils.generateMediaToken(user)).getStatus()).isEqualTo(401);
    }

    @Test
    void anAccessOrRefreshTokenInTheCookieIsRefused() throws Exception {
        assertThat(request("/api/v1/media/book/5/thumbnail", null, jwtUtils.generateAccessToken(user)).getStatus()).isEqualTo(401);
        assertThat(request("/api/v1/media/book/5/thumbnail", null, jwtUtils.generateRefreshToken(user)).getStatus()).isEqualTo(401);
    }

    @Test
    void theQueryTokenStillWorksEverywhere() throws Exception {
        String access = jwtUtils.generateAccessToken(user);
        assertThat(request("/api/v1/media/book/5/thumbnail", access, null).getStatus()).isEqualTo(200);
        assertThat(request("/api/v1/media/book/5/cbx/pages/1", access, null).getStatus()).isEqualTo(200);
        assertThat(request("/api/v1/media/book/5/cbx/pages/1", jwtUtils.generateMediaToken(user), null).getStatus()).isEqualTo(401);
    }

    @Test
    void noCredentialsIsRefused() throws Exception {
        assertThat(request("/api/v1/media/book/5/thumbnail", null, null).getStatus()).isEqualTo(401);
    }
}
