import { useCallback, useEffect, useRef, useState } from 'react';
import {
  searchDevices,
  type DeviceSearchRequest,
  type DeviceView,
  type ConfigurationLookup,
} from '../api/devices';
import { ApiError } from '../api/client';

export interface DevicesState {
  devices: DeviceView[];
  total: number;
  configurations: Record<string, ConfigurationLookup>;
  loading: boolean;
  refreshing: boolean;
  error: string | null;
  lastUpdated: number | null;
  reload: () => Promise<void>;
}

function messageFor(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.httpStatus === 401 || err.httpStatus === 403)
      return 'Your session has expired. Sign in again.';
    if (err.httpStatus === 0) return 'Cannot reach the server.';
  }
  return 'Failed to load devices.';
}

export function useDevices(
  request: DeviceSearchRequest = {},
): DevicesState {
  const [devices, setDevices] = useState<DeviceView[]>([]);
  const [total, setTotal] = useState(0);
  const [configurations, setConfigurations] = useState<
    Record<string, ConfigurationLookup>
  >({});
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastUpdated, setLastUpdated] = useState<number | null>(null);
  const activeController = useRef<AbortController | null>(null);
  const requestSequence = useRef(0);
  const requestKey = JSON.stringify(request);

  const reload = useCallback(
    async () => {
      activeController.current?.abort();
      const controller = new AbortController();
      activeController.current = controller;
      const sequence = ++requestSequence.current;
      setLoading((current) => {
        if (!current) setRefreshing(true);
        return current;
      });
      setError(null);
      try {
        const res = await searchDevices(JSON.parse(requestKey) as DeviceSearchRequest, controller.signal);
        if (sequence !== requestSequence.current) return;
        setDevices(res.devices?.items ?? []);
        setTotal(res.devices?.totalItemsCount ?? 0);
        setConfigurations(res.configurations ?? {});
        setLastUpdated(Date.now());
      } catch (err) {
        if (controller.signal.aborted || sequence !== requestSequence.current) return;
        setError(messageFor(err));
      } finally {
        if (sequence === requestSequence.current) {
          setLoading(false);
          setRefreshing(false);
        }
      }
    },
    [requestKey],
  );

  useEffect(() => {
    activeController.current?.abort();
    const controller = new AbortController();
    activeController.current = controller;
    const sequence = ++requestSequence.current;
    setError(null);
    if (devices.length === 0) setLoading(true);
    else setRefreshing(true);
    void searchDevices(JSON.parse(requestKey) as DeviceSearchRequest, controller.signal)
      .then((res) => {
        if (sequence !== requestSequence.current) return;
        setDevices(res.devices?.items ?? []);
        setTotal(res.devices?.totalItemsCount ?? 0);
        setConfigurations(res.configurations ?? {});
        setLastUpdated(Date.now());
      })
      .catch((err: unknown) => {
        if (controller.signal.aborted || sequence !== requestSequence.current) return;
        setError(messageFor(err));
      })
      .finally(() => {
        if (!controller.signal.aborted && sequence === requestSequence.current) {
          setLoading(false);
          setRefreshing(false);
        }
      });
    return () => controller.abort();
  // requestKey is the stable serialized request; devices is intentionally excluded.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [requestKey]);

  return { devices, total, configurations, loading, refreshing, error, lastUpdated, reload };
}
