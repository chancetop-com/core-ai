/** Shared registry DTOs for the main process and the renderer. */
export interface RegistryPlugin {
  id: string;
  name: string;
  version: string;
  description: string | null;
  vendor: string | null;
  homepage: string | null;
  downloadUrl: string;
  sha256: string;
  size: number | null;
  requiredCapabilities: string[];
  optionalCapabilities: string[];
  components: string[];
  updatedAt: string | null;
}
