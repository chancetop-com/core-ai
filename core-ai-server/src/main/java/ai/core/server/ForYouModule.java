package ai.core.server;

import ai.core.server.web.foryou.ForYouController;
import ai.core.server.web.foryou.ForYouService;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class ForYouModule extends Module {
    @Override
    protected void initialize() {
        bind(ForYouService.class);

        var controller = bind(ForYouController.class);
        http().route(HTTPMethod.GET, "/api/for-you", controller::dashboard);
        http().route(HTTPMethod.GET, "/api/for-you/reports", controller::listReports);
        http().route(HTTPMethod.POST, "/api/for-you/reports", controller::createReport);
        http().route(HTTPMethod.PUT, "/api/for-you/reports/:id", controller::updateReport);
        http().route(HTTPMethod.DELETE, "/api/for-you/reports/:id", controller::deleteReport);
        http().route(HTTPMethod.GET, "/api/for-you/todos", controller::listTodos);
        http().route(HTTPMethod.POST, "/api/for-you/todos", controller::createTodo);
        http().route(HTTPMethod.PUT, "/api/for-you/todos/:id", controller::updateTodo);
        http().route(HTTPMethod.DELETE, "/api/for-you/todos/:id", controller::deleteTodo);
        http().route(HTTPMethod.GET, "/api/for-you/files", controller::listFiles);
        http().route(HTTPMethod.GET, "/api/for-you/token-usage", controller::tokenUsage);
    }
}
