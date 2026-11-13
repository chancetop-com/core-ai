package ai.core.server.share;

import ai.core.server.SessionShareModule;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.artifact.PublicUrlConfiguration;
import ai.core.server.channel.ChannelConfigStore;
import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.SessionShare;
import ai.core.server.domain.SessionShareFile;
import ai.core.server.domain.User;
import ai.core.server.messaging.TurnStateRegistry;
import ai.core.server.messaging.TurnStateRegistry.TurnLiveness;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.session.ChatMessageQuery;
import ai.core.server.session.ChatMessageStore;
import ai.core.server.session.ChatSessionStore;
import ai.core.server.trace.SpanStore;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.service.TraceAccessControl;
import ai.core.server.trace.service.TraceService;
import ai.core.server.trace.service.TraceVisibility;
import ai.core.server.trace.service.TraceVisibilityResolver;
import ai.core.server.user.UserStore;
import ai.core.server.web.auth.AuthInterceptor;
import ai.core.server.web.auth.PermissionInterceptor;
import ai.core.server.web.auth.RequestAuthenticator;
import ai.core.server.web.auth.SubUserDelegationResolver;
import ai.core.server.web.session.SessionIdentity;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import core.framework.internal.module.ModuleContext;
import core.framework.module.App;
import core.framework.module.SystemModule;
import core.framework.web.Request;
import core.framework.web.exception.NotFoundException;
import core.framework.web.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * @author Xander
 */
