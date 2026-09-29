import { apiClient } from './client';

export interface ChartItem {
  stringAttr?: string;
  intAttr?: number;
  number: number;
}

export interface FleetSummary {
  statusSummary: ChartItem[];
  devicesTotal: number;
  topConfigs: string[];
  statusOfflineByConfig: number[];
  statusIdleByConfig: number[];
  statusOnlineByConfig: number[];
}

export function getFleetSummary(signal?: AbortSignal): Promise<FleetSummary> {
  return apiClient.get<FleetSummary>('/private/summary/devices', signal);
}
