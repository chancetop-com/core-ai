package ai.core.server.apimcp.serviceapi.service;

import ai.core.server.apimcp.serviceapi.domain.ServiceApi;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface ServiceApiStore {
    List<ServiceApi> listAll();

    Optional<ServiceApi> get(String id);

    Optional<ServiceApi> getByName(String name);

    void insert(ServiceApi api);

    void replace(ServiceApi api);

    void delete(String id);
}
