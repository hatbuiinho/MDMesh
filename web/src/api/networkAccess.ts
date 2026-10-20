import { apiClient } from './client';
export interface NetworkRule { id: string; name: string; mode: 'BLOCKLIST' | 'ALLOWLIST'; scope: 'ALL' | 'SELECTED'; packages: string[]; domains: string[] }
export interface NetworkRequest { effectiveStatus: string; supported: boolean; applied: boolean; id: number; deviceNumber: string; deviceName: string; configurationId: number; policyHash: string; packageName: string; domain: string; reason: string; status: string; decisionBy?: string; decisionNote?: string; grantPackages?: string; expiresAt?: number | null; createdAt: number; decidedAt?: number }
export interface NetworkDecision { action: 'approve' | 'reject' | 'revoke'; duration?: string; timezone?: string; scope?: string; ruleId?: string; note?: string }
export const getNetworkRules = (id: number) => apiClient.get<NetworkRule[]>(`/private/network-access/configuration/${id}`);
export const saveNetworkRules = (id: number, rules: NetworkRule[]) => apiClient.put<NetworkRule[]>(`/private/network-access/configuration/${id}`, rules);
export const listNetworkRequests = (page = 1) => apiClient.get<NetworkRequest[]>(`/private/network-access/requests?page=${page}`);
export const decideNetworkRequest = (id: number, body: NetworkDecision) => apiClient.post<NetworkRequest>(`/private/network-access/requests/${id}/decision`, body);

export interface NetworkAudit { status: string; actor: string; note: string; createdat: number; expiresat?: number; grantpackages?: string }
export const networkRequestHistory = (id: number) => apiClient.get<NetworkAudit[]>(`/private/network-access/requests/${id}/history`);
