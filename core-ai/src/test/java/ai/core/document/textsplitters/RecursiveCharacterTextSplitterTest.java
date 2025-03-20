package ai.core.document.textsplitters;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class RecursiveCharacterTextSplitterTest {

    @Test
    void testSplitEmptyText() {
        var text = "";
        var chunks = new RecursiveCharacterTextSplitter().split(text);
        assertNotNull(chunks);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void testSplitDirTree() {
        var text = """
                d:\\hdr-project\\azure.properties
                d:\\hdr-project\\backend\\cleanup-kitchen-cooking-db-cronjob\\conf\\dev\\resources\\app.properties
                d:\\hdr-project\\backend\\cleanup-kitchen-cooking-db-cronjob\\conf\\dev\\resources\\sys.properties
                d:\\hdr-project\\backend\\cleanup-kitchen-cooking-db-cronjob\\conf\\prod\\resources\\app.properties
                d:\\hdr-project\\backend\\cleanup-kitchen-cooking-db-cronjob\\conf\\prod\\resources\\sys.properties
                d:\\hdr-project\\backend\\cleanup-kitchen-cooking-db-cronjob\\conf\\uat\\resources\\app.properties
                d:\\hdr-project\\backend\\cleanup-kitchen-cooking-db-cronjob\\conf\\uat\\resources\\sys.properties
                d:\\hdr-project\\backend\\cleanup-kitchen-cooking-db-cronjob\\src\\main\\java\\app\\cleanupdb\\blankview\\BlankView.java
                d:\\hdr-project\\backend\\cleanup-kitchen-cooking-db-cronjob\\src\\main\\java\\app\\cleanupdb\\CleanupApp.java
                d:\\hdr-project\\backend\\cleanup-kitchen-cooking-db-cronjob\\src\\main\\java\\app\\cleanupdb\\config\\CleanupConfigs.java
                d:\\hdr-project\\backend\\cleanup-kitchen-cooking-db-cronjob\\src\\main\\java\\app\\cleanupdb\\config\\CleanupPolicy.java
                d:\\hdr-project\\backend\\cleanup-kitchen-cooking-db-cronjob\\src\\main\\java\\app\\cleanupdb\\config\\CleanupTaskConfig.java
                d:\\hdr-project\\backend\\cleanup-kitchen-cooking-db-cronjob\\src\\main\\java\\app\\cleanupdb\\tasks\\CleanupService.java
                d:\\hdr-project\\backend\\cleanup-kitchen-cooking-db-cronjob\\src\\main\\java\\Main.java
                d:\\hdr-project\\backend\\cleanup-kitchen-cooking-db-cronjob\\src\\main\\resources\\app.properties
                d:\\hdr-project\\backend\\cleanup-kitchen-cooking-db-cronjob\\src\\main\\resources\\sys.properties
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\daoproxy\\domain\\ExtraTrackingData.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\daoproxy\\ExtraTrackingDataContextService.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\daoproxy\\MongoProxy.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\daoproxy\\MongoProxyBinder.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\daoproxy\\ProxyHelper.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\daoproxy\\RepositoryProxy.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\daoproxy\\TrackingActionView.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\db\\CanceledOrigin.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\db\\DeliveryBy.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\db\\DiningOption.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\db\\From.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\db\\HDRType.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\db\\InPersonSource.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\db\\OrderStatus.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\db\\OrderType.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\db\\ScheduleType.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\ext\\lock\\collection\\DistributedLock.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\ext\\lock\\DistributedLock.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\ext\\lock\\DistributedLockInterface.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\ext\\lock\\MongoDistributedLockImpl.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\ext\\lock\\RedisDistributedLockImpl.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\mongo\\MongoQueries.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\sql\\SQLHelper.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\util\\LazyLoader.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\util\\Partition.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\util\\time\\OpeningTime.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\util\\time\\Time.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\util\\time\\TimeUtils.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\util\\time\\ZoneIds.java
                d:\\hdr-project\\backend\\common-library\\src\\main\\java\\app\\common\\version\\VersionUtil.java
                d:\\hdr-project\\backend\\common-library\\src\\test\\java\\commonlibrary\\time\\TimeUtilsTest.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\conf\\dev\\resources\\app.properties
                d:\\hdr-project\\backend\\cooking-optimization-service\\conf\\dev\\resources\\sys.properties
                d:\\hdr-project\\backend\\cooking-optimization-service\\conf\\prod\\resources\\app.properties
                d:\\hdr-project\\backend\\cooking-optimization-service\\conf\\prod\\resources\\sys.properties
                d:\\hdr-project\\backend\\cooking-optimization-service\\conf\\uat\\resources\\app.properties
                d:\\hdr-project\\backend\\cooking-optimization-service\\conf\\uat\\resources\\sys.properties
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\batching\\domain\\ApplianceBatch.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\batching\\domain\\HotHoldingBatch.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\batching\\domain\\mongo\\BatchSuggestionV2.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\batching\\domain\\mongo\\CookingBatchInput.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\batching\\domain\\mongo\\CookingBatchInputStep.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\batching\\domain\\mongo\\CookingBatchOutput.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\batching\\domain\\mongo\\HotHoldingBatchInput.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\batching\\domain\\mongo\\HotHoldingBatchOutput.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\batching\\domain\\mongo\\HotHoldingBatchSuggestion.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\batching\\domain\\mongo\\HotHoldingTaskType.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\batching\\domain\\mongo\\PreparationType.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\batching\\service\\BatchingMapper.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\batching\\service\\CookBatching.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\batching\\service\\CookBatchingHelpers.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\batching\\service\\CookBatchingService.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\batching\\service\\CookBatchingStepBuilder.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\batching\\service\\HotHoldBatchingService.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\batching\\service\\HotHoldingBatching.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\batching\\service\\HotHoldingTaskBuilder.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\batching\\web\\BatchingWebServiceImpl.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\BatchingModule.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\common\\CollectionHelpers.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\CosApp.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\replay\\domain\\SequencingView.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\replay\\service\\SequencingContextService.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\replay\\web\\SequencingReplayWebServiceImpl.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\BatchHelpers.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\BatchScorer.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\BatchSequencer.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\Bucket.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\HoldBackStrategy.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\HotHoldDelayFilter.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\InventoryMapper.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\ItemMapper.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\ItemScore.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\ItemSegment.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\KcsSuperPodMapper.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\KitchenContext.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\KitchenOrderContext.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\KitchenSequenceScores.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\KmsSuperPodMapper.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\mongo\\Appliance.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\mongo\\ApplianceType.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\mongo\\CookingActivity.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\mongo\\DayOfWeek.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\mongo\\EstimatorSettings.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\mongo\\InputHotHoldInventory.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\mongo\\InputInventory.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\mongo\\InputItem.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\mongo\\InputPod.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\mongo\\InputRethermInventory.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\mongo\\InputStep.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\mongo\\InputSuperPod.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\mongo\\ItemStatus.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\mongo\\PodType.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\mongo\\ResourceStatus.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\mongo\\ResourceType.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\mongo\\Restaurant.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\mongo\\SequencedBatch.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\mongo\\SequencingContext.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\mongo\\SequencingScore.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\mongo\\SimulationInput.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\mongo\\SimulationOutput.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\mongo\\StepStatus.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\mongo\\TurboDeck.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\PodContext.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\SegmentedItem.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\SequencedBatchMapper.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\SequenceScoreCalculator.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\SequencingBuilders.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\SequencingScoreMapper.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\StepMapper.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\domain\\SuperPodInventory.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\kafka\\BulkCookingTaskItemEventHandler.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\kafka\\BulkCookTimeEstimationTriggerHandler.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\service\\AdHocSequencingService.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\service\\SequencingService.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\sequencing\\web\\SequencingWebServiceImpl.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\SequencingModule.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\SequencingReplayModule.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\simulation\\domain\\ApplianceCapacityConfig.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\simulation\\domain\\BatchingStrategy.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\simulation\\domain\\BatchSuggestion.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\simulation\\domain\\ChefResource.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\simulation\\domain\\InventoryAllocator.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\simulation\\domain\\ItemPriorityScheduler.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\simulation\\domain\\KitchenInventory.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\simulation\\domain\\KitchenResource.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\simulation\\domain\\KitchenResourceFactory.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\simulation\\domain\\KitchenSimulation.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\simulation\\domain\\LegacyBatchingStrategy.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\simulation\\domain\\LenientGenericResource.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\simulation\\domain\\OrderItem.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\simulation\\domain\\OrderItemStep.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\simulation\\domain\\PressResource.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\simulation\\domain\\ResourceRequirement.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\simulation\\domain\\SchedulingStrategy.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\simulation\\domain\\SimulationHelpers.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\simulation\\domain\\StepTimes.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\simulation\\domain\\StrictGenericResource.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\simulation\\domain\\Task.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\simulation\\domain\\TrackedTimes.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\simulation\\domain\\UnitOfWork.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\simulation\\domain\\WaterBathResource.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\app\\cos\\simulation\\domain\\WorkItem.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\java\\Main.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\resources\\app.properties
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\main\\resources\\sys.properties
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\batching\\service\\CookBatchingTest.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\batching\\service\\HotHoldingBatchingTest.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\batching\\service\\TestObjects.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\batching\\service\\TestObjectsFryer.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\ConfigTest.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\fixtures\\FixtureHelpers.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\fixtures\\MiscHelpers.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\fixtures\\RandomHelpers.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\fixtures\\SimulationHelpers.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\fixtures\\testdata\\builders\\InputInventoryTd.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\fixtures\\testdata\\builders\\InputItemTd.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\fixtures\\testdata\\builders\\InputPodTd.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\fixtures\\testdata\\builders\\InputStepTd.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\fixtures\\testdata\\TestAppliances.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\fixtures\\testdata\\TestInputPods.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\fixtures\\testdata\\TestItems.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\fixtures\\testdata\\TestSuperPods.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\fixtures\\TimeHelpers.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\IntegrationTest.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\sequencing\\domain\\BatchScorerTest.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\sequencing\\domain\\HoldBackStrategyTest.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\sequencing\\domain\\HotHoldDelayFilterTest.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\sequencing\\domain\\mongo\\InputItemEnumTests.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\sequencing\\domain\\SequenceScoreCalculatorTest.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\sequencing\\service\\SequencingServiceTest.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\simulation\\domain\\KitchenSimulationTest.java
                d:\\hdr-project\\backend\\cooking-optimization-service\\src\\test\\java\\app\\cos\\TestModule.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\batching\\BatchInputStepView.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\batching\\HotHoldingTaskTypeView.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\batching\\SuggestCookingBatchRequest.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\batching\\SuggestCookingBatchResponse.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\batching\\SuggestHotHoldingBatchRequest.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\batching\\SuggestHotHoldingBatchResponse.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\BatchingWebService.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\common\\CookingActivityView.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\common\\InputItemView.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\common\\InputStepView.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\common\\ItemStatusView.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\common\\kafka\\CookingOptimizationSequencingResultMessage.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\common\\kafka\\CookingOptimizationServiceTopics.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\common\\kafka\\CookTimeEstimationTriggerMessage.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\common\\PreparationTypeView.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\common\\ResourceTypeView.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\common\\StepStatusView.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\replay\\GetSequencingContextResponse.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\replay\\PodTypeView.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\replay\\SearchSequencingTimelineRequest.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\replay\\SearchSequencingTimelineResponse.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\sequencing\\BatchAndSequenceRequest.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\sequencing\\DebugSequencingRequest.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\sequencing\\GetSequencedGroupsRequest.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\sequencing\\GetSequencedGroupsResponse.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\sequencing\\GetSequencingScoresRequest.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\sequencing\\GetSequencingScoresResponse.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\SequencingReplayWebService.java
                d:\\hdr-project\\backend\\cooking-optimization-service-interface\\src\\main\\java\\app\\cos\\api\\SequencingWebService.java
                d:\\hdr-project\\backend\\cooking-optimization-service-mongo-migration\\conf\\dev\\resources\\sys.properties
                d:\\hdr-project\\backend\\cooking-optimization-service-mongo-migration\\conf\\prod\\resources\\sys.properties
                d:\\hdr-project\\backend\\cooking-optimization-service-mongo-migration\\conf\\uat\\resources\\sys.properties
                d:\\hdr-project\\backend\\cooking-optimization-service-mongo-migration\\src\\main\\java\\app\\cos\\script\\BatchSuggestionScript.java
                d:\\hdr-project\\backend\\cooking-optimization-service-mongo-migration\\src\\main\\java\\app\\cos\\script\\BatchSuggestionV2Script.java
                d:\\hdr-project\\backend\\cooking-optimization-service-mongo-migration\\src\\main\\java\\app\\cos\\script\\HotHoldingBatchSuggestionScript.java
                d:\\hdr-project\\backend\\cooking-optimization-service-mongo-migration\\src\\main\\java\\app\\cos\\script\\SequencedBatchesScript.java
                d:\\hdr-project\\backend\\cooking-optimization-service-mongo-migration\\src\\main\\java\\app\\cos\\script\\SequencingContextsScript.java
                d:\\hdr-project\\backend\\cooking-optimization-service-mongo-migration\\src\\main\\java\\app\\cos\\script\\SequencingScoresScript.java
                d:\\hdr-project\\backend\\cooking-optimization-service-mongo-migration\\src\\main\\java\\Main.java
                d:\\hdr-project\\backend\\cooking-optimization-service-mongo-migration\\src\\main\\resources\\sys.properties
                d:\\hdr-project\\backend\\cooking-optimization-service-v2\\conf\\dev\\resources\\sys.properties
                d:\\hdr-project\\backend\\cooking-optimization-service-v2\\src\\main\\java\\app\\cosv2\\CosApp.java
                d:\\hdr-project\\backend\\cooking-optimization-service-v2\\src\\main\\java\\app\\cosv2\\optimization\\domain\\OptimizationModelWrapper.java
                d:\\hdr-project\\backend\\cooking-optimization-service-v2\\src\\main\\java\\app\\cosv2\\optimization\\service\\OptimizationService.java
                d:\\hdr-project\\backend\\cooking-optimization-service-v2\\src\\main\\java\\app\\cosv2\\optimization\\web\\OptimizationWebServiceImpl.java
                d:\\hdr-project\\backend\\cooking-optimization-service-v2\\src\\main\\java\\app\\cosv2\\OptimizationModule.java
                d:\\hdr-project\\backend\\cooking-optimization-service-v2\\src\\main\\java\\Main.java
                d:\\hdr-project\\backend\\cooking-optimization-service-v2\\src\\main\\resources\\sys.properties
                d:\\hdr-project\\backend\\cooking-optimization-service-v2\\src\\test\\java\\app\\cosv2\\IntegrationTest.java
                d:\\hdr-project\\backend\\cooking-optimization-service-v2\\src\\test\\java\\app\\cosv2\\optimization\\domain\\OptimizationModelWrapperTest.java
                d:\\hdr-project\\backend\\cooking-optimization-service-v2\\src\\test\\java\\app\\cosv2\\optimization\\web\\OptimizationWebServiceImplTest.java
                d:\\hdr-project\\backend\\cooking-optimization-service-v2\\src\\test\\java\\app\\cosv2\\TestModule.java
                d:\\hdr-project\\backend\\cooking-optimization-service-v2-interface\\src\\main\\java\\app\\cosv2\\api\\optimization\\KitchenOptimizationRequest.java
                d:\\hdr-project\\backend\\cooking-optimization-service-v2-interface\\src\\main\\java\\app\\cosv2\\api\\optimization\\KitchenOptimizationResponse.java
                d:\\hdr-project\\backend\\cooking-optimization-service-v2-interface\\src\\main\\java\\app\\cosv2\\api\\OptimizationWebService.java
                d:\\hdr-project\\backend\\delivery-zone-service\\conf\\dev\\resources\\app.properties
                d:\\hdr-project\\backend\\delivery-zone-service\\conf\\dev\\resources\\slack.properties
                d:\\hdr-project\\backend\\delivery-zone-service\\conf\\dev\\resources\\sys.properties
                d:\\hdr-project\\backend\\delivery-zone-service\\conf\\prod\\resources\\app.properties
                d:\\hdr-project\\backend\\delivery-zone-service\\conf\\prod\\resources\\slack.properties
                d:\\hdr-project\\backend\\delivery-zone-service\\conf\\prod\\resources\\sys.properties
                d:\\hdr-project\\backend\\delivery-zone-service\\conf\\uat\\resources\\app.properties
                d:\\hdr-project\\backend\\delivery-zone-service\\conf\\uat\\resources\\slack.properties
                d:\\hdr-project\\backend\\delivery-zone-service\\conf\\uat\\resources\\sys.properties
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\cache\\HDRCaches.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\cache\\HDRCacheService.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\cache\\WonderSpotPartnerCache.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\cache\\WonderSpotPartnerCacheService.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\dao\\DeliveryZoneProxyHelper.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\deliveryzone\\domain\\DeliveryZone.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\deliveryzone\\service\\DeliveryZoneBuilder.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\deliveryzone\\service\\DeliveryZoneQueryHelper.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\deliveryzone\\service\\DeliveryZoneService.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\deliveryzone\\service\\FeatureV2.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\deliveryzone\\service\\MatchedDeliveryZone.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\deliveryzone\\service\\MultiPolygon.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\deliveryzone\\service\\MultiPolygonUtil.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\deliveryzone\\util\\MongoRegexSpecialCharEscapeUtil.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\deliveryzone\\util\\TupleUtil.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\deliveryzone\\web\\BODeliveryZoneWebServiceImpl.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\deliveryzone\\web\\DeliveryZoneWebServiceImpl.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\DeliveryZoneApp.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\DeliveryZoneModule.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\domain\\DeliveryZoneDomainTracking.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\domain\\DeliveryZoneMultipolygonTracking.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\client\\RunModelAPIClient.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\client\\RunModelAPIClientException.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\client\\RunModelAPIConfig.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\client\\RunModelWebService.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\client\\StandardPolygon.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\domain\\AddressConfiguration.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\domain\\AreaConfiguration.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\domain\\AssociatedAreaGroup.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\domain\\BatchGOVModel.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\domain\\GOV.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\domain\\GOVModel.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\domain\\GroupConfiguration.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\domain\\LocationType.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\domain\\LockStatus.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\domain\\Polygon.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\domain\\RunState.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\kafka\\FetchGOVModelMessageHandler.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\service\\AddressConfService.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\service\\AreaConfService.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\service\\AssociatedAreaGroupService.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\service\\exception\\DuplicateAddressException.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\service\\exception\\GOVModelLockedException.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\service\\exception\\InvalidStateException.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\service\\GOVModelDTOBuilder.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\service\\GOVModelService.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\service\\GroupService.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\service\\MapService.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\service\\PolygonContext.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\service\\PolygonService.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\web\\GOVModelWebServiceImpl.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\govmodel\\web\\MapWebServiceImpl.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\GOVModelModule.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\kafka\\CorporateOrderClientChangedMessageHandler.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\kafka\\DeliveryZoneDomainTrackingMessageHandler.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\kafka\\HDRSnapshotMessageHandler.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\kafka\\trackingprocessor\\AbstractHDRTrackingProcessor.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\kafka\\trackingprocessor\\ChangeFieldView.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\kafka\\trackingprocessor\\DeliveryZoneTrackingProcessor.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\app\\deliveryzone\\kafka\\trackingprocessor\\ProcessResult.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\java\\Main.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\resources\\app.properties
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\resources\\slack.properties
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\main\\resources\\sys.properties
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\test\\java\\app\\deliveryzone\\ConfigTest.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\test\\java\\app\\deliveryzone\\deliveryzone\\service\\DeliveryZoneServiceTest.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\test\\java\\app\\deliveryzone\\EnumTest.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\test\\java\\app\\deliveryzone\\IntegrationTest.java
                d:\\hdr-project\\backend\\delivery-zone-service\\src\\test\\java\\app\\deliveryzone\\TestModule.java
                """;
        var chunks = new RecursiveCharacterTextSplitter().split(text);
        assertEquals(11, chunks.size());
    }

    @Test
    @SuppressWarnings("MethodLength")
    void testSplitJavaCode() {
        var code = """
                package com.chancetop.naixt.agent.utils;
                
                import com.chancetop.naixt.agent.api.naixt.Action;
                import com.chancetop.naixt.agent.api.naixt.FileContent;
                import core.framework.util.Strings;
                
                import java.io.IOException;
                import java.nio.charset.StandardCharsets;
                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.nio.file.Paths;
                import java.util.logging.Logger;
                
                /**
                 * @author stephen
                 */
                public class IdeUtils {
                    private static final Logger LOGGER = Logger.getLogger(IdeUtils.class.getName());
                
                    public static String getFileContent(String workspacePath, String path) {
                        if (Strings.isBlank(path)) return "Path is blank.";
                        var truePath = toAbsolutePath(workspacePath, path);
                        try {
                            return Files.readString(Paths.get(truePath), StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            LOGGER.warning("Failed to read file: " + path);
                            return Strings.format("failed to read file<{}>, please check your path", path);
                        }
                    }
                
                    public static String toWorkspaceRelativePath(String workspacePath, String currentFilePath) {
                        if (Strings.isBlank(workspacePath) || Strings.isBlank(currentFilePath)) return currentFilePath;
                
                        var absolutePath = Paths.get(currentFilePath);
                        var basePath = Paths.get(workspacePath);
                
                        try {
                            absolutePath = absolutePath.toRealPath();
                            basePath = basePath.toRealPath();
                        } catch (IOException e) {
                            LOGGER.warning("Failed to resolve real path.");
                            return currentFilePath;
                        }
                
                        if (!absolutePath.startsWith(basePath)) {
                            return currentFilePath;
                        }
                
                        return basePath.relativize(absolutePath).toString();
                    }
                
                    public static void doChange(String workspace, FileContent fileContent) {
                        if (fileContent == null || Strings.isBlank(fileContent.filePath)) return;
                        if (fileContent.action == Action.DELETE) {
                            try {
                                Files.deleteIfExists(Paths.get(toAbsolutePath(workspace, fileContent.filePath)));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            return;
                        }
                        try {
                            var path = Paths.get(toAbsolutePath(workspace, fileContent.filePath));
                            if (fileContent.action == Action.ADD) {
                                if (path.getParent() != null) {
                                    Files.createDirectories(path.getParent());
                                }
                                if (!Files.exists(path)) {
                                    Files.createFile(path);
                                }
                            }
                            Files.writeString(path, fileContent.content, StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                
                    private static String toAbsolutePath(String workspace, String filePath) {
                        Path absolutePath;
                        var path = Paths.get(filePath);
                        if (path.isAbsolute()) {
                            absolutePath = path;
                        } else {
                            var workspacePath = Paths.get(workspace);
                            absolutePath = workspacePath.resolve(filePath).normalize();
                        }
                        return absolutePath.toString();
                    }
                
                    public static String getDirFileTree(String workspacePath, String path, Boolean recursive) {
                        if (Strings.isBlank(path)) return "";
                        try {
                            var truePath = toAbsolutePath(workspacePath, path);
                            var rootPath = Paths.get(truePath);
                            if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
                                return "Invalid workspace directory.";
                            }
                            return buildDirFileTree(rootPath, recursive) + "\\n";
                        } catch (IOException e) {
                            LOGGER.warning("Failed to read workspace directory: " + path);
                            return "Error reading workspace directory, please check your path.";
                        }
                    }
                
                    private static String buildDirFileTree(Path current, Boolean recursive) throws IOException {
                        var treeBuilder = new StringBuilder();
                
                        try (var stream = Files.newDirectoryStream(current, entry -> Files.isRegularFile(entry) && filterFile(entry) || Files.isDirectory(entry))) {
                            for (var entry : stream) {
                                if (isGitIgnore(entry)) continue;
                                if (Files.isRegularFile(entry)) {
                                    treeBuilder.append(entry).append('\\n');
                                }
                                if (recursive && Files.isDirectory(entry)) {
                                    var subTree = buildDirFileTree(entry, true);
                                    if (subTree.isEmpty()) continue;
                                    treeBuilder.append(subTree).append('\\n');
                                }
                            }
                        }
                
                        return treeBuilder.isEmpty() ? "" : treeBuilder.toString().replaceAll("\\n\\\\s*\\\\n", "\\n");
                    }
                
                    private static boolean isGitIgnore(Path path) {
                        var entry = path.getFileName();
                        return entry.toString().startsWith(".")
                                || "build".equals(entry.toString())
                                || "gradle".equals(entry.toString())
                                || "bin".equals(entry.toString());
                    }
                
                    private static boolean filterFile(Path entry) {
                        return entry.toString().endsWith(".java")
                                || entry.toString().endsWith(".kt")
                                || entry.toString().endsWith(".xml")
                                || entry.toString().endsWith(".properties");
                    }
                }
                """;
        var chunks = RecursiveCharacterTextSplitter.fromLanguage(LanguageSeparators.Language.JAVA).split(code);
        assertEquals(3, chunks.size());
    }

    @Test
    void testSplitWithDefaultSeparators() {
        var text = "This is a sample text.\nIt contains multiple lines.\n\nAnd some paragraphs.";
        var chunks = new RecursiveCharacterTextSplitter().split(text);
        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        assertEquals(1, chunks.size());
    }

    @Test
    void testSplitWithCustomChunkSizeNoOverlap() {
        var chunkSize = 10;
        var textSplitter = new RecursiveCharacterTextSplitter(List.of(" "), chunkSize, 0, true, false);
        var text = "The quick brown fox jumps over the lazy dog.";
        var chunks = textSplitter.split(text);
        assertNotNull(chunks);
        assertEquals(6, chunks.size());
        for (var chunk : chunks) {
            assertTrue(chunk.chunk().length() <= chunkSize);
        }
    }

    @Test
    void testSplitWithSplitterAndCustomChunkSizeNoOverlap() {
        var textSplitter = new RecursiveCharacterTextSplitter(Arrays.asList(",", "."), 15, 0, true, false);
        var text = "Hello, world. This is a test.";
        var chunks = textSplitter.split(text);
        assertNotNull(chunks);
        assertEquals(4, chunks.size());
        assertEquals("Hello,", chunks.get(0).chunk());
        assertEquals(" world.", chunks.get(1).chunk());
        assertEquals(" This is a test", chunks.get(2).chunk());
        assertEquals(".", chunks.get(3).chunk());
    }

    @Test
    void testSplitWithRegexSeparators() {
        var textSplitter = new RecursiveCharacterTextSplitter(List.of("\\d+"), 50, 10, true, true);
        var text = "Section1ContentSection2ContentSection3Content";
        var chunks = textSplitter.split(text);
        assertNotNull(chunks);
        assertEquals(1, chunks.size());
        assertEquals(text, chunks.getFirst().chunk());
    }
}
