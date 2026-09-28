import { apiClient } from './client';

export interface AppUsageWindow { days: number[]; from: string; to: string }
export interface AppUsageRule {
  id?: number; packageName: string; dailyLimitMinutes?: number; warningMinutes: number;
  action: 'suspend'; enabled: boolean; allowedWindows: AppUsageWindow[];
}
export interface AppUsagePolicy { timezone: string; rules: AppUsageRule[] }
export interface AppUsageReport {
  deviceNumber: string; packageName: string; usageDate: string; foregroundMs: number;
  limitReachedAt?: number; status?: string;
}

export const getAppUsagePolicy = (configurationId: number) =>
  apiClient.get<AppUsagePolicy>(`/private/app-usage/configuration/${configurationId}`);
export const saveAppUsagePolicy = (configurationId: number, policy: AppUsagePolicy) =>
  apiClient.put<AppUsagePolicy>(`/private/app-usage/configuration/${configurationId}`, policy);
export const getAppUsageReport = (configurationId: number, from: string, to: string) =>
  apiClient.get<AppUsageReport[]>(`/private/app-usage/report?configurationId=${configurationId}&from=${from}&to=${to}`);
export const grantAppUsage = (deviceNumber: string, packageName: string, extraMinutes: number, expiresAt?: number) =>
  apiClient.post(`/private/app-usage/device/${encodeURIComponent(deviceNumber)}/override`, { packageName, extraMinutes, expiresAt });
