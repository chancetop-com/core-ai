package ai.core.example.naixt.api;

import ai.core.example.api.ChatResponse;
import ai.core.example.api.NaixtWebService;
import ai.core.example.api.naixt.NaixtChatRequest;
import ai.core.example.naixt.service.NaixtService;
import core.framework.inject.Inject;

/**
 * @author stephen
 */
public class NaixtWebServiceImpl implements NaixtWebService {
    @Inject
    NaixtService naixtService;

    @Override
    public ChatResponse chat(NaixtChatRequest request) {
        return naixtService.chat(request);
    }
}
