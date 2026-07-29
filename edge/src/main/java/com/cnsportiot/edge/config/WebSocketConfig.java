package com.cnsportiot.edge.config;

import com.cnsportiot.edge.realtime.CvChannelHandler;
import com.cnsportiot.edge.realtime.EdgeWebSocketHandler;
import com.cnsportiot.edge.realtime.LocalhostOnlyInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/** WS 端点注册 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final EdgeWebSocketHandler frontendHandler;
    private final CvChannelHandler cvHandler;

    public WebSocketConfig(EdgeWebSocketHandler frontendHandler, CvChannelHandler cvHandler) {
        this.frontendHandler = frontendHandler;
        this.cvHandler = cvHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(frontendHandler, "/ws/display", "/ws/console", "/ws/registration")
                // 局域网可信环境,页面由本服务托管;访问范围靠部署时的监听地址收敛
                .setAllowedOriginPatterns("*");

        registry.addHandler(cvHandler, "/internal/cv/stream")
                .addInterceptors(new LocalhostOnlyInterceptor())
                .setAllowedOriginPatterns("*");
    }

}

