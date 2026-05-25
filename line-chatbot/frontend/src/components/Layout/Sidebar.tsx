/**
 * 左側導覽選單
 */
import { useState } from 'react'
import { Layout, Menu } from 'antd'
import {
  DashboardOutlined,
  MessageOutlined,
  BarChartOutlined,
  ApiOutlined,
  UserOutlined,
  TagsOutlined,
  NotificationOutlined,
  FileTextOutlined,
} from '@ant-design/icons'
import { useNavigate, useLocation } from 'react-router-dom'

const { Sider } = Layout

const menuItems = [
  {
    key: '/dashboard',
    icon: <DashboardOutlined />,
    label: 'Dashboard',
  },
  {
    key: '/qa',
    icon: <MessageOutlined />,
    label: '問答管理',
  },
  {
    key: '/usage',
    icon: <BarChartOutlined />,
    label: '用量監控',
  },
  {
    key: '/line-users',
    icon: <UserOutlined />,
    label: 'LINE 用戶',
  },
  {
    key: '/tags',
    icon: <TagsOutlined />,
    label: '標籤管理',
  },
  {
    key: '/templates',
    icon: <FileTextOutlined />,
    label: '訊息模板',
  },
  {
    key: '/broadcasts',
    icon: <NotificationOutlined />,
    label: '推播管理',
  },
  {
    key: '/line-settings',
    icon: <ApiOutlined />,
    label: 'LINE 串接設定',
  },
]

export default function Sidebar() {
  const [collapsed, setCollapsed] = useState(false)
  const navigate = useNavigate()
  const location = useLocation()

  return (
    <Sider
      collapsible
      collapsed={collapsed}
      onCollapse={setCollapsed}
      style={{ background: '#001529' }}
    >
      <div
        style={{
          height: 48,
          margin: '16px',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          color: '#fff',
          fontWeight: 'bold',
          fontSize: collapsed ? 14 : 16,
          whiteSpace: 'nowrap',
          overflow: 'hidden',
        }}
      >
        {collapsed ? 'LC' : 'LINE Chatbot'}
      </div>
      <Menu
        theme="dark"
        mode="inline"
        selectedKeys={[location.pathname]}
        items={menuItems}
        onClick={({ key }) => navigate(key)}
      />
    </Sider>
  )
}
