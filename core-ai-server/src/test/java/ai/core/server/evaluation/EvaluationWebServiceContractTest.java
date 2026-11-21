package ai.core.server.evaluation;

import ai.core.api.server.AgentRunWebService;
import ai.core.api.server.DatasetWebService;
import ai.core.api.server.evaluation.EvaluationWebService;
import ai.core.server.web.EvaluationWebServiceImpl;
import core.framework.internal.bean.BeanClassValidator;
import core.framework.internal.web.service.WebServiceInterfaceValidator;
import core.framework.internal.web.service.WebServiceImplValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * @author stephen
 */
class EvaluationWebServiceContractTest {
    @Test
    void interfaceAndImplementationPassCoreNgValidation() {
        assertDoesNotThrow(() -> {
            var beanValidator = new BeanClassValidator();
            new WebServiceInterfaceValidator(AgentRunWebService.class, beanValidator).validate();
            new WebServiceInterfaceValidator(DatasetWebService.class, beanValidator).validate();
            new WebServiceInterfaceValidator(EvaluationWebService.class, beanValidator).validate();
            new WebServiceImplValidator<>(EvaluationWebService.class, new EvaluationWebServiceImpl()).validate();
        });
    }
}
