package org.booklore.service;

import org.booklore.exception.ApiError;
import org.booklore.model.dto.response.ApiTokenCreatedResponse;
import org.booklore.model.dto.response.ApiTokenSummary;
import org.booklore.model.entity.ApiTokenEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.repository.ApiTokenRepository;
import org.booklore.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Self-service API tokens for third-party apps (companion readers, sync tools, etc).
 * Unlike a normal login JWT - which inherits whatever permissions the account has - a
 * token minted here is always read-only plus reading-progress updates, enforced in
 * {@link org.booklore.config.security.filter.ApiTokenAuthFilter} regardless of the
 * linked account's actual permission flags. Same "separate, purpose-scoped credential"
 * pattern already used for OPDS and KOReader/Kobo sync, applied to the main REST API.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiTokenService {

    /** Distinguishes an API token from a normal login JWT at a glance, no parsing needed. */
    public static final String TOKEN_PREFIX = "blt_";

    private static final int TOKEN_RANDOM_BYTES = 32;

    private final ApiTokenRepository apiTokenRepository;
    private final UserRepository userRepository;

    @Transactional
    public ApiTokenCreatedResponse createToken(Long userId, String name) {
        BookLoreUserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> ApiError.USER_NOT_FOUND.createException(userId));

        String rawToken = generateToken();
        ApiTokenEntity entity = ApiTokenEntity.builder()
                .user(user)
                .name(name)
                .tokenHash(hash(rawToken))
                .build();
        entity = apiTokenRepository.save(entity);

        return ApiTokenCreatedResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .token(rawToken)
                .createdAt(entity.getCreatedAt())
                .build();
    }

    @Transactional(readOnly = true)
    public List<ApiTokenSummary> listTokens(Long userId) {
        return apiTokenRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(t -> !t.isRevoked())
                .map(t -> ApiTokenSummary.builder()
                        .id(t.getId())
                        .name(t.getName())
                        .createdAt(t.getCreatedAt())
                        .lastUsedAt(t.getLastUsedAt())
                        .build())
                .toList();
    }

    @Transactional
    public void revokeToken(Long userId, Long tokenId) {
        ApiTokenEntity entity = apiTokenRepository.findById(tokenId)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("API token not found"));
        if (!entity.getUser().getId().equals(userId)) {
            throw ApiError.FORBIDDEN.createException("You do not own this API token");
        }
        apiTokenRepository.delete(entity);
    }

    /**
     * Looks up the user behind a presented API token. Returns empty for a missing,
     * malformed, or revoked token. Touches last-used-at on every successful lookup.
     */
    @Transactional
    public Optional<BookLoreUserEntity> authenticate(String rawToken) {
        if (rawToken == null || !rawToken.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        Optional<ApiTokenEntity> found = apiTokenRepository.findByTokenHash(hash(rawToken))
                .filter(t -> !t.isRevoked());
        found.ifPresent(t -> {
            t.setLastUsedAt(Instant.now());
            apiTokenRepository.save(t);
        });
        // The token's user is a lazy proxy that can't be read once this transaction ends, and the
        // filters read the user's permissions after it has; load the real user here instead.
        return found.map(t -> t.getUser().getId())
                .flatMap(userRepository::findById)
                .map(user -> (BookLoreUserEntity) org.hibernate.Hibernate.unproxy(user));
    }

    private String generateToken() {
        byte[] randomBytes = new byte[TOKEN_RANDOM_BYTES];
        new SecureRandom().nextBytes(randomBytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
