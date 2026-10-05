package org.booklore.config.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.service.security.JwtSecretService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtUtilsTest {

    private static final String SECRET = "test-secret-that-is-long-enough-for-hs256-signing!!";
    private final JwtUtils jwtUtils;
    private final BookLoreUserEntity user = BookLoreUserEntity.builder().id(7L).username("reader").build();

    JwtUtilsTest() {
        JwtSecretService secretService = mock(JwtSecretService.class);
        when(secretService.getSecret()).thenReturn(SECRET);
        jwtUtils = new JwtUtils(secretService);
    }

    private String legacyToken(long lifetimeMs) {
        // Tokens issued before the token_use claim existed: same shape, no type.
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("reader")
                .claim("userId", 7L)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(lifetimeMs)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();
    }

    @Test
    void accessTokenAuthenticatesRequests() {
        assertThat(jwtUtils.validateAccessToken(jwtUtils.generateAccessToken(user))).isTrue();
    }

    @Test
    void refreshTokenIsRejectedAsABearerToken() {
        String refresh = jwtUtils.generateRefreshToken(user);

        assertThat(jwtUtils.validateAccessToken(refresh)).isFalse();
    }

    @Test
    void refreshTokenCannotBeRedeemedStraightAway() {
        String refresh = jwtUtils.generateRefreshToken(user);

        // Not-before is two minutes out, so the refresh endpoint's validation fails until then.
        assertThat(jwtUtils.validateToken(refresh)).isFalse();
    }

    @Test
    void legacyTokensAreToldApartByLifetime() {
        assertThat(jwtUtils.validateAccessToken(legacyToken(JwtUtils.accessTokenExpirationMs))).isTrue();
        assertThat(jwtUtils.validateAccessToken(legacyToken(JwtUtils.refreshTokenExpirationMs))).isFalse();
    }

    @Test
    void everyTokenGetsItsOwnId() {
        Claims first = jwtUtils.extractClaims(jwtUtils.generateAccessToken(user));
        Claims second = jwtUtils.extractClaims(jwtUtils.generateAccessToken(user));

        assertThat(first.getId()).isNotBlank().isNotEqualTo(second.getId());
        assertThat(first.get(JwtUtils.TOKEN_USE_CLAIM)).isEqualTo(JwtUtils.TOKEN_USE_ACCESS);
    }

    @Test
    void tokensCarryTheIssuer_andAForeignIssuerIsRejected() {
        assertThat(jwtUtils.extractClaims(jwtUtils.generateAccessToken(user)).getIssuer()).isEqualTo(JwtUtils.ISSUER);

        Instant now = Instant.now();
        String foreign = Jwts.builder()
                .issuer("some-other-app")
                .subject("reader")
                .claim(JwtUtils.TOKEN_USE_CLAIM, JwtUtils.TOKEN_USE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(60_000)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();
        assertThat(jwtUtils.validateAccessToken(foreign)).isFalse();
    }

    @Test
    void tokensFromBeforeTheRenameAreStillAccepted() {
        Instant now = Instant.now();
        String legacy = Jwts.builder()
                .issuer("booklore")
                .subject("reader")
                .claim(JwtUtils.TOKEN_USE_CLAIM, JwtUtils.TOKEN_USE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(60_000)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();

        assertThat(JwtUtils.ISSUER).isEqualTo("trove");
        assertThat(jwtUtils.validateAccessToken(legacy)).isTrue();
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = jwtUtils.generateAccessToken(user);
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

        assertThat(jwtUtils.validateAccessToken(tampered)).isFalse();
    }

    @Test
    void mediaTokenOpensImagesOnly() {
        String media = jwtUtils.generateMediaToken(user);

        assertThat(jwtUtils.validateMediaToken(media)).isTrue();
        assertThat(jwtUtils.validateAccessToken(media)).isFalse();
        assertThat(jwtUtils.validateMediaToken(jwtUtils.generateAccessToken(user))).isFalse();
        assertThat(jwtUtils.validateMediaToken(jwtUtils.generateRefreshToken(user))).isFalse();
        assertThat(jwtUtils.extractUserId(media)).isEqualTo(7L);
    }
}
