const loaders: Record<string, () => Promise<unknown>> = {
  '/dashboard': () => import('../pages/DashboardPage'),
  '/devices': () => import('../pages/DevicesPage'),
  '/configs': () => import('../pages/ConfigurationsPage'),
  '/apps': () => import('../pages/AppsPage'),
  '/enroll': () => import('../pages/EnrollPage'),
  '/settings': () => import('../pages/SettingsPage'),
};

export function preloadRoute(path: string): void {
  void loaders[path]?.();
}
