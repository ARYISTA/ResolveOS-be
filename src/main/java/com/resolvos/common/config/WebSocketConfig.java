package com.resolvos.common.config;

import com.resolvos.realtime.CaseWebSocketHandler;
import com.resolvos.realtime.TenantWebSocketHandler;
import com.resolvos.realtime.WsHandshakeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final TenantWebSocketHandler tenantWebSocketHandler;
    private final CaseWebSocketHandler caseWebSocketHandler;
    private final WsHandshakeInterceptor wsHandshakeInterceptor;
    private final CorsProperties corsProperties;

    public WebSocketConfig(TenantWebSocketHandler tenantWebSocketHandler,
                           CaseWebSocketHandler caseWebSocketHandler,
                           WsHandshakeInterceptor wsHandshakeInterceptor,
                           CorsProperties corsProperties) {
        this.tenantWebSocketHandler = tenantWebSocketHandler;
        this.caseWebSocketHandler = caseWebSocketHandler;
        this.wsHandshakeInterceptor = wsHandshakeInterceptor;
        this.corsProperties = corsProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(tenantWebSocketHandler, "/ws/tenant")
                .addInterceptors(wsHandshakeInterceptor)
                .setAllowedOriginPatterns(corsProperties.frontendOrigin(), "http://localhost:*", "*");

        registry.addHandler(caseWebSocketHandler, "/ws/cases/*")
                .addInterceptors(wsHandshakeInterceptor)
                .setAllowedOriginPatterns(corsProperties.frontendOrigin(), "http://localhost:*", "*");
    }
}
