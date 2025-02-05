package ai.core.huggingface;

import ai.core.huggingface.flux.FillingImageRequest;
import ai.core.huggingface.flux.FillingImageResponse;
import ai.core.huggingface.flux.FluxIpAdapterRequest;
import ai.core.huggingface.flux.FluxIpAdapterResponse;
import ai.core.huggingface.iclight.RelightingImageRequest;
import ai.core.huggingface.iclight.RelightingImageResponse;
import ai.core.huggingface.iclight.RelightingWithBackgroundRequest;
import ai.core.huggingface.iclight.RelightingWithBackgroundResponse;
import ai.core.huggingface.rmbg.RemoveImageBackgroundRequest;
import ai.core.huggingface.rmbg.RemoveImageBackgroundResponse;
import core.framework.inject.Inject;
import core.framework.util.Strings;

import static java.lang.Thread.sleep;

/**
 * @author stephen
 */
public class HuggingfaceService {
    @Inject
    HuggingfaceWebService huggingfaceWebService;

    public byte[] flux(String text) {
        return huggingfaceWebService.flux(text);
    }

    public String seg(String url) {
        try {
            return huggingfaceWebService.segformerClothes(url);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public FluxIpAdapterResponse fluxIpAdapter(FluxIpAdapterRequest request) {
        var apiRsp = huggingfaceWebService.fluxIpAdapter(request);
        try {
            sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (apiRsp.eventId == null) throw new RuntimeException("flux ip adapter failed");
        var rst = huggingfaceWebService.getFluxIpAdapterResult(apiRsp.eventId);
        var fmtRst = toRst(apiRsp.eventId, rst);
        var rsp = new FluxIpAdapterResponse();
        rsp.url = getUrl(fmtRst);
        return rsp;
    }

    public RelightingImageResponse relighting(RelightingImageRequest request) {
        var apiRsp = huggingfaceWebService.relighting(request);
        try {
            sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (apiRsp.eventId == null) throw new RuntimeException("relighting failed");
        var rst = huggingfaceWebService.getRelightingResult(apiRsp.eventId);
        var fmtRst = toRst(apiRsp.eventId, rst);
        var rsp = new RelightingImageResponse();
        rsp.url = getUrl(fmtRst);
        return rsp;
    }

    public RelightingWithBackgroundResponse relightingWithBackground(RelightingWithBackgroundRequest request) {
        var apiRsp = huggingfaceWebService.relightingWithBackground(request);
        try {
            sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (apiRsp.eventId == null) throw new RuntimeException("relighting failed");
        var rst = huggingfaceWebService.getRelightingWithBackgroundResult(apiRsp.eventId);
        var fmtRst = toRst(apiRsp.eventId, rst);
        var rsp = new RelightingWithBackgroundResponse();
        rsp.url = getUrl(fmtRst);
        return rsp;
    }

    public RemoveImageBackgroundResponse removeImageBackground(RemoveImageBackgroundRequest request) {
        var apiRsp = huggingfaceWebService.removeImageBackground(request);
        try {
            sleep(500);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        var rsp = new RemoveImageBackgroundResponse();
        var rst = huggingfaceWebService.getRmbgResult(apiRsp.eventId);
        rsp.url = toRst(apiRsp.eventId, rst);
        return rsp;
    }

    public FillingImageResponse fillingImage(FillingImageRequest request) {
        var apiRsp = huggingfaceWebService.fluxFilling(request);
        try {
            sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (apiRsp.eventId == null) {
            throw new RuntimeException("event_id is null");
        }
        var rst = huggingfaceWebService.getFillingResult(apiRsp.eventId);
        var fmtRst = toRst(apiRsp.eventId, rst);
        var rsp = new FillingImageResponse();
        rsp.url = getUrl(fmtRst);
        return rsp;
    }

    private String getUrl(String fmtRst) {
        return fmtRst.substring(fmtRst.indexOf("\"url\"") + "\"url\": \"".length(), fmtRst.indexOf("\"size\"") - 3)
                .replaceAll("hf.sp/", "hf.space/")
                .replaceAll("/c/file", "/file");
    }

    private String toRst(String eventId, String rst) {
        var completeText = "event: complete";
        if (rst.contains(completeText)) {
            var data = rst.substring(rst.indexOf("event: complete") + completeText.length()).split("\n")[1];
            data = "\"data\"" + data.substring("data".length());
            return Strings.format("{{}}", data);
        }
        throw new RuntimeException(Strings.format("event_id: {}, error: {}", eventId, rst));
    }
}
