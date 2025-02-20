package ai.core.example.controller;

import core.framework.web.Controller;
import core.framework.web.Request;
import core.framework.web.Response;

/**
 * @author stephen
 */
public class StopServiceController implements Controller {
    @Override
    public Response execute(Request request) throws Exception {
        new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }).start();
        return Response.text("Ok");
    }
}
