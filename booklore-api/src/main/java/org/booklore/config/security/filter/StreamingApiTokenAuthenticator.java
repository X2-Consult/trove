package org.booklore.config.security.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.booklore.config.security.userdetails.UserAuthenticationDetails;
import org.booklore.mapper.custom.BookLoreUserTransformer;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.service.ApiTokenService;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

/**
 * Lets a self-service API token ({@code blt_…}, see {@link ApiTokenService}) through the streaming
 * endpoints, which sit in security chains of their own that otherwise only accept login JWTs. It's
 * held to what {@link ApiTokenAuthFilter} allows elsewhere: reads only, with the linked account's
 * library and book access. It must come in the Authorization header: an API token lasts until it's
 * revoked, so unlike a short-lived login token it's refused in the URL, where proxies log it.
 */
@Component
@RequiredArgsConstructor
public class StreamingApiTokenAuthenticator {

    public enum Outcome {
        /** No API token here; the caller carries on with its login-token check. */
        NOT_AN_API_TOKEN,
        /** Signed in as the token's owner. */
        AUTHENTICATED,
        /** Refused; the error response has been sent. */
        REJECTED
    }

    private final ApiTokenService apiTokenService;
    private final BookLoreUserTransformer bookLoreUserTransformer;

    public Outcome authenticate(String headerToken, String queryToken, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (headerToken == null && queryToken != null && queryToken.startsWith(ApiTokenService.TOKEN_PREFIX)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Send API tokens in the Authorization header, not the URL");
            return Outcome.REJECTED;
        }
        if (headerToken == null || !headerToken.startsWith(ApiTokenService.TOKEN_PREFIX)) {
            return Outcome.NOT_AN_API_TOKEN;
        }
        if (!HttpMethod.GET.matches(request.getMethod()) && !HttpMethod.HEAD.matches(request.getMethod())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "API tokens are read-only here");
            return Outcome.REJECTED;
        }
        Optional<BookLoreUserEntity> user = apiTokenService.authenticate(headerToken);
        if (user.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or revoked API token");
            return Outcome.REJECTED;
        }
        BookLoreUser dto = bookLoreUserTransformer.toDTO(user.get());
        var authentication = new UsernamePasswordAuthenticationToken(dto, null, null);
        authentication.setDetails(new UserAuthenticationDetails(request, dto.getId()));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return Outcome.AUTHENTICATED;
    }
}
