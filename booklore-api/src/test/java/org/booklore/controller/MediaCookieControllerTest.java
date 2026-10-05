package org.booklore.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MediaCookieControllerTest {

    @Test
    void cookieIsHttpOnlyLaxAndOnlySentToMediaPaths() {
        MockHttpServletRequest https = new MockHttpServletRequest();
        https.setSecure(true);

        ResponseCookie cookie = MediaCookieController.cookie("tok", Duration.ofDays(30), https);

        assertThat(cookie.getName()).isEqualTo("trove_media");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/api/v1/media");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void notMarkedSecureOverPlainHttp_andLogoutExpiresIt() {
        MockHttpServletRequest http = new MockHttpServletRequest();

        assertThat(MediaCookieController.cookie("tok", Duration.ofDays(1), http).isSecure()).isFalse();
        ResponseCookie expired = MediaCookieController.expired(http);
        assertThat(expired.getMaxAge()).isEqualTo(Duration.ZERO);
        assertThat(expired.getValue()).isEmpty();
    }
}
