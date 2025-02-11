package ai.core.example.service;

import ai.core.agent.Agent;
import ai.core.agent.AgentChain;
import ai.core.agent.NodeStatus;
import ai.core.agent.UserInputAgent;
import ai.core.agent.listener.listeners.DefaultAgentRunningEventListener;
import ai.core.defaultagents.CotAgent;
import ai.core.defaultagents.DefaultImageCaptionAgent;
import ai.core.defaultagents.DefaultImageGenerateAgent;
import ai.core.defaultagents.PromptOptimizeAgent;
import ai.core.defaultagents.SummaryAgent;
import ai.core.defaultagents.ThinkingClaudeAgent;
import ai.core.example.api.naixt.MCPToolCallRequest;
import ai.core.example.api.socialmedia.CotResponse;
import ai.core.example.api.socialmedia.CreateSocialMediaRequest;
import ai.core.example.api.socialmedia.CreateSocialMediaResponse;
import ai.core.example.api.socialmedia.CreateUserSocialMediaRequest;
import ai.core.example.api.socialmedia.CreateUserSocialMediaResponse;
import ai.core.example.api.socialmedia.FaceIdImageResponse;
import ai.core.example.api.socialmedia.OrderIssueResponse;
import ai.core.huggingface.HuggingfaceService;
import ai.core.huggingface.Path;
import ai.core.huggingface.flux.FillingImageRequest;
import ai.core.huggingface.flux.FluxIpAdapterRequest;
import ai.core.huggingface.iclight.RelightingImageRequest;
import ai.core.huggingface.iclight.RelightingWithBackgroundRequest;
import ai.core.huggingface.rmbg.RemoveImageBackgroundRequest;
import ai.core.mcp.client.MCPClientService;
import ai.core.mcp.client.MCPServerConfig;
import ai.core.image.providers.LiteLLMImageProvider;
import ai.core.image.providers.inner.GenerateImageRequest;
import ai.core.llm.providers.LiteLLMProvider;
import ai.core.llm.providers.inner.EmbeddingRequest;
import ai.core.persistence.PersistenceProvider;
import ai.core.persistence.providers.TemporaryPersistenceProvider;
import ai.core.prompt.Prompts;
import ai.core.rag.vectorstore.milvus.MilvusVectorStore;
import ai.core.tool.function.Functions;
import ai.core.tool.mcp.MCPToolCalls;
import ai.core.tool.tools.ExecutePythonScriptTool;
import core.framework.api.json.Property;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import core.framework.util.Strings;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * @author stephen
 */
public class ExampleService {
    private static final String IMAGE_CAPTION_COLLECTION = "image_captions";
    private static final String WIKI_COLLECTION = "wiki";

    PersistenceProvider persistenceProvider = new TemporaryPersistenceProvider();

    @Inject
    LiteLLMProvider liteLLMProvider;
    @Inject
    MilvusVectorStore milvusVectorStore;
    @Inject
    WeatherService weatherService;
    @Inject
    LiteLLMImageProvider llmImageProvider;
    @Inject
    SearchImageService searchImageService;
    @Inject
    HuggingfaceService huggingfaceService;
    @Inject
    ChatAgent chatAgent;
    @Inject
    UserInfoService userInfoService;

    public OrderIssueResponse groupStart(String query) {
        var group = OrderIssueGroup.create(liteLLMProvider, persistenceProvider, userInfoService);
        group.run(query, null);
        var rsp = new OrderIssueResponse();
        rsp.content = group.getOutput();
        rsp.conversation = List.of(group.getConversation().split("\n"));
        if (group.getNodeStatus() == NodeStatus.WAITING_FOR_USER_INPUT) {
            rsp.id = group.save();
            return rsp;
        }
        return rsp;
    }

    public OrderIssueResponse groupFinish(String id, String query) {
        var group = OrderIssueGroup.create(liteLLMProvider, persistenceProvider, userInfoService);
        group.load(id);
        group.run(query, null);
        var rsp = new OrderIssueResponse();
        rsp.content = group.getOutput();
        rsp.conversation = List.of(group.getConversation().split("\n"));
        return rsp;
    }

    public String chat(String query) {
        var agent = ThinkingClaudeAgent.of(liteLLMProvider);
//        return chatAgent.run(query);
        return agent.run(query, null);
    }

    public String userInputStart() {
        var agent = UserInputAgent.builder().persistenceProvider(persistenceProvider).build();
        agent.run("test need user input", null);
        return agent.save();
    }

    public String userInputFinish(String id, String query) {
        var agent = UserInputAgent.builder().persistenceProvider(persistenceProvider).build();
        agent.load(id);
        return agent.run(query, null);
    }

