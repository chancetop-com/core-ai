// /* eslint-disable */
// const webpack = require("webpack");
// const env = require("./env");
// const HTMLPlugin = require("html-webpack-plugin");
// const StylelintPlugin = require("stylelint-webpack-plugin");
// const ForkTSCheckerPlugin = require("fork-ts-checker-webpack-plugin");
// const TSImportPlugin = require("ts-import-plugin");
// const path = require("path");
// const port = process.argv[2] || "7443";
// const config = {
//     mode: "development",
//     entry: [`webpack-dev-server/client?https://0.0.0.0:${port}`, "webpack/hot/dev-server", `${env.src}/index.tsx`],
//     output: {
//         filename: "static/js/[name].js",
//         publicPath: "/",
//         pathinfo: false,
//     },
//     resolve: {
//         extensions: [".ts", ".tsx", ".js", ".jsx", ".less"],
//         modules: [env.src, "node_modules"],
//         alias: {
//             conf: env.conf,
//             lib: env.lib,
//         },
//     },
//     devtool: "cheap-module-source-map",
//     optimization: {
//         removeAvailableModules: false,
//         removeEmptyChunks: false,
//         splitChunks: false,
//         runtimeChunk: true,
//     },
//     module: {
//         rules: [
//             // {
//             //     test: /\.(ts|tsx)$/,
//             //     include: env.src,
//             //     loader: "ts-loader",
//             //     options: {
//             //         configFile: env.tsConfig,
//             //         transpileOnly: true,
//             //         getCustomTransformers: () => ({
//             //             before: [TSImportPlugin({libraryName: "antd", libraryDirectory: "es", style: true})],
//             //         }),
//             //     },
//             // },
//             {
//                 test: /\.(ts|tsx)$/,
//                 include: env.src,
//                 loader: "esbuild-loader",
//                 options: {
//                     loader: "tsx", // Or 'ts' if you don't need tsx
//                     target: "es2015",
//                     tsconfigRaw: require(env.tsConfig),
//                 },
//             },
//             {
//                 test: /\.(css|less)$/,
//                 use: [
//                     "style-loader",
//                     {
//                         loader: "css-loader",
//                         options: {
//                             // Using `local` value has same effect like using `modules: true`
//                             modules: {
//                                 mode: "global",
//                                 localIdentName: "[name]__[local]--[hash:base64:5]",
//                                 localIdentContext: path.resolve(__dirname, "src"),
//                             },
//                         },
//                     },
//                     {
//                         loader: "less-loader",
//                         options: {
//                             sourceMap: true,
//                             lessOptions: {
//                                 javascriptEnabled: true,
//                                 modifyVars: {
//                                     "primary-color": "#C09D75",
//                                     "link-color": "#116EBE",
//                                 },
//                             },
//                         },
//                     },
//                 ],
//             },
//             {
//                 test: /\.(png|jpe?g|gif)$/,
//                 type: "asset/resource",
//                 generator: {
//                     filename: "static/img/[name].[hash:8].[ext]",
//                 },
//             },
//             {
//                 test: /\.(woff|woff2|eot|ttf|otf)$/,
//                 type: "asset/resource",
//                 generator: {
//                     filename: "static/font/[name].[hash:8].[ext]",
//                 },
//             },
//             {
//                 test: /\.ico$/,
//                 type: "asset/resource",
//                 generator: {
//                     filename: "static/ico/[name].[hash:8].ico",
//                 },
//             },
//         ],
//     },
//     plugins: [
//         new StylelintPlugin({
//             configFile: env.stylelintConfig,
//             context: env.src,
//             files: "**/*.less",
//             customSyntax: "postcss-less",
//         }),
//         new ForkTSCheckerPlugin({
//             typescript: {
//                 configFile: env.tsConfig,
//                 mode: "write-references",
//             },
//         }),
//         new HTMLPlugin({
//             template: `${env.src}/index.html`,
//         }),
//         new webpack.HotModuleReplacementPlugin(),
//         new webpack.ProgressPlugin(),
//         new webpack.IgnorePlugin({
//             resourceRegExp: /^\.\/locale$/,
//             contextRegExp: /moment$/,
//         }),
//     ],
// };

// module.exports = {webpackConfig: config, port};