class SessionShareHttpTest {
    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return JsonUtil.OBJECT_MAPPER.readTree(response.body());
    }

    private static HttpResponse<String> send(HttpClient client, String baseUrl, String method, String path, String body, boolean owner) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(5));
        if (owner) request.header("Authorization", "Bearer test-owner");
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static JsonNode createShare(HttpClient client, String baseUrl) throws Exception {
        var created = send(client, baseUrl, "POST", "/api/sessions/session-1/shares", "{\"expires_in_days\":7}", true);
        assertEquals(200, created.statusCode(), created.body());
        var ownerView = json(created);
        assertEquals("session-1", ownerView.path("session_id").asText());
        assertTrue(ownerView.path("expires_at").isTextual());
        assertEquals(2, ownerView.path("max_message_seq").asInt());
        return ownerView;
    }

    private static String verifyAnonymousMetadata(HttpClient client, String baseUrl, String root) throws Exception {
        var metadata = send(client, baseUrl, "GET", root, null, false);
        assertEquals(200, metadata.statusCode(), metadata.body());
        assertEquals("no-store", metadata.headers().firstValue("Cache-Control").orElse(null));
        assertEquals("no-referrer", metadata.headers().firstValue("Referrer-Policy").orElse(null));
        assertEquals("Debug conversation", json(metadata).path("title").asText());
        assertFalse(json(metadata).has("owner_user_id"));
        var download = json(metadata).path("artifacts").get(0).path("download_url").asText();
        assertEquals(root + "/files/file-ref/content", download);
        return download;
    }

    private static void verifyAnonymousHistory(HttpClient client, String baseUrl, String root) throws Exception {
        var history = send(client, baseUrl, "GET", root + "/messages?offset=1&limit=1", null, false);
        assertEquals(200, history.statusCode(), history.body());
        var messages = json(history).path("messages");
        assertEquals(1, messages.size());
        assertEquals(2, messages.get(0).path("seq").asInt());
        assertEquals("trace-1", messages.get(0).path("trace_id").asText());
        assertTrue(messages.get(0).path("sandbox").isMissingNode() || messages.get(0).path("sandbox").isNull());
        assertTrue(json(history).path("next_offset").isMissingNode() || json(history).path("next_offset").isNull());
    }

    private static void verifyAnonymousTrace(HttpClient client, String baseUrl, String root) throws Exception {
        var trace = send(client, baseUrl, "GET", root + "/traces/trace-1", null, false);
        assertEquals(200, trace.statusCode(), trace.body());
        assertEquals("trace-1", json(trace).path("traceId").asText());
        assertTrue(trace.body().contains("remembered fact"));
        assertFalse(trace.body().contains("credential-secret"));
        var spans = send(client, baseUrl, "GET", root + "/traces/trace-1/spans", null, false);
        assertEquals(200, spans.statusCode(), spans.body());
        assertEquals("span-1", json(spans).path("spans").get(0).path("spanId").asText());
        var span = send(client, baseUrl, "GET", root + "/traces/trace-1/spans/span-1", null, false);
        assertEquals(200, span.statusCode(), span.body());
        assertFalse(span.body().contains("credential-secret"));
    }

    private static void verifyAnonymousFile(HttpClient client, String baseUrl, String download) throws Exception {
        var file = send(client, baseUrl, "GET", download, null, false);
        assertEquals(200, file.statusCode(), file.body());
        assertEquals("attached-content", file.body());
        assertTrue(file.headers().firstValue("Content-Disposition").orElse("").startsWith("attachment"));
    }

    private static void revokeAndVerify(HttpClient client, SharingApp app, String shareId, String root, String download) throws Exception {
        assertEquals(404, send(client, app.baseUrl, "GET", root + "/traces/unshared", null, false).statusCode());
        assertEquals(401, send(client, app.baseUrl, "GET", "/api/sessions/session-1/shares", null, false).statusCode());
        var current = send(client, app.baseUrl, "GET", "/api/sessions/session-1/shares", null, true);
        assertEquals(200, current.statusCode(), current.body());
        assertEquals(shareId, json(current).path("share").path("id").asText());
        var revoked = send(client, app.baseUrl, "DELETE", "/api/sessions/session-1/shares/" + shareId, null, true);
        assertEquals(204, revoked.statusCode(), revoked.body());
        assertNotNull(app.share.get().revokedAt);
        for (var path : List.of(root, root + "/messages", root + "/traces/trace-1",
            root + "/traces/trace-1/spans", root + "/traces/trace-1/spans/span-1", download)) {
            assertEquals(404, send(client, app.baseUrl, "GET", path, null, false).statusCode(), path);
        }
    }

    @Test
    void registersProductionRoutesAndReadsSharedConversationWithoutLoginThenRevokes() throws Exception {
        var app = new SharingApp(freePort());
        app.configure();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            app.context().startupHook.initialize();
            app.context().startupHook.start();
            var ownerView = createShare(client, app.baseUrl);
            var root = "/api/public/session-shares/" + app.share.get().token;
            var download = verifyAnonymousMetadata(client, app.baseUrl, root);
            verifyAnonymousHistory(client, app.baseUrl, root);
            verifyAnonymousTrace(client, app.baseUrl, root);
            verifyAnonymousFile(client, app.baseUrl, download);
            revokeAndVerify(client, app, ownerView.path("id").asText(), root, download);
        } finally {
            app.context().shutdownHook.run();
        }
    }

    private static final class SharingApp extends App {
        final AtomicReference<SessionShare> share = new AtomicReference<>();
        final String baseUrl;
        private final int port;

        SharingApp(int port) {
            this.port = port;
            baseUrl = "http://127.0.0.1:" + port;
        }

        ModuleContext context() {
            return context;
        }

        @Override
        public <T> T bind(Type type, String name, T instance) {
            return mockingDetails(instance).isMock() ? context.bind(type, name, instance) : super.bind(type, name, instance);
        }

        @Override
        protected void initialize() {
            load(new SystemModule("apidef-e2e.properties"));
            http().listenHTTP("127.0.0.1:" + port);
            bind(new PublicUrlConfiguration(baseUrl));
            bindIdentity();
            bindSession();
            bindTraces();
            http().intercept(bind(AuthInterceptor.class));
            http().intercept(bind(PermissionInterceptor.class));
            load(new SharingModule(store(), files()));
        }

        private void bindIdentity() {
            var user = new User();
            user.id = "owner-1";
            user.status = "active";
            var users = mock(UserStore.class);
            when(users.get("owner-1")).thenReturn(Optional.of(user));
            bind(UserStore.class, users);
            var permissions = mock(PermissionService.class);
            when(permissions.has("owner-1", PermissionCodes.TRACE_VIEW)).thenReturn(Boolean.TRUE);
            bind(PermissionService.class, permissions);
            var authenticator = mock(RequestAuthenticator.class);
            when(authenticator.authenticate(any())).thenAnswer(invocation -> {
                Request request = invocation.getArgument(0);
                if (!"Bearer test-owner".equals(request.header("Authorization").orElse(""))) throw new UnauthorizedException("login required");
                return "owner-1";
            });
            bind(RequestAuthenticator.class, authenticator);
            bind(SessionIdentity.class, mock(SessionIdentity.class));
            bind(ChannelConfigStore.class, mock(ChannelConfigStore.class));
            bind(SubUserDelegationResolver.class, mock(SubUserDelegationResolver.class));
            bind(SandboxService.class, mock(SandboxService.class));
        }

        private void bindSession() {
            var session = new ChatSession();
            session.id = "session-1";
            session.userId = "owner-1";
            session.title = "Debug conversation";
            var sessions = mock(ChatSessionStore.class);
            when(sessions.get(session.id)).thenReturn(Optional.of(session));
            bind(ChatSessionStore.class, sessions);
            var prompt = new ChatMessage();
            prompt.sessionId = session.id;
            prompt.role = "user";
            prompt.seq = 1L;
            prompt.content = "Check this image";
            var reply = new ChatMessage();
            reply.sessionId = session.id;
            reply.role = "agent";
            reply.seq = 2L;
            reply.traceId = "trace-1";
            reply.content = "Tool failed";
            var messages = mock(ChatMessageStore.class);
            when(messages.latestSeq(session.id)).thenReturn(2L);
            when(messages.count(any())).thenReturn(2L);
            when(messages.find(any())).thenAnswer(invocation -> {
                ChatMessageQuery query = invocation.getArgument(0);
                if (!query.ascending) return List.of(reply);
                return List.of(prompt, reply).stream().skip(query.offset).limit(query.limit).toList();
            });
            bind(ChatMessageStore.class, messages);
            var turns = mock(TurnStateRegistry.class);
            when(turns.liveness(session.id)).thenReturn(TurnLiveness.NOT_RUNNING);
            bind(TurnStateRegistry.class, turns);
        }

        private void bindTraces() {
            var trace = new Trace();
            trace.traceId = "trace-1";
            trace.sessionId = "session-1";
            trace.userId = "owner-1";
            trace.input = "{\"system\":\"remembered fact\",\"api_key\":\"credential-secret\"}";
            var span = new Span();
            span.traceId = trace.traceId;
            span.spanId = "span-1";
            span.input = trace.input;
            var traces = mock(TraceService.class);
            when(traces.get(trace.traceId)).thenReturn(trace);
            when(traces.getWithPayload(trace.traceId)).thenReturn(trace);
            when(traces.span(trace.traceId, span.spanId)).thenReturn(span);
            bind(TraceService.class, traces);
            var spans = mock(SpanStore.class);
            when(spans.summariesByTrace(eq(trace.traceId), anyInt())).thenReturn(List.of(span));
            bind(SpanStore.class, spans);
            var visibility = mock(TraceVisibilityResolver.class);
            when(visibility.resolve("owner-1")).thenReturn(TraceVisibility.of(Set.of("owner-1")));
            bind(TraceVisibilityResolver.class, visibility);
            var access = mock(TraceAccessControl.class);
            when(access.canRead(any(), eq("owner-1"), any())).thenReturn(Boolean.TRUE);
            bind(TraceAccessControl.class, access);
        }

        private MongoSessionShareStore store() {
            var store = mock(MongoSessionShareStore.class);
            when(store.getBySessionId("session-1")).thenAnswer(invocation -> Optional.ofNullable(share.get()));
            when(store.getByToken(anyString())).thenAnswer(invocation -> {
                var value = share.get();
                return value != null && value.token.equals(invocation.getArgument(0)) ? Optional.of(value) : Optional.empty();
            });
            when(store.replace(any(), any())).thenAnswer(invocation -> {
                SessionShare value = invocation.getArgument(0);
                return share.compareAndSet(null, value);
            });
            doAnswer(invocation -> {
                var value = share.get();
                if (value != null && value.id.equals(invocation.getArgument(1))) value.revokedAt = ZonedDateTime.now();
                return null;
            }).when(store).revoke(anyString(), anyString(), any());
            return store;
        }

        private SessionShareFileService files() {
            var files = mock(SessionShareFileService.class);
            var file = new SessionShareFile();
            file.id = "file-ref";
            file.fileId = "source-file";
            file.fileName = "attachment.txt";
            file.contentType = "text/plain";
            when(files.capture(any(), any())).thenReturn(List.of(file));
            when(files.rewrite(anyString(), any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(files.requireFile(any(), anyString())).thenAnswer(invocation -> {
                if (!file.id.equals(invocation.getArgument(1))) throw new NotFoundException("file unavailable");
                return file;
            });
            when(files.read(any(), any())).thenReturn("attached-content".getBytes(StandardCharsets.UTF_8));
            return files;
        }
    }

    private static final class SharingModule extends SessionShareModule {
        private final MongoSessionShareStore store;
        private final SessionShareFileService files;

        SharingModule(MongoSessionShareStore store, SessionShareFileService files) {
            this.store = store;
            this.files = files;
        }

        @Override
        public <T> T bind(Class<T> instanceClass) {
            if (instanceClass == MongoSessionShareStore.class) return instanceClass.cast(store);
            if (instanceClass == SessionShareFileService.class) return super.bind(instanceClass, instanceClass.cast(files));
            return super.bind(instanceClass);
        }

        @Override
        public <T> T bind(Type type, String name, T instance) {
            return mockingDetails(instance).isMock() ? context.bind(type, name, instance) : super.bind(type, name, instance);
        }
    }
}
