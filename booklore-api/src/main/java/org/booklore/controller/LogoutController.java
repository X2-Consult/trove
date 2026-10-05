package org.booklore.controller;

import lombok.AllArgsConstructor;
import org.booklore.config.security.service.LogoutService;
import org.booklore.model.dto.request.LogoutRequest;
import org.booklore.model.dto.response.LogoutResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/auth")
public class LogoutController {

    private final LogoutService logoutService;

    @PostMapping("/logout")
    public ResponseEntity<LogoutResponse> logout(Authentication auth,
                                                  @RequestBody(required = false) LogoutRequest request,
                                                  @RequestHeader(value = "Origin", required = false) String origin,
                                                  HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        // Set first so the image cookie goes even if the logout itself fails (e.g. an expired session).
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, MediaCookieController.expired(httpRequest).toString());
        String refreshToken = request != null ? request.refreshToken() : null;
        LogoutResponse response = logoutService.logout(auth, refreshToken, origin);
        return ResponseEntity.ok(response);
    }
}
