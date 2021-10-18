package com.meread.selenium.ws;

import com.meread.selenium.util.CommonAttributes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.Map;

/**
 * 类描述：拦截器
 *
 * @author yangxg
 */
@Component
@Slf4j
public class MyHandshakeInterceptor extends HttpSessionHandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                   Map<String, Object> map) throws Exception {
        log.info("Before handshake " + request.getRemoteAddress());
        if (request instanceof ServletServerHttpRequest) {
            ServletServerHttpRequest serverHttpRequest = (ServletServerHttpRequest) request;

            HttpServletRequest servletRequest = serverHttpRequest.getServletRequest();
            HttpSession session = servletRequest.getSession(true);

            String path = request.getURI().getPath();
            map.put(CommonAttributes.JD_LOGIN_TYPE, path.substring(path.lastIndexOf('/') + 1));

            if (session != null) {
                map.put(CommonAttributes.SESSION_ID, session.getId());
            }
        }
        return super.beforeHandshake(request, response, handler, map);
    }

}  