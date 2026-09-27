import { API_BASE, ApiError, apiClient, type ApiEnvelope } from './client';

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
  description?: string | null;
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
export function uploadAgentRelease(file: File, onProgress?: (percent: number) => void): Promise<AgentRelease> {
  const form = new FormData(); form.append('file', file);
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `${API_BASE}/private/agent/v1/releases`);
    xhr.withCredentials = true;
    xhr.setRequestHeader('Accept', 'application/json');
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress?.(Math.min(100, Math.round(event.loaded / event.total * 100)));
    };
    xhr.onerror = () => reject(new ApiError('Network error uploading the APK', 'ERROR', 0));
    xhr.onload = () => {
      let envelope: ApiEnvelope<AgentRelease>;
      try { envelope = JSON.parse(xhr.responseText) as ApiEnvelope<AgentRelease>; }
      catch { reject(new ApiError('Unexpected non-JSON response from server', 'ERROR', xhr.status)); return; }
      if (xhr.status < 200 || xhr.status >= 300 || (envelope.status && envelope.status !== 'OK')) {
        reject(new ApiError(envelope.message ?? `Upload failed with HTTP ${xhr.status}`, envelope.status ?? 'ERROR', xhr.status));
        return;
      }
      resolve(envelope.data);
    };
    xhr.send(form);
  });
}
export const previewRollout = (id: number) => apiClient.get<RolloutDevice[]>(`${BASE}/preview/${id}`);
export const getActiveRollout = () => apiClient.get<ActiveRollout | null>(`${BASE}/active`);
export const createRollout = (req: CreateRolloutRequest) => apiClient.post<ActiveRollout>(BASE, req);
export const promoteRollout = (id: number) => apiClient.post<ActiveRollout>(`${BASE}/${id}/promote`);
export const retryRolloutDevice = (id: number, deviceNumber: string) => apiClient.post<ActiveRollout>(`${BASE}/${id}/retry`, { deviceNumber });
export const cancelRollout = (id: number) => apiClient.post<void>(`${BASE}/${id}/cancel`);
export const finishRollout = (id: number) => apiClient.post<void>(`${BASE}/${id}/finish`);
