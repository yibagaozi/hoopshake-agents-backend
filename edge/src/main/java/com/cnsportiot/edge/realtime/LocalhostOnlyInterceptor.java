package com.cnsportiot.edge.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.InetAddress;
import java.util.Map;

/** {@code /internal/**} 仅允许本机连接 */
public class LocalhostOnlyInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LocalhostOnlyInterceptor.class);

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        String remote = remoteAddress(request);
        if (isLoopback(remote)) {
            return true;
        }
        log.warn("拒绝非本机连接 /internal 通道: {}", remote);
        response.setStatusCode(org.springframework.http.HttpStatus.FORBIDDEN);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
        // 无需处理
    }

    private static String remoteAddress(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            return servletRequest.getServletRequest().getRemoteAddr();
        }
        return request.getRemoteAddress() == null
                ? null : request.getRemoteAddress().getAddress().getHostAddress();
    }

    private static boolean isLoopback(String addr) {
        if (addr == null) {
            return false;
        }
        try {
            return InetAddress.getByName(addr).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }
}

