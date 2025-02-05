/* eslint-disable */
const webpack = require("webpack");
const env = require("./env");
const autoprefixer = require("autoprefixer");
const MiniCSSExtractPlugin = require("mini-css-extract-plugin");
const ForkTSCheckerPlugin = require("fork-ts-checker-webpack-plugin");
const HTMLPlugin = require("html-webpack-plugin");
const StylelintPlugin = require("stylelint-webpack-plugin");
const BundleAnalyzerPlugin = require("webpack-bundle-analyzer").BundleAnalyzerPlugin;
const yargs = require("yargs");
const path = require("path");
const {ESBuildMinifyPlugin} = require("esbuild-loader");

const {analyze, env: environment} = yargs.argv;

const varLessConfig = require("../varLessConfig");

const config = {
    mode: "production",
    entry: `${env.src}/index.tsx`,
    output: {
        path: env.dist,
        filename: "static/js/[name].[chunkhash:8].js",
        publicPath: env.webpackJSON === null ? "/" : env.webpackJSON.publicPath,
    },
    resolve: {
        extensions: [".ts", ".tsx", ".js", ".jsx", ".less"],
        modules: [env.src, "node_modules"],
        alias: {
            conf: env.conf,
            lib: env.lib,
        },
    },
    devtool: false,
    bail: true,
    cache: {
        type: "filesystem",
    },
    optimization: {
        moduleIds: "named",
        runtimeChunk: "single",
        splitChunks: {
            automaticNameDelimiter: "-",
            maxAsyncRequests: 10,
            cacheGroups: {
                vendor: {
                    test: /[\\/]node_modules[\\/]/,
                    name: "vendors",
                    chunks: "all",
                },
            },
        },
        minimizer: [
            new ESBuildMinifyPlugin({
                target: "es2015", // Syntax to compile to (see options below for possible values)
                css: true,
                legalComments: "none",
                sourcemap: "external",
            }),
        ],
    },
    performance: {
        maxEntrypointSize: 1000000,
        maxAssetSize: 1000000,
    },
    module: {
        rules: [
            {
                test: /\.(ts|tsx)$/,
                include: env.src,
                loader: "esbuild-loader",
                options: {
                    loader: "tsx", // Or 'ts' if you don't need tsx
                    target: "es2015",
                    tsconfigRaw: require(env.tsConfig),
                },
            },
            {
                test: /\.(css|less)$/,
                use: [
                    MiniCSSExtractPlugin.loader,
                    {
                        loader: "css-loader",
                        options: {
                            sourceMap: true,
                            importLoaders: 2,
                            modules: {
                                mode: "global",
                                localIdentName: "[name]__[local]--[hash:base64:5]",
                                localIdentContext: path.resolve(__dirname, "src"),
                            },
                        },
                    },
                    {
                        loader: "postcss-loader",
                        options: {
                            sourceMap: false,
                            postcssOptions: {
                                plugins: [autoprefixer],
                            },
                        },
                    },
                    {
                        loader: "less-loader",
                        options: {
                            sourceMap: true,
                            lessOptions: {
                                javascriptEnabled: true,
                                modifyVars: varLessConfig,
                            },
                        },
                    },
                ],
            },
            {
                test: /\.(png|jpe?g|gif)$/,
                type: "asset/resource",
                generator: {
                    filename: "static/img/[name].[hash:8].[ext]",
                },
            },
            {
                test: /\.(woff|woff2|eot|ttf|otf)$/,
                type: "asset/resource",
                generator: {
                    filename: "static/font/[name].[hash:8].[ext]",
                },
            },
            {
                test: /\.ico$/,
                type: "asset/resource",
                generator: {
                    filename: "static/ico/[name].[hash:8].ico",
                },
            },
        ],
        noParse: /jsonlint-lines/,
    },
    plugins: [
        new MiniCSSExtractPlugin({
            filename: "static/css/[name].[contenthash:8].css",
            ignoreOrder: true,
        }),
        ...(env.env
            ? []
            : [
                  new ForkTSCheckerPlugin({
                      typescript: {
                          configFile: env.tsConfig,
                          mode: "write-references",
                      },
                  }),
                  new StylelintPlugin({
                      configFile: env.stylelintConfig,
                      context: env.src,
                      files: "**/*.less",
                      customSyntax: "postcss-less",
                  }),
              ]),

        new HTMLPlugin({
            template: `${env.src}/index.html`,
            minify: {
                collapseBooleanAttributes: true,
                collapseInlineTagWhitespace: true,
                collapseWhitespace: true,
                includeAutoGeneratedTags: false,
                keepClosingSlash: true,
                minifyCSS: true,
                minifyJS: true,
                minifyURLs: true,
                removeAttributeQuotes: true,
                removeComments: true,
                removeEmptyAttributes: true,
                removeRedundantAttributes: true,
                removeScriptTypeAttributes: true,
                removeStyleLinkTypeAttributes: true,
                removeTagWhitespace: true,
                useShortDoctype: true,
            },
        }),
        new webpack.ProgressPlugin({profile: true}),
        new webpack.IgnorePlugin({
            resourceRegExp: /^\.\/locale$/,
            contextRegExp: /moment$/,
        }),
        analyze && new BundleAnalyzerPlugin(),
    ].filter(it => it),
};

module.exports = config;
