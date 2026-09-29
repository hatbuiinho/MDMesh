import { apiClient } from './client';

export interface DeviceEvent {
  id: number;
  type: string;
  ts: number;
  detail?: string | null;
}

export async function getEvents(
  deviceId: number | string,
  since = 0,
  limit = 50,
  before?: number,
  signal?: AbortSignal,
): Promise<DeviceEvent[]> {
  const beforeParam = before == null ? '' : `&before=${before}`;
  return apiClient.get<DeviceEvent[]>(
    `/private/agent/v1/devices/${deviceId}/events?since=${since}&limit=${limit}${beforeParam}`,
    signal,
  );
}
