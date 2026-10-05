package org.booklore.config.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.booklore.config.security.JwtUtils;
import org.booklore.controller.MediaCookieController;
import org.booklore.mapper.custom.BookLoreUserTransformer;
import org.booklore.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Authenticates /api/v1/media requests with an access token in the {@code token} query parameter,
 * or, for cover, thumbnail and author-photo images only, with the trove_media cookie's media token.
 * The cookie keeps image URLs free of the (rotating) access token, so the browser can cache them.
 * Comic pages are book content and still need the access token.
 */
@Component
public class CoverJwtFilter extends AbstractQueryParameterJwtFilter {

    static final Pattern COOKIE_IMAGE_PATH = Pattern.compile(
            "^/api/v1/media/(book/\\d+/(cover|thumbnail|backup-cover|audiobook-cover|audiobook-thumbnail)|author/\\d+/(photo|thumbnail))$");

    public CoverJwtFilter(
            JwtUtils jwtUtils,
            UserRepository userRepository,
            BookLoreUserTransformer bookLoreUserTransformer) {
        super(jwtUtils, userRepository, bookLoreUserTransformer);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/media/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String queryToken = request.getParameter("token");
        String cookieToken = mediaCookie(request);
        if ((queryToken == null || queryToken.isEmpty()) && cookieToken != null
                && COOKIE_IMAGE_PATH.matcher(request.getRequestURI()).matches()) {
            try {
                if (jwtUtils.validateMediaToken(cookieToken)) {
                    authenticateUser(cookieToken, request);
                    chain.doFilter(request, response);
                    return;
                }
            } catch (Exception ex) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication failed");
                return;
            }
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid media cookie");
            return;
        }
        super.doFilterInternal(request, response, chain);
    }

    private static String mediaCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(c -> MediaCookieController.COOKIE_NAME.equals(c.getName()) && c.getValue() != null && !c.getValue().isEmpty())
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
