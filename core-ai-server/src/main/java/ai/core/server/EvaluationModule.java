package ai.core.server;

import ai.core.api.server.evaluation.EvaluationWebService;
import ai.core.server.evaluation.grading.EvalGraderEngine;
import ai.core.server.evaluation.service.EvalAgentRunGateway;
import ai.core.server.evaluation.service.EvalComparator;
import ai.core.server.evaluation.service.EvalDatasetImporter;
import ai.core.server.evaluation.service.EvalDatasetParser;
import ai.core.server.evaluation.service.EvalGraderSuiteService;
import ai.core.server.evaluation.service.EvalReportBuilder;
import ai.core.server.evaluation.service.EvalRunService;
import ai.core.server.evaluation.service.EvalSchedulerJob;
import ai.core.server.evaluation.service.EvalSnapshotBuilder;
import ai.core.server.evaluation.service.EvalSpecService;
import ai.core.server.evaluation.service.EvalTrialExecutor;
import ai.core.server.evaluation.service.EvaluationService;
import ai.core.server.evaluation.store.EvalRunStore;
import ai.core.server.evaluation.store.EvaluationStore;
import ai.core.server.evaluation.store.PgEvalRunStore;
import ai.core.server.evaluation.store.PgEvaluationStore;
import ai.core.server.web.EvaluationWebServiceImpl;
import core.framework.module.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Offline agent evaluation. The store is PostgreSQL-only: the server already requires pg mode for
 * messaging, and a second Mongo implementation would not be a supported deployment.
 *
 * @author stephen
 */
public class EvaluationModule extends Module {
    private static final Logger LOGGER = LoggerFactory.getLogger(EvaluationModule.class);

    @Override
    protected void initialize() {
        if (!"pg".equals(property("sys.store.mode").orElse("mongo"))) {
            LOGGER.warn("agent evaluation is disabled: sys.store.mode must be pg");
            return;
        }
        bind(EvaluationStore.class, bind(PgEvaluationStore.class));
        bind(EvalRunStore.class, bind(PgEvalRunStore.class));
        bind(EvalDatasetParser.class);
        bind(EvalGraderEngine.class);
        bind(EvalDatasetImporter.class);
        bind(EvalGraderSuiteService.class);
        bind(EvalSpecService.class);
        bind(EvalSnapshotBuilder.class);
        bind(EvalReportBuilder.class);
        bind(EvalComparator.class);
        bind(EvaluationService.class);
        bind(EvalAgentRunGateway.class);
        bind(EvalRunService.class);
        bind(EvalTrialExecutor.class);
        schedule().fixedRate("evaluation-trial-scheduler", bind(EvalSchedulerJob.class), Duration.ofSeconds(2));
        api().service(EvaluationWebService.class, bind(EvaluationWebServiceImpl.class));
    }
}