    public String mcpToolCallTest(MCPToolCallRequest request) {
        var mcpClientService = new MCPClientService(new MCPServerConfig(request.host, request.port));
        var agent = Agent.builder()
                .name("mcp-tool-call-agent")
                .description("mcp tool call agent")
                .systemPrompt("you are a tool call agent")
                .promptTemplate("")
                .toolCalls(MCPToolCalls.from(mcpClientService))
                .llmProvider(liteLLMProvider).build();
        return agent.run(request.query, null);
    }

    public CreateSocialMediaResponse createSocialMediaPost(CreateSocialMediaRequest request) {
        var agent = new SocialMediaGenerateAgent(liteLLMProvider, milvusVectorStore);
        var rst = agent.run(request.idea, request.location);
        var rsp = new CreateSocialMediaResponse();
        rsp.content = rst.postContent;
        rsp.contents = rst.postContents == null ? List.of() : rst.postContents;
        rsp.imageSuggestion = rst.imageSuggestion + Prompts.REALISM_PHOTO_STYLE_IMAGE_PROMPT_SUFFIX;
        rsp.imageUrl = llmImageProvider.generateImage(new GenerateImageRequest(rsp.imageSuggestion)).url();
//        rsp.imageUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(huggingfaceService.flux(rst.imageSuggestion));
        if (request.isGenerateVideo) {
//            var dishRst = JSON.fromJSON(DishJudgementAgent.DishJudgementResult.class, DishJudgementAgent.of(liteLLMProvider).run(request.idea, null));
            rsp.videoUrl = null;
        }
        return rsp;
    }

    public CreateUserSocialMediaResponse createUserSocialMediaPost(CreateUserSocialMediaRequest request) {
        Agent agent;
        if ("en".equals(request.language)) {
            agent = EndUserSocialMediaGenerateAgent.of(liteLLMProvider);
        } else {
            agent = EndUserChineseSocialMediaGenerateAgent.of(liteLLMProvider);
        }
        var imageCaption = new DefaultImageCaptionAgent().of(liteLLMProvider).run(request.refImageUrl, null);
        var rst = agent.run(request.idea, Map.of("location", request.location, "image_caption", imageCaption));
        var json = JSON.fromJSON(SocialMediaGenerateAgent.SocialMediaPostDTO.class, rst);
        var rsp = new CreateUserSocialMediaResponse();
        rsp.contents = json.postContents == null ? List.of() : json.postContents;
        rsp.contentsCn = json.postContentsCn == null ? List.of() : json.postContentsCn;
        rsp.imageSuggestion = json.imageSuggestion;
        rsp.imageUrls = searchImageService.search(json.imageSuggestionKeywords, 5);
        return rsp;
    }

    public String ideas(String used) {
        var agent = SocialMediaIdeaSuggestionAgent.of(liteLLMProvider, milvusVectorStore);
        return agent.run("", Map.of("used_suggestion_list", used));
    }

    public String userIdeas() {
        var agent = EndUserSocialMediaIdeaSuggestionAgent.of(liteLLMProvider, null);
        return agent.run("", null);
    }

    public String optimize(String prompt) {
        var agent = PromptOptimizeAgent.of(liteLLMProvider);
        return agent.run(prompt, null);
    }

    public String summaryOptimize(String prompt) {
        var agent = PromptOptimizeAgent.of(liteLLMProvider);
        agent.run(prompt, null);
        var summaryAgent = SummaryAgent.of(liteLLMProvider);
        var summaryChain = AgentChain.builder().name("summary-chain").description("summary the chain").build();
        summaryChain.addNode(agent);
        summaryChain.addNode(summaryAgent);
        summaryChain.run(prompt, null);
        return SummaryAgent.summaryTopic(summaryChain, summaryAgent);
    }

    public String debate(String topic) {
        var proAgent = Agent.builder()
                .name("debate-con-agent")
                .description("con debater")
                .systemPrompt("You are the negative side in a debate, and you do not support the viewpoint of the topic.You will analyze the arguments from the affirmative side and refute them one by one.")
                .promptTemplate("pro viewpoints: ")
                .llmProvider(liteLLMProvider).build();
        var conAgent = Agent.builder()
                .name("debate-pro-agent")
                .description("pro debater")
                .systemPrompt("You are the affirmative side in a debate, and you support the viewpoint of the topic.you will elaborate and provide examples from multiple perspectives to illustrate why you support this viewpoint.")
                .promptTemplate("topic: ")
                .llmProvider(liteLLMProvider).build();
        conAgent.addStatusChangedEventListener(NodeStatus.RUNNING, new DefaultAgentRunningEventListener());
        var debateChain = AgentChain.builder().name("debate-chain").description("chain of debate").build();
        debateChain.addNode(proAgent);
        debateChain.addNode(conAgent);
        debateChain.run(topic, null);
        return debateChain.getConversationText();
    }

