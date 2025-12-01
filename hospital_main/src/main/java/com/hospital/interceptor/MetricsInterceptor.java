package com.hospital.interceptor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.concurrent.TimeUnit;

@Component
public class MetricsInterceptor implements HandlerInterceptor {

    @Autowired
    private MeterRegistry meterRegistry;

    private static final String START_TIME_ATTR = "metrics.startTime";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_TIME_ATTR, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, 
                               Object handler, Exception ex) {
        Long startTime = (Long) request.getAttribute(START_TIME_ATTR);
        if (startTime == null) return;

        long duration = System.currentTimeMillis() - startTime;
        
        // URI 매핑 패턴 사용 (예: /api/hospitals/{id})
        String uri = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (uri == null) uri = request.getRequestURI();
        
        if (isExcludedPath(uri)) return;

        String method = request.getMethod();
        String status = String.valueOf(response.getStatus());
        String exception = (ex != null) ? ex.getClass().getSimpleName() : "None";

        // 대시보드 12900용 메트릭 이름 'http.server.requests' 사용
        Timer.builder("http.server.requests")
                .tag("uri", uri)
                .tag("method", method)
                .tag("status", status)
                .tag("exception", exception)
                .tag("outcome", getOutcome(response.getStatus()))
                .register(meterRegistry)
                .record(duration, TimeUnit.MILLISECONDS);
    }

    private String getOutcome(int status) {
        if (status >= 200 && status < 300) return "SUCCESS";
        if (status >= 300 && status < 400) return "REDIRECTION";
        if (status >= 400 && status < 500) return "CLIENT_ERROR";
        if (status >= 500) return "SERVER_ERROR";
        return "UNKNOWN";
    }

    private boolean isExcludedPath(String path) {
        return path == null || path.startsWith("/actuator") || path.startsWith("/static") || path.endsWith(".ico");
    }
}
