package ai.core.server.web;

import core.framework.api.http.HTTPStatus;

/**
 * An error that carries its own HTTP status and optional structured body extension. The global error
 * handler must not flatten these into {errorCode, message} without the details the client needs.
 *
 * @author core-ai
 */
public interface TypedWebError {
    HTTPStatus status();

    default String extraJson() {
        return null;
    }

    default Integer retryAfterSeconds() {
        return null;
    }
}
