package com.ekusys.exam.common.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(JwtResourceServerProperties.class)
public class ResourceServerSecurityConfig {
    @Bean
    JwtDecoder jwtDecoder(JwtResourceServerProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(
            PemKeyReader.readPublicKey(properties.getPublicKey(), properties.getPublicKeyLocation())
        ).build();
        OAuth2TokenValidator<Jwt> tokenTypeValidator = jwt -> {
            String type = jwt.getClaimAsString("typ");
            if ("access".equals(type) || "service".equals(type)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Unsupported token type", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(properties.getIssuer()), tokenTypeValidator
        ));
        return decoder;
    }

    @Bean
    SecurityFilterChain resourceServerFilterChain(HttpSecurity http) throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter());
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/internal/v1/**").hasAuthority("SCOPE_internal")
                .anyRequest().authenticated())
            .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
            .build();
    }

    private Converter<Jwt, Collection<GrantedAuthority>> authoritiesConverter() {
        return jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>();
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles != null) {
                roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
            }
            String scope = jwt.getClaimAsString("scope");
            if (scope != null) {
                for (String value : scope.split(" ")) {
                    if (!value.isBlank()) authorities.add(new SimpleGrantedAuthority("SCOPE_" + value));
                }
            }
            return authorities;
        };
    }
}
