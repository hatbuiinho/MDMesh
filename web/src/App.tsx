import { lazy, Suspense } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './auth/AuthContext';
import { ProtectedRoute } from './auth/ProtectedRoute';
import { ThemeProvider } from './ui/theme';
import { ToastProvider } from './ui/toast';

const LoginPage = lazy(() => import('./pages/LoginPage').then((m) => ({ default: m.LoginPage })));
const SetPasswordPage = lazy(() => import('./pages/SetPasswordPage').then((m) => ({ default: m.SetPasswordPage })));
const DashboardPage = lazy(() => import('./pages/DashboardPage').then((m) => ({ default: m.DashboardPage })));
const DevicesPage = lazy(() => import('./pages/DevicesPage').then((m) => ({ default: m.DevicesPage })));
const DeviceDetailPage = lazy(() => import('./pages/DeviceDetailPage').then((m) => ({ default: m.DeviceDetailPage })));
const AppsPage = lazy(() => import('./pages/AppsPage').then((m) => ({ default: m.AppsPage })));
const ConfigurationsPage = lazy(() => import('./pages/ConfigurationsPage').then((m) => ({ default: m.ConfigurationsPage })));
const EnrollPage = lazy(() => import('./pages/EnrollPage').then((m) => ({ default: m.EnrollPage })));
const SettingsPage = lazy(() => import('./pages/SettingsPage').then((m) => ({ default: m.SettingsPage })));

export default function App() {
  return (
    <AuthProvider>
      <ThemeProvider>
      <ToastProvider>
        <BrowserRouter>
          <Suspense fallback={<div className="route-loading"><span className="spin" /> Loading…</div>}>
            <Routes>
              <Route path="/login" element={<LoginPage />} />
              <Route path="/set-password" element={<SetPasswordPage />} />
              <Route element={<ProtectedRoute />}>
                <Route path="/dashboard" element={<DashboardPage />} />
                <Route path="/devices" element={<DevicesPage />} />
                <Route path="/devices/:id" element={<DeviceDetailPage />} />
                <Route path="/apps" element={<AppsPage />} />
                <Route path="/configs" element={<ConfigurationsPage />} />
                <Route path="/enroll" element={<EnrollPage />} />
                <Route path="/settings" element={<SettingsPage />} />
              </Route>
              <Route path="*" element={<Navigate to="/dashboard" replace />} />
            </Routes>
          </Suspense>
        </BrowserRouter>
      </ToastProvider>
      </ThemeProvider>
    </AuthProvider>
  );
}
