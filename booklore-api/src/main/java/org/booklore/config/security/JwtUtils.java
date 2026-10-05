package org.booklore.config.security;

import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.service.security.JwtSecretService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Service
@Component
@RequiredArgsConstructor
public class JwtUtils {

    private final JwtSecretService jwtSecretService;
    @Getter
    public static final long accessTokenExpirationMs = 1000L * 60 * 60 * 10;  // 10 hours
    @Getter
    public static final long refreshTokenExpirationMs = 1000L * 60 * 60 * 24 * 30; // 30 days

    // A refresh token can't be redeemed straight after it's issued (Grimmory c7d147c8). The frontend
    // only refreshes after a 401, and access tokens last 10 hours, so a legitimate client never hits it.
    static final long refreshTokenNotBeforeMs = 1000L * 60 * 2;

    // Access and refresh tokens are otherwise signed identically, so the claim is what stops a refresh
    // token (30 days, revocable only in the database) being presented as a bearer token.
    static final String ISSUER = "trove";
    // Tokens issued before the rename to Trove; accepted so nobody is signed out by the upgrade.
    static final String LEGACY_ISSUER = "booklore";

    static final String TOKEN_USE_CLAIM = "token_use";
    static final String TOKEN_USE_ACCESS = "access";
    static final String TOKEN_USE_REFRESH = "refresh";
    // Read-only, for the web app's image requests (covers, thumbnails, author photos), sent as the
    // trove_media cookie so image URLs carry no token and stay cacheable; see MediaCookieController.
    static final String TOKEN_USE_MEDIA = "media";
    public static final long mediaTokenExpirationMs = refreshTokenExpirationMs;
    // Tokens issued before the claim existed are told apart by lifetime instead.
    private static final long LEGACY_ACCESS_LIFETIME_SLACK_MS = 1000L * 60;

    private SecretKey getSigningKey() {
        String secretKey = jwtSecretService.getSecret();
        return Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(BookLoreUserEntity user, boolean isRefreshToken) {
        long expirationTime = isRefreshToken ? refreshTokenExpirationMs : accessTokenExpirationMs;
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .issuer(ISSUER)
                .id(UUID.randomUUID().toString())
                .subject(user.getUsername())
                .claim("userId", user.getId())
                .claim("isDefaultPassword", user.isDefaultPassword())
                .claim(TOKEN_USE_CLAIM, isRefreshToken ? TOKEN_USE_REFRESH : TOKEN_USE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationTime)));
        if (isRefreshToken) {
            builder.notBefore(Date.from(now.plusMillis(refreshTokenNotBeforeMs)));
        }
        return builder.signWith(getSigningKey(), Jwts.SIG.HS256).compact();
    }

    public String generateAccessToken(BookLoreUserEntity user) {
        return generateToken(user, false);
    }

    public String generateRefreshToken(BookLoreUserEntity user) {
        return generateToken(user, true);
    }

    public String generateMediaToken(BookLoreUserEntity user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(ISSUER)
                .id(UUID.randomUUID().toString())
                .subject(user.getUsername())
                .claim("userId", user.getId())
                .claim(TOKEN_USE_CLAIM, TOKEN_USE_MEDIA)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(mediaTokenExpirationMs)))
                .signWith(getSigningKey(), Jwts.SIG.HS256)
                .compact();
    }

    /** Only a media token: it opens image endpoints and nothing else. */
    public boolean validateMediaToken(String token) {
        try {
            return TOKEN_USE_MEDIA.equals(extractClaims(token).get(TOKEN_USE_CLAIM));
        } catch (JwtException e) {
            log.debug("Invalid media token: {}", e.getMessage());
            return false;
        }
    }

    public boolean validateToken(String token) {
        try {
            extractClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.debug("Token expired: {}", e.getMessage());
        } catch (JwtException e) {
            log.debug("Invalid token: {}", e.getMessage());
        }
        return false;
    }

    /**
     * For every path that authenticates a request with a token (API calls, WebSockets, streaming
     * query parameters): the token must be valid and must be an access token.
     */
    public boolean validateAccessToken(String token) {
        try {
            if (isAccessToken(extractClaims(token))) {
                return true;
            }
            log.debug("Rejected a refresh token presented as an access token");
        } catch (ExpiredJwtException e) {
            log.debug("Token expired: {}", e.getMessage());
        } catch (JwtException e) {
            log.debug("Invalid token: {}", e.getMessage());
        }
        return false;
    }

    static boolean isAccessToken(Claims claims) {
        Object use = claims.get(TOKEN_USE_CLAIM);
        if (use != null) {
            return TOKEN_USE_ACCESS.equals(use);
        }
        Date issuedAt = claims.getIssuedAt();
        Date expiration = claims.getExpiration();
        if (issuedAt == null || expiration == null) {
            return false;
        }
        return expiration.getTime() - issuedAt.getTime() <= accessTokenExpirationMs + LEGACY_ACCESS_LIFETIME_SLACK_MS;
    }

    public Claims extractClaims(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        // Reject a token minted for something else with the same key. Tokens from before the issuer
        // claim have none and still pass; they're all expired 30 days after this ships.
        String issuer = claims.getIssuer();
        if (issuer != null && !ISSUER.equals(issuer) && !LEGACY_ISSUER.equals(issuer)) {
            throw new JwtException("Unexpected token issuer: " + issuer);
        }
        return claims;
    }

    public String extractUsername(String token) {
        return extractClaims(token).getSubject();
    }

    public Long extractUserId(String token) {
        Object userIdClaim = extractClaims(token).get("userId");
        if (userIdClaim instanceof Number) {
            return ((Number) userIdClaim).longValue();
        }
        throw new IllegalArgumentException("Invalid userId claim type");
    }
}