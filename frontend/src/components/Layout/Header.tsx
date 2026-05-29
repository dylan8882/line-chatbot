/**
 * 頂部 Header：顯示用戶名稱與登出按鈕
 */
import { Layout, Button, Space, Typography, message } from 'antd'
import { LogoutOutlined, UserOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import useAuthStore from '../../store/authStore'
import { logout } from '../../api/auth'

const { Header: AntHeader } = Layout
const { Text } = Typography

export default function Header() {
  const navigate = useNavigate()
  const { username, clearAuth } = useAuthStore()

  const handleLogout = async () => {
    try {
      await logout()
    } catch {
      // 即使 API 失敗也繼續清除本地狀態
    } finally {
      clearAuth()
      navigate('/login')
      message.success('已登出')
    }
  }

  return (
    <AntHeader
      style={{
        background: '#fff',
        padding: '0 24px',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'flex-end',
        boxShadow: '0 1px 4px rgba(0,0,0,0.1)',
      }}
    >
      <Space>
        <UserOutlined />
        <Text>{username}</Text>
        <Button
          type="text"
          icon={<LogoutOutlined />}
          onClick={handleLogout}
          danger
        >
          登出
        </Button>
      </Space>
    </AntHeader>
  )
}
