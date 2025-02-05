import {message} from "antd";
import {ErrorListener, Exception, APIException, SagaGenerator} from "@wonder/core-fe";

export class ErrorHandler implements ErrorListener {
    *onError(exception: Exception): SagaGenerator {
        if (exception instanceof APIException) {
            if (exception.statusCode === 401) {
                return exception;
            } else if (exception.statusCode >= 500) {
                message.error("Server error");
                return;
            }
        }
        message.error(exception.message, 5);
    }
}
