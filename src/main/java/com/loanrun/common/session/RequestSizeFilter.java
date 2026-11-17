package com.loanrun.common.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.common.api.ApiError;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-90)
public class RequestSizeFilter extends OncePerRequestFilter {
    private static final long MAX_BYTES = 1024 * 1024;
    private final ObjectMapper mapper;
    public RequestSizeFilter(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/")) { chain.doFilter(request, response); return; }
        if (request.getContentLengthLong() > MAX_BYTES) {
            response.setStatus(400);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            mapper.writeValue(response.getWriter(), ApiError.of("VALIDATION_ERROR", "요청 본문이 너무 큽니다.", Map.of()));
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            private ServletInputStream limited;
            @Override public ServletInputStream getInputStream() throws IOException {
                if (limited == null) limited = new LimitedInputStream(super.getInputStream());
                return limited;
            }
        }, response);
    }

    private static class LimitedInputStream extends ServletInputStream {
        private final ServletInputStream source;
        private long readBytes;
        LimitedInputStream(ServletInputStream source) { this.source = source; }
        @Override public int read() throws IOException {
            int value = source.read();
            if (value != -1 && ++readBytes > MAX_BYTES) throw new IOException("Request size limit");
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = source.read(buffer, offset, (int)Math.min(length, MAX_BYTES - readBytes + 1));
            if (count > 0 && (readBytes += count) > MAX_BYTES) throw new IOException("Request size limit");
            return count;
        }
        @Override public boolean isFinished() { return source.isFinished(); }
        @Override public boolean isReady() { return source.isReady(); }
        @Override public void setReadListener(ReadListener listener) { source.setReadListener(listener); }
        @Override public void close() throws IOException { source.close(); }
    }
}
