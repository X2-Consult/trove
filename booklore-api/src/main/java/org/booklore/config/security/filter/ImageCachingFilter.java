package org.booklore.config.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Lets browsers keep cover, thumbnail, author-photo and comic-page images.
 * <p>
 * The web app versions cover URLs with the cover's last-change time ({@code ...?2026-10-04T10:00:00Z}),
 * so a versioned URL always means the same image and is kept for a year without asking again.
 * Anything else is kept for an hour, then revalidated cheaply through the Last-Modified the media
 * endpoints send. {@code private}, because the images are per-user (and the URL can carry a token).
 */
@Component
public class ImageCachingFilter extends OncePerRequestFilter {

    static final String VERSIONED = "private, max-age=31536000, immutable";
    static final String UNVERSIONED = "private, max-age=3600";

    private static final Pattern IMAGE_PATH = Pattern.compile(
            "^/api/v1/media/(book/\\d+/(cover|thumbnail|backup-cover|audiobook-cover|audiobook-thumbnail|cbx/pages/\\d+)"
                    + "|author/\\d+/(photo|thumbnail))$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if (IMAGE_PATH.matcher(request.getRequestURI()).matches()) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, isVersioned(request.getQueryString()) ? VERSIONED : UNVERSIONED);
        }
        filterChain.doFilter(request, response);
    }

    /** True if the query carries a version: a bare value (the web app's cover timestamp) or v=. */
    static boolean isVersioned(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        return Arrays.stream(query.split("&"))
                .anyMatch(part -> !part.isBlank() && (!part.contains("=") || part.startsWith("v=")));
    }
}
