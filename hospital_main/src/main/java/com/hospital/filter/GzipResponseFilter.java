package com.hospital.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.zip.GZIPOutputStream;

/**
 * HTTP 응답을 Gzip으로 압축하는 필터
 * JSON, HTML 등의 응답을 자동으로 압축하여 네트워크 전송량을 감소시킵니다.
 */
public class GzipResponseFilter implements Filter {


    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // 초기화
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // 클라이언트가 gzip을 지원하는지 확인
        String acceptEncoding = httpRequest.getHeader("Accept-Encoding");

        if (acceptEncoding != null && acceptEncoding.contains("gzip")) {
            // Gzip 응답 래퍼 사용
            GzipHttpServletResponseWrapper gzipResponse = new GzipHttpServletResponseWrapper(httpResponse);

            chain.doFilter(request, gzipResponse);

            gzipResponse.finish();
        } else {
            // 압축 없이 그대로
            chain.doFilter(request, response);
        }
    }

    @Override
    public void destroy() {
        // 정리
    }

    /**
     * Gzip 압축을 지원하는 HttpServletResponse 래퍼
     */
    private static class GzipHttpServletResponseWrapper extends HttpServletResponseWrapper {

        private GzipServletOutputStream gzipOutputStream;
        private PrintWriter printWriter;

        public GzipHttpServletResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (printWriter != null) {
                throw new IllegalStateException("PrintWriter already obtained");
            }
            if (gzipOutputStream == null) {
                // Content-Encoding 헤더 설정
                ((HttpServletResponse) getResponse()).setHeader("Content-Encoding", "gzip");
                gzipOutputStream = new GzipServletOutputStream(getResponse().getOutputStream());
            }
            return gzipOutputStream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (gzipOutputStream != null) {
                throw new IllegalStateException("OutputStream already obtained");
            }
            if (printWriter == null) {
                // Content-Encoding 헤더 설정
                ((HttpServletResponse) getResponse()).setHeader("Content-Encoding", "gzip");
                GZIPOutputStream gzip = new GZIPOutputStream(getResponse().getOutputStream());
                printWriter = new PrintWriter(new OutputStreamWriter(gzip, getCharacterEncoding()));
            }
            return printWriter;
        }

        public void finish() throws IOException {
            if (printWriter != null) {
                printWriter.close();
            }
            if (gzipOutputStream != null) {
                gzipOutputStream.finish();
            }
        }
    }

    /**
     * Gzip 압축을 지원하는 ServletOutputStream
     */
    private static class GzipServletOutputStream extends ServletOutputStream {

        private final GZIPOutputStream gzipOutputStream;

        public GzipServletOutputStream(ServletOutputStream outputStream) throws IOException {
            this.gzipOutputStream = new GZIPOutputStream(outputStream);
        }

        @Override
        public void write(int b) throws IOException {
            gzipOutputStream.write(b);
        }

        @Override
        public void write(byte[] b) throws IOException {
            gzipOutputStream.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            gzipOutputStream.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            gzipOutputStream.flush();
        }

        @Override
        public void close() throws IOException {
            gzipOutputStream.close();
        }

        public void finish() throws IOException {
            gzipOutputStream.finish();
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            // Not implemented
        }
    }
}
