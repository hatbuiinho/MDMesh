import { apiClient } from './client';
export interface AppInstallRequest {
  id: number; deviceNumber: string; deviceName?: string; configurationId: number;
  packageName: string; appName: string; developer?: string; reason: string;
  status: string; decisionBy?: string; decisionNote?: string; createdAt: number; decidedAt?: number; installedAt?: number;
}
export interface AppInstallAudit { status: string; actor: string; note?: string; createdat: number }
export const listAppRequests = (page: number) => apiClient.get<AppInstallRequest[]>(`/private/app-requests?page=${page}`);
export const decideAppRequest = (id: number, action: 'approve' | 'reject' | 'revoke', note: string) => apiClient.post<AppInstallRequest>(`/private/app-requests/${id}/decision`, { action, note });
export const appRequestHistory = (id: number) => apiClient.get<AppInstallAudit[]>(`/private/app-requests/${id}/history`);
