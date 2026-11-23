// Every custom scheme the app serves, in the ONE list that may be registered as privileged.
// Electron builds each privilege list (fetch-schemes, secure-schemes, cors-schemes, …) from the most
// recent protocol.registerSchemesAsPrivileged() call, so a scheme declared only in an earlier call
// drops out of every category the later call declares. Registering the plugin schemes after the
// artifact scheme removed coreai-artifact from --fetch-schemes: every fetch to it failed with
// "Failed to fetch" before any handler ran, while images and iframes still loaded.
export interface SchemePrivileges {
  standard: true;
  secure: true;
  supportFetchAPI: true;
  stream: true;
  corsEnabled?: true;
}

export interface PrivilegedScheme {
  scheme: string;
  privileges: SchemePrivileges;
}

export const ARTIFACT_SCHEME = 'coreai-artifact';
export const PLUGIN_PAGE_SCHEME = 'coreai-plugin-page';
export const PLUGIN_ASSET_SCHEME = 'coreai-plugin-asset';

/** `corsEnabled` only matters where the host renderer (a `file://` page) fetches the scheme itself. */
export const PRIVILEGED_SCHEMES: PrivilegedScheme[] = [
  {
    scheme: ARTIFACT_SCHEME,
    privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true, corsEnabled: true },
  },
  {
    scheme: PLUGIN_PAGE_SCHEME,
    privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true },
  },
  {
    scheme: PLUGIN_ASSET_SCHEME,
    privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true },
  },
];
