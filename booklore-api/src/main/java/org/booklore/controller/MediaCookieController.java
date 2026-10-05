package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import org.booklore.config.security.JwtUtils;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.repository.UserRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Gives the signed-in web app a trove_media cookie with a media token, so cover and thumbnail URLs
 * need no token and the browser can cache them for good (see CoverJwtFilter, ImageCachingFilter).
 * HttpOnly, sent only to /api/v1/media and, being SameSite=Lax, not with other sites' image requests.
 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/session/media-cookie")
@Tag(name = "Session", description = "Cookie used by the web app's image requests")
public class MediaCookieController {

    public static final String COOKIE_NAME = "trove_media";
    static final String COOKIE_PATH = "/api/v1/media";

    private final AuthenticationService authenticationService;
    private final UserRepository userRepository;
    private final JwtUtils jwtUtils;

    @Operation(summary = "Set the image cookie", description = "Sets the trove_media cookie for the signed-in user.")
    @PostMapping
    public ResponseEntity<Void> set(HttpServletRequest request) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        if (user == null || user.getId() == null) {
            throw ApiError.GENERIC_UNAUTHORIZED.createException("Not signed in");
        }
        BookLoreUserEntity entity = userRepository.findById(user.getId())
                .orElseThrow(() -> ApiError.GENERIC_UNAUTHORIZED.createException("User not found"));
        ResponseCookie cookie = cookie(jwtUtils.generateMediaToken(entity), Duration.ofMillis(JwtUtils.mediaTokenExpirationMs), request);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.toString()).build();
    }

    @Operation(summary = "Clear the image cookie")
    @DeleteMapping
    public ResponseEntity<Void> clear(HttpServletRequest request) {
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, expired(request).toString()).build();
    }

    /** A cookie that removes trove_media, for logout. */
    public static ResponseCookie expired(HttpServletRequest request) {
        return cookie("", Duration.ZERO, request);
    }

    static ResponseCookie cookie(String value, Duration maxAge, HttpServletRequest request) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }
}
