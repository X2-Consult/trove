package org.booklore.config.security;

import org.booklore.config.security.filter.*;
import org.booklore.config.security.service.OpdsUserDetailsService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.http.HttpMethod;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
@EnableMethodSecurity
@Configuration
public class SecurityConfig {

    private static final Pattern ALLOWED = Pattern.compile("\\s*,\\s*");
    private final OpdsUserDetailsService opdsUserDetailsService;
    private final JwtAuthenticationFilter dualJwtAuthenticationFilter;
    private final ApiTokenAuthFilter apiTokenAuthFilter;
    private final Environment env;

    private static final String[] COMMON_PUBLIC_ENDPOINTS = {
            "/ws/**",                  // WebSocket connections (auth handled in WebSocketAuthInterceptor)
            "/kobo/**",                // Kobo API requests (auth handled in KoboAuthFilter)
            "/api/v1/auth/**",         // Login and token refresh endpoints (must remain public)
            "/api/v1/public-settings", // Public endpoint for checking OIDC or other app settings
            "/api/v1/setup/**",        // Setup wizard endpoints (must remain accessible before initial setup)
            "/api/v1/healthcheck/**"   // Healthcheck endpoints (must remain accessible for Docker healthchecks)
    };

    private static final String[] COMMON_UNAUTHENTICATED_ENDPOINTS = {
            "/api/v1/opds/search.opds",
            "/api/v2/opds/search.opds"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Built explicitly per-chain rather than relying on a single shared
     * AuthenticationManager bean resolved via HttpSecurity's shared
     * AuthenticationManagerBuilder - with multiple SecurityFilterChain beans
     * in play, that shared-bean pattern is fragile (bean init order isn't
     * guaranteed to configure it with opdsUserDetailsService before some
     * other chain's HttpSecurity resolves/caches an AuthenticationManager).
     */
    private DaoAuthenticationProvider opdsAuthenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(opdsUserDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain opdsBasicAuthSecurityChain(HttpSecurity http) throws Exception {
        List<String> unauthenticatedEndpoints = new ArrayList<>(Arrays.asList(COMMON_UNAUTHENTICATED_ENDPOINTS));
        http
                .securityMatcher("/api/v1/opds/**", "/api/v2/opds/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(opdsAuthenticationProvider())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(unauthenticatedEndpoints.toArray(new String[0])).permitAll()
                        .anyRequest().authenticated()
                )
                .httpBasic(basic -> basic
                        .realmName("Booklore OPDS")
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setHeader("WWW-Authenticate", "Basic realm=\"Booklore OPDS\"");
                            response.getWriter().write("HTTP Status 401 - " + authException.getMessage());
                        })
                );

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain komgaBasicAuthSecurityChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/komga/api/v1/**", "/komga/api/v2/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(opdsAuthenticationProvider())
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().authenticated()
                )
                .httpBasic(basic -> basic
                        .realmName("Booklore Komga API")
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setHeader("WWW-Authenticate", "Basic realm=\"Booklore Komga API\"");
                            response.getWriter().write("HTTP Status 401 - " + authException.getMessage());
                        })
                );

        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain koreaderSecurityChain(HttpSecurity http, KoreaderAuthFilter koreaderAuthFilter) throws Exception {
        http
                .securityMatcher("/api/koreader/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Registration is always rejected by the controller itself (with a
                        // clear 403), which a client can only see if the request is allowed
                        // to reach it - unlike login/sync, there's no existing KOReader
                        // account to authenticate this request against yet.
                        .requestMatchers(HttpMethod.POST, "/api/koreader/users/create").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(koreaderAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain koboSecurityChain(HttpSecurity http, KoboAuthFilter koboAuthFilter) throws Exception {
        http
                .securityMatcher("/api/kobo/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterBefore(koboAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(4)
    public SecurityFilterChain coverJwtApiSecurityChain(HttpSecurity http, CoverJwtFilter coverJwtFilter) throws Exception {
        http
                .securityMatcher("/api/v1/media/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .cacheControl(HeadersConfigurer.CacheControlConfig::disable)
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                )
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                )
                .addFilterBefore(coverJwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(dualJwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    @Order(5)
    public SecurityFilterChain customFontSecurityChain(HttpSecurity http, CustomFontJwtFilter customFontJwtFilter) throws Exception {
        http
                .securityMatcher("/api/v1/custom-fonts/*/file")
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .headers(headers -> headers
                        // These URLs carry the access token as a query parameter so the browser
                        // can load them directly. Without this, a book's own content (an EPUB
                        // referencing an external image) would leak the token via the Referer.
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                )
                .addFilterBefore(customFontJwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(6)
    public SecurityFilterChain epubStreamingSecurityChain(HttpSecurity http, EpubStreamingJwtFilter epubStreamingJwtFilter) throws Exception {
        http
                .securityMatcher("/api/v1/epub/*/file/**")
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .headers(headers -> headers
                        // These URLs carry the access token as a query parameter so the browser
                        // can load them directly. Without this, a book's own content (an EPUB
                        // referencing an external image) would leak the token via the Referer.
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                )
                .addFilterBefore(epubStreamingJwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(7)
    public SecurityFilterChain audiobookStreamingSecurityChain(HttpSecurity http, AudiobookStreamingJwtFilter audiobookStreamingJwtFilter) throws Exception {
        http
                .securityMatcher("/api/v1/audiobooks/*/stream/**", "/api/v1/audiobooks/*/track/*/stream/**", "/api/v1/audiobooks/*/cover")
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .headers(headers -> headers
                        // These URLs carry the access token as a query parameter so the browser
                        // can load them directly. Without this, a book's own content (an EPUB
                        // referencing an external image) would leak the token via the Referer.
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                )
                .addFilterBefore(audiobookStreamingJwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(8)
    public SecurityFilterChain jwtApiSecurityChain(HttpSecurity http) throws Exception {
        List<String> publicEndpoints = new ArrayList<>(Arrays.asList(COMMON_PUBLIC_ENDPOINTS));
        http
                .securityMatcher("/api/**", "/komga/**", "/ws/**")
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .contentTypeOptions(contentType -> {})
                )
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers(publicEndpoints.toArray(new String[0])).permitAll()
                        .anyRequest().authenticated()
                )
                // Order matters: JwtAuthenticationFilter's position must be registered
                // (relative to the standard UsernamePasswordAuthenticationFilter) before
                // it can be used as an anchor for apiTokenAuthFilter below.
                .addFilterBefore(dualJwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(apiTokenAuthFilter, JwtAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(9)
    public SecurityFilterChain staticResourcesSecurityChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin)
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .contentTypeOptions(contentType -> {})
                        // A personal library: keep its pages (login, help) out of search engines and
                        // AI search, even when the server is reachable from the internet.
                        .addHeaderWriter(new StaticHeadersWriter("X-Robots-Tag", "noindex, nofollow"))
                )
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        String allowedOriginsStr = env.getProperty("app.cors.allowed-origins", "*").trim();
        boolean wildcardOrigins = "*".equals(allowedOriginsStr) || allowedOriginsStr.isEmpty();
        if (wildcardOrigins) {
            log.warn(
                "CORS is configured to allow all origins (*) because 'app.cors.allowed-origins' is '{}'. " +
                "Credentialed cross-origin requests are disabled in this mode - set an explicit " +
                "origin list via ALLOWED_ORIGINS to allow them.",
                allowedOriginsStr.isEmpty() ? "empty" : "*"
            );
            configuration.setAllowedOriginPatterns(List.of("*"));
        } else {
            List<String> origins = Arrays.stream(ALLOWED.split(allowedOriginsStr))
                    .filter(s -> !s.isEmpty())
                    .map(String::trim)
                    .toList();
            configuration.setAllowedOriginPatterns(origins);
        }

        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("Authorization", "Cache-Control", "Content-Type", "Range", "If-None-Match"));
        configuration.setExposedHeaders(List.of("Content-Disposition", "Accept-Ranges", "Content-Range", "Content-Length", "ETag", "Date"));
        // Never combine a wildcard origin with credentials - that would hand any site on the
        // internet a credentialed cross-origin channel to this server.
        configuration.setAllowCredentials(!wildcardOrigins);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }
}
