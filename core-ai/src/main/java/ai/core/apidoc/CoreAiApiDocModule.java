package ai.core.apidoc;

import core.framework.http.HTTPMethod;
import core.framework.module.Module;

import java.util.Set;

/**
 * @author stephen
 */
public class CoreAiApiDocModule extends Module {
    private String appDescription;
    private boolean warnOnMissingDescription = true;
    private boolean failOnMissingDescription;

    public CoreAiApiDocModule appDescription(String appDescription) {
        this.appDescription = appDescription;
        return this;
    }

    public CoreAiApiDocModule warnOnMissingDescription(boolean warnOnMissingDescription) {
        this.warnOnMissingDescription = warnOnMissingDescription;
        return this;
    }

    public CoreAiApiDocModule failOnMissingDescription(boolean failOnMissingDescription) {
        this.failOnMissingDescription = failOnMissingDescription;
        return this;
    }

    @Override
    protected void initialize() {
        var apiController = context.apiController;
        var serviceInterfaces = apiController.serviceInterfaces == null ? Set.<Class<?>>of() : apiController.serviceInterfaces;
        var beanClasses = apiController.beanClasses == null ? Set.<Class<?>>of() : apiController.beanClasses;
        var controller = new AiApiDocController(apiController.accessControl, serviceInterfaces, beanClasses,
                appDescription, warnOnMissingDescription, failOnMissingDescription);
        context.route(HTTPMethod.GET, "/_sys/api/ai", controller, true);
        onStartup(controller::initialize);
    }
}
