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

/**
 * 各业务服务共用的 OAuth2 资源服务器安全配置。
 *
 * <p>认证服务负责签发 JWT；本配置使用其 RSA 公钥验签，并在每次请求时从 Bearer Token
 * 建立认证上下文，不依赖服务端 HTTP Session。</p>
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(JwtResourceServerProperties.class)
public class ResourceServerSecurityConfig {
    /**
     * 创建 JWT 解码器，并限定可被当前资源服务器接受的令牌来源和用途。
     *
     * <p>除标准的签名、过期时间和 issuer 校验外，额外校验 {@code typ}：只有用户访问令牌
     * ({@code access}) 和服务间调用令牌 ({@code service}) 可以访问受保护资源，避免其他用途的
     * JWT 被误用。</p>
     */
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

    /**
     * 定义资源服务器的无状态安全链。
     *
     * <p>健康检查供负载均衡器和运维探针访问；内部接口必须显式携带 {@code internal} scope；
     * 其余接口均要求通过 Bearer Token 认证。</p>
     */
    @Bean
    SecurityFilterChain resourceServerFilterChain(HttpSecurity http) throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter());
        return http.csrf(csrf -> csrf.disable())
            // API 使用 Bearer Token 鉴权，不创建或读取服务端会话。
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/internal/v1/**").hasAuthority("SCOPE_internal")
                .anyRequest().authenticated())
            .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
            .build();
    }

    /**
     * 将 JWT 中的业务声明转换为 Spring Security 可识别的权限。
     *
     * <p>{@code roles} 映射为 {@code ROLE_xxx}，用于 {@code hasRole} 等角色判断；空格分隔的
     * {@code scope} 映射为 {@code SCOPE_xxx}，用于接口或方法级别的细粒度授权。</p>
     */
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
