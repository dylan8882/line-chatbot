/**
 * 主版面配置：左側 Sidebar + 右側內容區（含 Header）
 */
import { Layout as AntLayout } from 'antd'
import { Outlet } from 'react-router-dom'
import Sidebar from './Sidebar'
import Header from './Header'

const { Content } = AntLayout

export default function Layout() {
  return (
    <AntLayout style={{ minHeight: '100vh' }}>
      <Sidebar />
      <AntLayout>
        <Header />
        <Content style={{ margin: '24px 16px', padding: 24, background: '#fff', borderRadius: 8 }}>
          <Outlet />
        </Content>
      </AntLayout>
    </AntLayout>
  )
}
