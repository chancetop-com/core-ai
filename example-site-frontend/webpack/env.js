/* eslint-disable */
const path = require("path");
const fs = require("fs");
const yargs = require("yargs");

// use "npm run build -- --env dev" or "yarn run build --env dev" to pass
const env = yargs.argv.env || null;
const profile = yargs.argv.profile || false;

function resolve(relativePath) {
    return path.resolve(__dirname, `../${relativePath}`);
}

function webpackJSON() {
    if (env === null) return null;
    const path = resolve(`conf/${env}/webpack.json`);
    if (!fs.existsSync(path)) return null;

    return JSON.parse(fs.readFileSync(path));
}

module.exports = {
    dist: resolve("build/dist"),
    src: resolve("src"),
    static: resolve("static"),
    conf: env == null ? resolve("src/conf") : resolve(`conf/${env}`),
    lib: resolve("lib"),
    tsConfig: resolve("webpack/tsconfig.json"),
    stylelintConfig: resolve("webpack/stylelint.json"),
    webpackJSON: webpackJSON(),
    profile: profile,
    env,
};
