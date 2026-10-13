# Desktop builds

```sh
npm ci
npm test
npm run check
npm run stage:engine
npm run dist
```

## macOS previews

The Mac package targets Apple Silicon. Without `CSC_NAME` or `CSC_LINK`, the build
uses an ad-hoc signature with Hardened Runtime enabled. Signing is required so
the packaged app does not retain Electron's invalid pre-packaging signature.

Run `npm run verify:mac` after packaging. It checks the app and bundled engine
signatures, starts the Electron runtime, and checks the engine version. To check
an installed or mounted copy, pass its app path:

```sh
npm run verify:mac -- /Applications/core-ai-desktop.app
```

Ad-hoc signing does not establish an Apple developer identity or notarize the
app. Downloads remain previews and may require approval in System Settings >
Privacy & Security > Open Anyway. See [Apple's instructions](https://support.apple.com/102445).

For distribution without that exception, configure a Developer ID Application
certificate through electron-builder's `CSC_LINK` / `CSC_KEY_PASSWORD` or
`CSC_NAME`, supply Apple notarization credentials, and verify the notarized
download on a clean Mac before making the release stable. An explicitly
configured certificate that cannot be used fails the build instead of silently
producing an unsigned package.
