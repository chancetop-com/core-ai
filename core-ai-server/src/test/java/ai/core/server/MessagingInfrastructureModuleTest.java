package ai.core.server;

import ai.core.server.messaging.PgLeaseOwnerRow;
import ai.core.server.messaging.PgPodAddressRow;
import ai.core.server.messaging.RpcClient;
import ai.core.server.messaging.SessionCommand;
import ai.core.server.web.PodLocalExecutor;
import ai.core.server.web.sse.ChannelService;
import ai.core.utils.JsonUtil;
import com.sun.net.httpserver.HttpServer;
import core.framework.db.Database;
import core.framework.internal.log.LogManager;
import core.framework.internal.module.ModuleContext;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author xander
 */
class MessagingInfrastructureModuleTest {
    @Test
    void forwardsSandboxCatalogToTheRegisteredOwner() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/_internal/rpc", exchange -> {
            try (exchange) {
                var target = exchange.getRequestHeaders().getFirst("X-Target-Pod");
                var body = RpcClient.okResponse(JsonUtil.toJson(target)).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            var database = mock(Database.class);
            var owner = new PgLeaseOwnerRow();
            owner.ownerPod = "remote-audit-pod";
            var address = new PgPodAddressRow();
            address.address = "127.0.0.1:" + server.getAddress().getPort();
            when(database.selectOne(anyString(), eq(PgLeaseOwnerRow.class), eq("audit-session"), anyInt())).thenReturn(Optional.of(owner));
            when(database.selectOne(anyString(), eq(PgPodAddressRow.class), eq(owner.ownerPod), anyInt())).thenReturn(Optional.of(address));

            var module = new TestMessagingModule(database);
            module.initialize();
            var command = SessionCommand.sandboxCatalog("audit-session", "user", "request");
            var result = module.bean(PodLocalExecutor.class).execute(command.sessionId(), () -> "local", command, String.class);

            assertEquals(owner.ownerPod, result);
        } finally {
            server.stop(0);
        }
    }

    private static final class TestMessagingModule extends MessagingInfrastructureModule {
        TestMessagingModule(Database database) {
            context = new ModuleContext(new LogManager());
            context.propertyManager.properties.set("sys.store.mode", "pg");
            context.propertyManager.properties.set("sys.internal.token", "test-token");
            context.bind(Database.class, null, database);
            bind(ChannelService.class);
        }
    }
}
