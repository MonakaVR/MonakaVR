// Desktop-only adapter types. These are not a tracking wire or backend protocol.
export const BRIDGE_POLICIES = ['steamvr', 'monaka', 'both', 'disabled'] as const;
export type BridgePolicy = (typeof BRIDGE_POLICIES)[number];
export const isBridgePolicy = (value: unknown): value is BridgePolicy =>
  BRIDGE_POLICIES.some((policy) => policy === value);

export interface BridgeLocations {
  configPath: string | null;
  validatorPath: string | null;
}
export interface BridgeMappingSummary {
  source: string;
  device: string;
  tracker: string;
  profile: string;
  inputSpace: string;
  inputRevision: number | null;
  approved: boolean | null;
  profileApproved: boolean | null;
}
export interface BridgeConfiguration {
  locations: BridgeLocations;
  token: string | null;
  policy: BridgePolicy | null;
  revision: number | null;
  publisher: string | null;
  mappings: BridgeMappingSummary[];
}
export interface BridgeHealthRead {
  configPath: string | null;
  value: Record<string, unknown> | null;
  error: string | null;
}
export interface BridgeDesktopApi {
  load(requestId: string): Promise<BridgeConfiguration>;
  choose(kind: 'config' | 'validator'): Promise<BridgeConfiguration | null>;
  health(): Promise<BridgeHealthRead>;
  apply(
    token: string,
    policy: BridgePolicy,
    requestId: string
  ): Promise<BridgeConfiguration>;
  cancel(requestId: string): Promise<void>;
}
export type BridgeDesktopRequest =
  | { operation: 'load'; requestId: string }
  | { operation: 'choose'; kind: 'config' | 'validator' }
  | { operation: 'health' }
  | { operation: 'apply'; token: string; policy: BridgePolicy; requestId: string }
  | { operation: 'cancel'; requestId: string };
