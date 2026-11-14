package com.cnsportiot.cloud.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/** 承载 {@link AuthUser} 的 Spring Security Authentication;权限为 ROLE_<角色> */
public class AuthUserAuthentication extends AbstractAuthenticationToken {

    private final transient AuthUser principal;

    public AuthUserAuthentication(AuthUser principal) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public AuthUser getPrincipal() {
        return principal;
    }
}
