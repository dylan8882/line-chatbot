/**
 * 應用程式根元件
 * 設定 React Router、路由保護與全域 Layout
 */
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { ConfigProvider } from 'antd'
import zhTW from 'antd/locale/zh_TW'
import useAuthStore from './store/authStore'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import QAManagement from './pages/QAManagement'
import UsageMonitor from './pages/UsageMonitor'
import LineSettings from './pages/LineSettings'
import TagManagement from './pages/TagManagement'
import LineUsers from './pages/LineUsers'
import Layout from './components/Layout/Layout'

/** 路由保護：未登入自動導向 /login */
function PrivateRoute({ children }: { children: React.ReactNode }) {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  return isAuthenticated ? <>{children}</> : <Navigate to="/login" replace />
}

export default function App() {
  return (
    <ConfigProvider locale={zhTW}>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route
            path="/"
            element={
              <PrivateRoute>
                <Layout />
              </PrivateRoute>
            }
          >
            <Route index element={<Navigate to="/dashboard" replace />} />
            <Route path="dashboard" element={<Dashboard />} />
            <Route path="qa" element={<QAManagement />} />
            <Route path="usage" element={<UsageMonitor />} />
            <Route path="line-settings" element={<LineSettings />} />
            <Route path="line-users" element={<LineUsers />} />
            <Route path="tags" element={<TagManagement />} />
          </Route>
          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </BrowserRouter>
    </ConfigProvider>
  )
}
