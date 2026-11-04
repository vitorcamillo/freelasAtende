package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

final class TelegramServiceImplTestSupport {

    private TelegramServiceImplTestSupport() {
    }

    static ResponseSpec response(int status, String body) {
        return new ResponseSpec(status, body);
    }

    record ResponseSpec(int status, String body) {
    }

    static final class CapturingHttpClient extends HttpClient {
        private final Queue<ResponseSpec> responses = new ArrayDeque<>();
        private final List<HttpRequest> requests = new ArrayList<>();
        private final List<String> requestBodies = new ArrayList<>();

        CapturingHttpClient(ResponseSpec... responses) {
            Collections.addAll(this.responses, responses);
        }

        int requestCount() {
            return requests.size();
        }

        List<HttpRequest> requests() {
            return requests;
        }

        JsonNode bodyJson(int index) throws Exception {
            return new ObjectMapper().readTree(requestBodies.get(index));
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_2;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
            throws IOException, InterruptedException {
            requests.add(request);
            requestBodies.add(readBody(request));
            ResponseSpec response = responses.isEmpty()
                ? new ResponseSpec(200, "{\"ok\":true}")
                : responses.remove();
            return new FakeHttpResponse<>(request, response.status(), (T) response.body());
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("sendAsync não usado neste teste"));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("sendAsync não usado neste teste"));
        }

        private String readBody(HttpRequest request) throws IOException, InterruptedException {
            if (request.bodyPublisher().isEmpty()) {
                return "";
            }
            BodyCaptureSubscriber subscriber = new BodyCaptureSubscriber();
            request.bodyPublisher().orElseThrow().subscribe(subscriber);
            return subscriber.body();
        }
    }

    private static final class BodyCaptureSubscriber implements Flow.Subscriber<ByteBuffer> {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CountDownLatch done = new CountDownLatch(1);
        private Throwable error;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ByteBuffer item) {
            byte[] chunk = new byte[item.remaining()];
            item.get(chunk);
            bytes.write(chunk, 0, chunk.length);
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
            done.countDown();
        }

        @Override
        public void onComplete() {
            done.countDown();
        }

        String body() throws IOException, InterruptedException {
            if (!done.await(5, TimeUnit.SECONDS)) {
                throw new IOException("Timeout lendo corpo HTTP");
            }
            if (error != null) {
                throw new IOException(error);
            }
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class FakeHttpResponse<T> implements HttpResponse<T> {
        private final HttpRequest request;
        private final int status;
        private final T body;

        private FakeHttpResponse(HttpRequest request, int status, T body) {
            this.request = request;
            this.status = status;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Collections.emptyMap(), (name, value) -> true);
        }

        @Override
        public T body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public Version version() {
            return Version.HTTP_2;
        }
    }
}
