package com.ekusys.exam.auth.service;

import com.ekusys.exam.auth.config.AuthCookieProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class AuthCookieService {

    private final AuthCookieProperties properties;

    public AuthCookieService(AuthCookieProperties properties) {
        this.properties = properties;
    }

    public void writeRefreshToken(HttpServletResponse response, String refreshToken) {
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie(refreshToken, Duration.ofSeconds(properties.getMaxAgeSeconds())));
    }

    public void clearRefreshToken(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("", Duration.ZERO));
    }

    public Optional<String> extractRefreshToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
            .filter(cookie -> properties.getName().equals(cookie.getName()))
            .map(Cookie::getValue)
            .filter(value -> value != null && !value.isBlank())
            .findFirst();
    }

    private String buildCookie(String value, Duration maxAge) {
        return ResponseCookie.from(properties.getName(), value)
            .httpOnly(properties.isHttpOnly())
            .secure(properties.isSecure())
            .sameSite(properties.getSameSite())
            .path(properties.getPath())
            .maxAge(maxAge)
            .build()
            .toString();
    }
}
