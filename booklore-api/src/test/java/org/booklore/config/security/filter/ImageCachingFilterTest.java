package org.booklore.config.security.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ImageCachingFilterTest {

    private String cacheControl(String uri, String query) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setQueryString(query);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new ImageCachingFilter().doFilter(request, response, new MockFilterChain());
        return response.getHeader("Cache-Control");
    }

    @Test
    void versionedCoverUrlsAreKeptForAYear() throws Exception {
        assertThat(cacheControl("/api/v1/media/book/12/thumbnail", "2026-10-04T10:00:00Z&token=abc"))
                .isEqualTo(ImageCachingFilter.VERSIONED);
        assertThat(cacheControl("/api/v1/media/book/12/audiobook-cover", "v=3")).isEqualTo(ImageCachingFilter.VERSIONED);
    }

    @Test
    void unversionedImagesAreKeptForAnHour() throws Exception {
        assertThat(cacheControl("/api/v1/media/book/12/cover", "token=abc")).isEqualTo(ImageCachingFilter.UNVERSIONED);
        assertThat(cacheControl("/api/v1/media/book/12/audiobook-thumbnail", null)).isEqualTo(ImageCachingFilter.UNVERSIONED);
        assertThat(cacheControl("/api/v1/media/author/7/photo", null)).isEqualTo(ImageCachingFilter.UNVERSIONED);
        assertThat(cacheControl("/api/v1/media/book/12/cbx/pages/4", "token=abc")).isEqualTo(ImageCachingFilter.UNVERSIONED);
    }

    @Test
    void otherEndpointsAreLeftAlone() throws Exception {
        assertThat(cacheControl("/api/v1/books/12", "2026")).isNull();
        assertThat(cacheControl("/api/v1/media/bookdrop/3/cover", null)).isNull();
    }
}
