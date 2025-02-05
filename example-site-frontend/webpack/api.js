const chalk = require("chalk");
const fs = require("fs-extra");
const path = require("path");
const axios = require("axios");
const yargs = require("yargs");
const childProcess = require("child_process");
const Agent = require("https").Agent;

const apiURL = yargs.argv.url || "http://localhost:8080/_sys/api";
const typeModule = "type";
const typeSourcePath = path.resolve(__dirname, `../src/${typeModule}/api.ts`);
const serviceModule = "service";
const serviceSourceDir = path.resolve(__dirname, `../src/${serviceModule}`);

function generateTypes(types) {
    const builder = [];
    types.forEach(type => {
        if (type.type === "bean") {
            builder.push(`export interface ${type.name} {`);
            type.fields.forEach(field => {
                builder.push(`${field.name}: ${fieldType(field)}`);
                if (!field.constraints.notNull) builder.push(` | null`);
                builder.push(";");
                builder.push(fieldConstraintsComment(field.constraints));
                builder.push("\n");
            });
            builder.push(`}`);
        } else if (type.type === "enum") {
            builder.push(`export enum ${type.name} {`);
            type.enumConstants.forEach(constant => builder.push(`${constant.name} = "${constant.value}",`));
            builder.push(`}`);
        }
        builder.push("\n");
    });
    fs.writeFileSync(typeSourcePath, builder.join(""), "utf8");
    console.info(chalk`{white.bold api type generated}, source=${typeSourcePath}`);
}

function fieldType(field) {
    if (field.type === "List") return `${tsType(field.typeParams[0])}[]`;
    if (field.type === "Map") {
        const builder = [];
        builder.push("{[key");
        if (field.typeParams[0] === "String") {
            builder.push(":string]: ");
        } else {
            // map key can only be string or enum
            builder.push(` in ${field.typeParams[0]}]?: `);
        }
        if (field.typeParams[1] === "List") {
            // map value can only be List<V> or Bean type
            builder.push(`${tsType(field.typeParams[2])}[];}`);
        } else {
            builder.push(`${tsType(field.typeParams[1])};}`);
        }
        return builder.join("");
    }
    return tsType(field.type);
}

function fieldConstraintsComment(constraints) {
    const builder = [];
    if (constraints.notBlank === true) builder.push(`notBlank=true`);
    if (constraints.min !== null) builder.push(`min=${constraints.min}`);
    if (constraints.max !== null) builder.push(`max=${constraints.max}`);
    if (constraints.size !== null) builder.push(`size=(${constraints.size.min}, ${constraints.size.max})`);
    if (constraints.pattern !== null) builder.push(`pattern=${constraints.pattern}`);
    if (builder.length === 0) return "";
    return " // constraints: " + builder.join(", ");
}

function tsType(type) {
    if (type === "String") return "string";
    if (type === "Boolean") return "boolean";
    if (type === "Integer" || type === "Long" || type === "Double" || type === "BigDecimal") return "number";
    if (type === "ZonedDateTime") return "Date";
    if (type === "LocalDate" || type === "LocalDateTime" || type === "LocalTime") return "string"; // in ts/js, Date is always convert to ISO datetime utc format, so here use string for date/datetime without timezone
    return type;
}

function generateService(serviceName, operations) {
    const requiredTypes = [];
    const checkType = type => {
        const baseType = type.replace("[]", "");
        if (!["void", "number", "string"].includes(baseType) && !requiredTypes.includes(baseType)) {
            requiredTypes.push(baseType);
        }
        return type;
    };

    const lines = [];
    lines.push(`import {ajax} from "@wonder/core-fe";`);
    lines.push(``);
    lines.push(`export class ${serviceName} {`);
    operations
        .filter(operation => !operation.deprecated)
        .forEach(operation => {
            const pathParams = "{" + operation.pathParams.map(param => param.name).join(",") + "}";

            let requestBody = "null";
            const parameters = operation.pathParams.slice();
            if (operation.requestType) {
                parameters.push({name: "request", type: operation.requestType});
                requestBody = `request`;
            }
            const parameterSignature = parameters.map(param => param.name + ":" + checkType(tsType(param.type))).join(",");

            lines.push(`static ${operation.name}(${parameterSignature}): Promise<${checkType(operation.responseType) + (operation.optional ? " | null" : "")}>{`);
            lines.push(`return ajax("${operation.method}", "${operation.path}", ${pathParams}, ${requestBody});`);
            lines.push("}");
        });

    lines.push(`}`);
    if (requiredTypes.length > 0) lines.unshift(`import {${requiredTypes.join(",")}} from "${typeModule}/api";`);

    const serviceSourcePath = `${serviceSourceDir}/${serviceName}.ts`;
    fs.writeFileSync(serviceSourcePath, lines.join("\n"), "utf8");
    console.info(chalk`{white.bold api service client generated}, source=${serviceSourcePath}`);
}

function validateAPIResponse(response) {
    const contentType = response.headers["content-type"];
    if (!contentType || !contentType.startsWith("application/json")) throw new Error(`unexpected contentType, contentType=${contentType}`);
    const api = response.data;
    if (!api.types || !api.services) throw new Error(`unexpected api definition, responseText=${JSON.stringify(api)}}`);
    return api;
}

async function fetchAPIDefinition() {
    console.info(chalk`{white.bold fetching API definition}, url={green ${apiURL}}`);
    try {
        const response = await axios.get(apiURL, {httpsAgent: new Agent({rejectUnauthorized: false})});
        return validateAPIResponse(response);
    } catch (error) {
        console.error(chalk`{red.bold failed to fetch api definition, error=${error}}`);
        process.exit(1);
    }
}

function createDirs() {
    fs.emptyDirSync(serviceSourceDir);

    const typeSourceParentDir = path.resolve(typeSourcePath, "../");
    if (!fs.existsSync(typeSourceParentDir)) fs.mkdirSync(typeSourceParentDir);
}

function spawn(command, arguments) {
    const isWindows = process.platform === "win32";
    const result = childProcess.spawnSync(isWindows ? command + ".cmd" : command, arguments, {stdio: "inherit"});
    if (result.error) {
        console.error(result.error);
        process.exit(1);
    }
    if (result.status !== 0) {
        console.error(`non-zero exit code returned, code=${result.status}, command=${command} ${arguments.join(" ")}`);
        process.exit(1);
    }
}

function formatSources() {
    console.info(chalk`{white.bold format generated sources}`);
    spawn("prettier", ["--config", "webpack/prettier.json", "--write", `src/{${serviceModule},${typeModule}}/*.ts`]);
}

async function generate() {
    console.info(chalk`{white.bold usage:} yarn api / yarn api --url https://host/_sys/api`);
    const api = await fetchAPIDefinition();
    createDirs();
    generateTypes(api.types);
    api.services.forEach(service => generateService(service.name, service.operations));
    formatSources();
}

generate();
