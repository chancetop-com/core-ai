import {bootstrap} from "@wonder/core-fe";
import {ErrorHandler} from "./page/ErrorHandler";
import Main from "./page/main/component/index";
import config from "conf/config";
import "typeface-heebo";

bootstrap({
    componentType: Main,
    errorListener: new ErrorHandler(),
    loggerConfig: {
        serverURL: config.logServerURL,
        maskedKeywords: [/^cvc$/, /^cardNumber$/, /^expiration_year$/, /^expiration_month$/, /^expirationDate$/, /[Pp]assword/],
    },
});
