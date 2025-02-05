package ai.core.huggingface;

import ai.core.huggingface.flux.FillingImageRequest;
import ai.core.huggingface.flux.FluxInferenceRequest;
import ai.core.huggingface.flux.FluxIpAdapterRequest;
import ai.core.huggingface.iclight.RelightingImageRequest;
import ai.core.huggingface.iclight.RelightingWithBackgroundRequest;
import ai.core.huggingface.rmbg.RemoveImageBackgroundRequest;
import ai.core.huggingface.segformerb2clothes.SegmentClothesResponse;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPHeaders;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.http.HTTPResponse;
import core.framework.json.JSON;
import core.framework.util.Lists;
import core.framework.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static java.lang.Thread.sleep;

/**
 * @author stephen
 */
public class HuggingfaceWebService {
    private static final String ICLIGHT_V2_WITH_BACKGROUND_API_URL = "https://chancetophugging-iclight.hf.space/gradio_api/call/fn";
    private static final String FLUX_FILLING_API_SELF_HOST_URL = "https://chancetophugging-flux-1-fill-dev.hf.space/gradio_api/call/fn";
    private static final String FLUX_IP_ADAPTER_SELF_HOST_URL = "https://chancetophugging-flux-ip-adapter.hf.space/gradio_api/call/fn";
    private static final String ICLIGHT_V2_API_URL = "https://lllyasviel-iclight-v2.hf.space/call/process";
    private static final String RMBG_2_0_API_URL = "https://briaai-bria-rmbg-2-0.hf.space/call/image";
    private static final String FLUX_INFERENCE_API_URL = "https://api-inference.huggingface.co/models/black-forest-labs/FLUX.1-dev";
    private static final String SEGFORMER_CLOTHES_INFERENCE_API_URL = "https://api-inference.huggingface.co/models/mattmdjaga/segformer_b2_clothes";
    private final Logger logger = LoggerFactory.getLogger(HuggingfaceWebService.class);
    private final HTTPClient client;
    private final String token;

    public HuggingfaceWebService(HTTPClient client, String token) {
        this.client = client;
        this.token = token;
    }

    public byte[] flux(String text) {
        var req = new FluxInferenceRequest();
        req.inputs = text;
        return executeRaw(FLUX_INFERENCE_API_URL, JSON.toJSON(req)).body;
    }

    HuggingfaceResponse fluxIpAdapter(FluxIpAdapterRequest request) {
        return execute(FLUX_IP_ADAPTER_SELF_HOST_URL, toListObject(request));
    }

    HuggingfaceResponse relightingWithBackground(RelightingWithBackgroundRequest request) {
        return execute(ICLIGHT_V2_WITH_BACKGROUND_API_URL, toListObject(request));
    }

    HuggingfaceResponse relighting(RelightingImageRequest request) {
        return execute(ICLIGHT_V2_API_URL, toListObject(request));
    }

    @SuppressWarnings("unchecked")
    public String segformerClothes(String url) throws InterruptedException {
        HTTPResponse apiRsp;
        try {
            apiRsp = executeRaw(SEGFORMER_CLOTHES_INFERENCE_API_URL, url);
        } catch (Exception e) {
            // retry 1 times because of huggingface api hot load strategy
            sleep(1000);
            apiRsp = executeRaw(SEGFORMER_CLOTHES_INFERENCE_API_URL, url);
        }
        var l = JSON.fromJSON(List.class, apiRsp.text());
        var rsp = new SegmentClothesResponse();
        rsp.masks = Lists.newArrayList();
        l.forEach(v -> {
            var j = JSON.toJSON(v);
            rsp.masks.add(JSON.fromJSON(SegmentClothesResponse.Mask.class, j));
        });
        return rsp.masks.stream().filter(v -> "Upper-clothes".equals(v.label)).findFirst().orElseThrow().mask;
    }

    String getRelightingWithBackgroundResult(String eventId) {
        return getGradioResult(ICLIGHT_V2_WITH_BACKGROUND_API_URL, eventId);
    }

    String getRelightingResult(String eventId) {
        return getGradioResult(ICLIGHT_V2_API_URL, eventId);
    }

    HuggingfaceResponse removeImageBackground(RemoveImageBackgroundRequest request) {
        return execute(RMBG_2_0_API_URL, toListObject(request));
    }

    String getRmbgResult(String eventId) {
        return getGradioResult(RMBG_2_0_API_URL, eventId);
    }

    HuggingfaceResponse fluxFilling(FillingImageRequest request) {
        return execute(FLUX_FILLING_API_SELF_HOST_URL, toListObject(request));
    }

    String getFillingResult(String eventId) {
        return getGradioResult(FLUX_FILLING_API_SELF_HOST_URL, eventId);
    }

    String getFluxIpAdapterResult(String eventId) {
        return getGradioResult(FLUX_IP_ADAPTER_SELF_HOST_URL, eventId);
    }

    private List<Object> toListObject(FluxIpAdapterRequest request) {
        List<Object> list = Lists.newArrayList();
        list.add(request.path);
        list.add(request.prompt);
        return list;
    }

    private List<Object> toListObject(RelightingWithBackgroundRequest request) {
        List<Object> list = Lists.newArrayList();
        list.add(request.fg);
        list.add(request.bg);
        list.add(request.prompt);
        list.add(request.imageWidth);
        list.add(request.imageHeight);
        list.add(request.numSamples);
        list.add(request.seed);
        list.add(request.steps);
        list.add(request.aPrompt);
        list.add(request.nPrompt);
        list.add(request.cfg);
        list.add(request.highresScale);
        list.add(request.highresDenoise);
        list.add(request.bgSource);
        return list;
    }

    private List<Object> toListObject(RemoveImageBackgroundRequest request) {
        List<Object> list = Lists.newArrayList();
        list.add(request.path);
        return list;
    }

    private List<Object> toListObject(RelightingImageRequest request) {
        List<Object> list = Lists.newArrayList();
        list.add(request.path);
        list.add(request.bgSource);
        list.add(request.prompt);
        list.add(request.imageWidth);
        list.add(request.imageHeight);
        list.add(request.numSamples);
        list.add(request.seed);
        list.add(request.steps);
        list.add(request.nPrompt);
        list.add(request.cfg);
        list.add(request.gs);
        list.add(request.rs);
        list.add(request.initDenoise);
        return list;
    }

    private List<Object> toListObject(FillingImageRequest request) {
        List<Object> list = Lists.newArrayList();
        list.add(request.bg);
        list.add(request.mask);
        list.add(request.prompt);
        return list;
    }

    private String getGradioResult(String uri, String eventId) {
        var request = new HTTPRequest(HTTPMethod.GET, uri + "/" + eventId);
        return client.execute(request).text();
    }

    private HuggingfaceResponse execute(String uri, List<Object> objects) {
        var body = Strings.format("{\"data\": [{}]}", objects.stream().map(Object::toString).collect(Collectors.joining(",")));
        return execute(uri, body);
    }

    private HuggingfaceResponse execute(String uri, String body) {
        var response = executeRaw(uri, body);
        var rsp = JSON.fromJSON(HuggingfaceResponse.class, response.text());
        logger.info(rsp.eventId);
        return rsp;
    }

    private HTTPResponse executeRaw(String uri, String body) {
        var method = HTTPMethod.POST;
        var request = new HTTPRequest(method, uri);
        request.headers.put(HTTPHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString());
        request.headers.put(HTTPHeaders.USER_AGENT, "curl");
        request.headers.put("Connection", "close");
        request.headers.put("Authorization", "Bearer " + token);
        request.body(body.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);
        return client.execute(request);
    }
}
