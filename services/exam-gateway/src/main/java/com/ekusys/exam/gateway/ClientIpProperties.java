package com.ekusys.exam.gateway;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.gateway.client-ip")
public class ClientIpProperties {
    private List<String> trustedProxyCidrs = new ArrayList<>();

    public List<String> getTrustedProxyCidrs() {
        return trustedProxyCidrs;
    }

    public void setTrustedProxyCidrs(List<String> trustedProxyCidrs) {
        this.trustedProxyCidrs = trustedProxyCidrs == null ? new ArrayList<>() : trustedProxyCidrs;
    }
}
