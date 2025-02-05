import {defineConfig} from "vite";
import react from "@vitejs/plugin-react";
import StyleLintPlugin from "vite-plugin-stylelint";
import path, {resolve} from "path";
import fs from "fs";
import styleImport from "vite-plugin-style-import";
import checker from "vite-plugin-checker";
import basicSSL from "@vitejs/plugin-basic-ssl";
import cors from "cors";

import varLessConfig from "./varLessConfig";

// https://vitejs.dev/config/
const proxyContext = ["/image-uploader", "/excel", "/csv", "/ajax", "/file-uploader", "/image"].reduce((prev, next) => {
    prev[next] = {
        target: "http://localhost:8080", //kitchen-simulator.foodtruck-qa.com
        changeOrigin: true,
        secure: false,
    };
    return prev;
}, {});

const moduleRoot = "./src/";
const srcModules = fs.readdirSync(moduleRoot);

const paths = srcModules.reduce((prev, next) => {
    const currentPath = `${moduleRoot}${next}`;
    if (fs.statSync(currentPath).isDirectory()) {
        prev[next] = path.join(__dirname, currentPath + "/");
    }
    return prev;
}, {});

export default defineConfig({
    plugins: [
        react(),
        checker({
            typescript: true,
            eslint: {
                lintCommand: "eslint ./src/**/*.{ts,tsx} --cache",
            },
        }),
        StyleLintPlugin({fix: true}),
        styleImport({
            libs: [
                {
                    libraryName: "antd",
                    // resolveStyle: name => `antd/es/${name}/style`,
                },
            ],
        }),
        basicSSL(),
    ],
    esbuild: {
        // https://github.com/vitejs/vite/issues/8644#issuecomment-1159308803
        logOverride: {"this-is-undefined-in-esm": "silent"},
        tsconfigRaw: require("./tsconfig.json"),
    },
    build: {
        rollupOptions: {
            output: {
                // 这个时候会把所有的模块都区分开
                // manualChunks(id) {
                //     if (id.includes("node_modules")) {
                //         return id.toString().split("node_modules/")[1].split("/")[0].toString();
                //     }
                // },
                // 类似webpack自己组装
                manualChunks: {
                    lodash: ["lodash"],
                    antd: ["antd"],
                },
            },
        },
    },
    css: {
        preprocessorOptions: {
            less: {
                // 支持内联 JavaScript
                javascriptEnabled: true,
                modifyVars: varLessConfig,
            },
        },
    },
    resolve: {
        alias: {
            ...paths,
            conf: resolve(__dirname, "conf/dev"),
        },
    },
    
    server: {
        host: "localhost",
        port: 6443,
        https: true,
        proxy: proxyContext,
    },
});
