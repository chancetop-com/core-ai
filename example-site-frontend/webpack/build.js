/* eslint-disable */
const chalk = require("chalk");
const childProcess = require("child_process");
const webpack = require("webpack");
const fs = require("fs-extra");
const env = require("./env");
const webpackConfig = require("./webpack.config.build");

function spawn(command, arguments, errorMessage) {
    const isWindows = process.platform === "win32"; // spawn with {shell: true} can solve .cmd resolving, but prettier doesn't run correctly on mac/linux
    const result = childProcess.spawnSync(isWindows ? command + ".cmd" : command, arguments, {stdio: "inherit"});
    if (result.error) {
        console.error(result.error);
        process.exit(1);
    }
    if (result.status !== 0) {
        console.error(chalk`{red.bold ${errorMessage}}`);
        console.error(`non-zero exit code returned, code=${result.status}, command=${command} ${arguments.join(" ")}`);
        process.exit(1);
    }
}

function checkCodeStyle() {
    console.info(chalk`{green.bold [task]} {white.bold check code style}`);
    return spawn("prettier", ["--cache", "--config", "webpack/prettier.json", "--list-different", "{src,test}/**/*.{ts,tsx,less}"], "check code style failed, please format above files");
}

function checkLint() {
    console.info(chalk`{green.bold [task]} {white.bold Check Lint}`);
    spawn("eslint", ["./src", "--cache"]);
}

function test() {
    console.info(chalk`{green.bold [task]} {white.bold test}`);
    return spawn("jest", ["--config", "webpack/jest.json"], "test failed, please fix");
}

function cleanup() {
    console.info(chalk`{green.bold [task]} {white.bold cleanup build/dist}`);
    fs.emptyDirSync(env.dist);
}

function copyStatic() {
    console.info(chalk`{green.bold [task]} {white.bold copy static folder to dist}`);
    fs.copySync(env.static, env.dist, {dereference: true});
    setTimeout(() => {
        const path  = env.env ? env.conf : env.static
        fs.copySync(`${path}/favicon.ico`, `${env.dist}/favicon.ico`, {
            dereference: true,
            overwrite: true,
        });
    }, 0);
}

function build() {
    if (!env.env) {
        checkCodeStyle();

        checkLint();
    }
    // test();

    console.info(chalk`{white.bold [env]} webpackJSON=${env.webpackJSON === null ? null : JSON.stringify(env.webpackJSON)}`);
    console.info(chalk`{white.bold [env]} conf=${env.conf}`);

    cleanup();
    copyStatic();

    console.info(chalk`{green.bold [task]} {white.bold webpack}`);
    const compiler = webpack(webpackConfig);
    compiler.run((error, stats) => {
        if (error) {
            console.error(error.stack || error);
            if (error.details) console.error(error.details);
            process.exit(1);
        } else {
            console.log(stats.toString({chunks: false, colors: true}));
            if (env.profile) {
                console.info(chalk`{green.bold [task]} write stats.json`);
                fs.writeFileSync("stats.json", JSON.stringify(stats.toJson({}), null, 2));
            }
            // if (stats.hasErrors() || stats.hasWarnings()) {
            if (stats.hasErrors()) {
                process.exit(1);
            }
        }
    });
}

build();
