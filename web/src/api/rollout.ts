import { apiClient } from './client';

export interface AgentRelease {
  id: number;
  packageName: string;
  versionName: string;
  versionCode: number;
  sha256: string;
  signatureChecksum: string;
  url: string;
  createdAt: number;
}
export type DeviceRolloutStatus = 'updated' | 'pending' | 'waiting' | 'offline' | 'busy' | 'verifying' | 'failed' | 'ineligible' | 'not_started';
export interface RolloutDevice {
  deviceNumber: string;
  agentVersion: string | null;
  agentVersionCode: number | null;
  lastSeen: number | null;
  status: DeviceRolloutStatus;
  cohort?: 'canary' | 'fleet';
  detail?: string | null;
  attempts?: number;
  identityVerified: boolean;
}
export type RolloutCounts = Record<Exclude<DeviceRolloutStatus, 'not_started'> | 'total', number>;
export interface ActiveRollout {
  id: number;
  targetVersion: string;
  packageName: string;
  apkVersionCode: number;
  stage: 'canary' | 'fleet' | 'done' | 'cancelled';
  createdAt: number;
  updatedAt: number;
  progress: { canary: RolloutCounts; fleet: RolloutCounts | null };
  devices: RolloutDevice[];
}
export interface CreateRolloutRequest {
  releaseId: number;
  allDevices: boolean;
  canaryDeviceNumbers: string[];
}
const BASE = '/private/agent/v1/rollout';
export const listAgentReleases = () => apiClient.get<AgentRelease[]>('/private/agent/v1/releases');
export function uploadAgentRelease(file: File): Promise<AgentRelease> {
  const form = new FormData(); form.append('file', file);
  return apiClient.postForm('/private/agent/v1/releases', form);
}
export const previewRollout = (id: number) => apiClient.get<RolloutDevice[]>(`${BASE}/preview/${id}`);
export const getActiveRollout = () => apiClient.get<ActiveRollout | null>(`${BASE}/active`);
export const createRollout = (req: CreateRolloutRequest) => apiClient.post<ActiveRollout>(BASE, req);
export const promoteRollout = (id: number) => apiClient.post<ActiveRollout>(`${BASE}/${id}/promote`);
export const retryRolloutDevice = (id: number, deviceNumber: string) => apiClient.post<ActiveRollout>(`${BASE}/${id}/retry`, { deviceNumber });
export const cancelRollout = (id: number) => apiClient.post<void>(`${BASE}/${id}/cancel`);
export const finishRollout = (id: number) => apiClient.post<void>(`${BASE}/${id}/finish`);
