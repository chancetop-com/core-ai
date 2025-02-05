// /* eslint-disable */

// const chalk = require("chalk");
// const webpack = require("webpack");
// const env = require("./env");
// const {webpackConfig, port} = require("./webpack.config.dev");
// const DevServer = require("webpack-dev-server");

// function devServer(compiler, ticket) {
//     return new DevServer(
//         {
//             server: "https",
//             historyApiFallback: true,
//             hot: true,
//             host: "0.0.0.0",
//             port: "7443",
//             compress: true,
//             allowedHosts: "all",
//             client: {
//                 overlay: false,
//             },
//             devMiddleware: {
//                 stats: {
//                     colors: true,
//                 },
//             },
//             static: {
//                 directory: env.static,
//             },
//             // public: "test.foodtruck-qa.com",
//             proxy: [
//                 {
//                     context: ["/image-uploader", "/video-uploader", "/recipe-site", "/recipe/excel", "/recipe/pdf", "/restaurant/excel", "/ajax", "/cms/file-uploader", "/file-uploader", "/file-download", "/image", "/simulator/excel", "/excel/vendor-item", "/excel/v2"],
//                     target: "https://hdr-portal.foodtruck-qa.com/",
//                     secure: false,
//                     changeOrigin: true,
//                     // pathRewrite: {"/ajax/current": `/ajax/current?identity_ticket=${ticket}`},
//                 },
//             ],
//         },
//         compiler
//     );
// }

// function start() {
//     console.info(chalk`{white.bold [env]} conf=${env.conf}`);

//     const compiler = webpack(webpackConfig);
//     const server = devServer(compiler);
//     server.listen(port, "0.0.0.0", error => {
//         if (error) {
//             console.error(error);
//             process.exit(1);
//         }
//         console.info(chalk`starting dev server on {green https://localhost:${port}/} \n`);
//         return null;
//     });

//     ["SIGINT", "SIGTERM"].forEach(signal => {
//         process.on(signal, () => {
//             server.close();
//             process.exit();
//         });
//     });
// }

// start();