    public String function(String prompt) {
        var agent = Agent.builder()
                .name("weather-agent")
                .description("get weather of a city")
                .systemPrompt("Your are an assistant help user get weather for example temperature or air quality of gaven cities. "
                              + "If query do not contain a city in the gave list, return 'I am weather toolkit, I don't known other things, so which city's weather you want to check?'.")
                .promptTemplate("topic: ")
                .toolCalls(Functions.from(weatherService, "get", "getAirQuality"))
                .llmProvider(liteLLMProvider).build();
        return agent.run(prompt, null);
    }

    public String initWikiKnowledge(String query) {
        var collection = WIKI_COLLECTION;
        if (milvusVectorStore.hasCollection(collection)) {
            milvusVectorStore.createWikiCollection(collection);
        }
        var embedding = liteLLMProvider.embedding(new EmbeddingRequest(query));
        milvusVectorStore.insertWiki(collection, query, embedding.embedding());
        return "OK";
    }

    public String initImageKnowledge(String url) {
        var collection = IMAGE_CAPTION_COLLECTION;
        if (!milvusVectorStore.hasCollection(collection)) {
            milvusVectorStore.createImageCaptionCollection(collection);
        }
        var query = new DefaultImageCaptionAgent().of(liteLLMProvider).run(url, null);
        var embedding = liteLLMProvider.embedding(new EmbeddingRequest(query));
        milvusVectorStore.insertImageCaption(collection, url, query, embedding.embedding());
        return query;
    }

    public String rag(String prompt) {
        var agent = new WonderExpertAgent(liteLLMProvider, milvusVectorStore);
        return agent.run(prompt);
    }

    public CotResponse cot(String prompt) {
        var agent = CotAgent.of(liteLLMProvider);
        var rsp = new CotResponse();
        rsp.text = agent.run(prompt, null);
        rsp.cot = CotAgent.getConversationText(agent);
        return rsp;
    }

    public String generateImage(String query) {
        var agent = DefaultImageGenerateAgent.of(llmImageProvider);
        return agent.run(query, null);
    }

    public String relight(String query, String prompt, Integer width, Integer height) {
        var req = new RelightingImageRequest();
        req.path = new Path();
        req.path.path = query;
        req.prompt = Strings.format("\"{}\"", prompt);
        req.imageWidth = width;
        req.imageHeight = height;
        return huggingfaceService.relighting(req).url;
    }

    public String relightWithBackground(String fg, String prompt, Integer width, Integer height, String bg) {
        var req = new RelightingWithBackgroundRequest();
        req.fg = Strings.format("\"{}\"", fg);
        req.prompt = Strings.format("\"{}\"", prompt);
        req.imageWidth = width;
        req.imageHeight = height;
        req.bg = Strings.format("\"{}\"", bg);
        return huggingfaceService.relightingWithBackground(req).url;
    }

    public String removeBackground(String query) {
        var req = new RemoveImageBackgroundRequest();
        req.path = new Path();
        req.path.path = query;
        return huggingfaceService.removeImageBackground(req).url;
    }

    public String fluxFill(String url, String prompt) {
        var req = new FillingImageRequest();
        req.bg = Strings.format("\"{}\"", url.replace("hdr\\", "hdr/"));
        req.prompt = Strings.format("\"{}\"", prompt);
        req.mask = "\"data:image/png;base64," + huggingfaceService.seg(url) + "\"";
        return huggingfaceService.fillingImage(req).url;
    }

    public String fluxIpAdapter(String url, String prompt) {
        var req = new FluxIpAdapterRequest();
        req.path = Strings.format("\"{}\"", url.replace("hdr\\", "hdr/"));
        req.prompt = Strings.format("\"{}\"", prompt);
        return huggingfaceService.fluxIpAdapter(req).url;
    }

    public String fluxStyleShape(String url, String prompt, String style) {
        var rst = ExecutePythonScriptTool.call("d:\\hf_gradio.py", List.of("style_shaping", url, style, prompt));
        return JSON.fromJSON(Result.class, rst).url;
    }

    public FaceIdImageResponse faceId(String url, String prompt) {
        var rst = ExecutePythonScriptTool.call("d:\\hf_gradio.py", List.of("ip_adapter_face_id", url, prompt));
        var j = JSON.fromJSON(LocalResults.class, convertListToJson(Objects.requireNonNull(rst)));
        var rsp = new FaceIdImageResponse();
        rsp.urls = j.rsts.stream().map(v -> v.image).toList();
        return rsp;
    }

    public String convertListToJson(String text) {
        return Strings.format("{\"rsts\": {}}", text);
    }

    public static class LocalResults {
        @Property(name = "rsts")
        public List<LocalResult> rsts;
    }

    public static class LocalResult {
        @Property(name = "image")
        public String image;
    }

    public static class Results {
        @Property(name = "rsts")
        public List<Result> rsts;
    }

    public static class Result {
        @Property(name = "url")
        public String url;

        @Property(name = "image")
        public Image image;
    }

    public static class Image {
        @Property(name = "url")
        public String url;
    }
}
