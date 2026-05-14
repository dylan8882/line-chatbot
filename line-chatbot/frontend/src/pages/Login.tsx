/**
 * 登入頁面
 * 帳號密碼表單，登入成功後 JWT 存入 localStorage 並導向 Dashboard
 */
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Form, Input, Button, Card, Typography, message, Alert } from 'antd'
import { UserOutlined, LockOutlined } from '@ant-design/icons'
import { useForm, Controller } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { login } from '../api/auth'
import useAuthStore from '../store/authStore'

const { Title } = Typography

const schema = z.object({
  username: z.string().min(1, '請輸入帳號'),
  password: z.string().min(1, '請輸入密碼'),
})

type FormValues = z.infer<typeof schema>

export default function Login() {
  const navigate = useNavigate()
  const setAuth = useAuthStore((s) => s.setAuth)
  const [loading, setLoading] = useState(false)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  const {
    control,
    handleSubmit,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { username: '', password: '' },
  })

  const onSubmit = async (values: FormValues) => {
    setLoading(true)
    setErrorMsg(null)
    try {
      const res = await login(values)
      const { token, username, role } = res.data.data
      setAuth(token, username, role)
      message.success('登入成功')
      navigate('/dashboard')
    } catch (err: unknown) {
      setErrorMsg(err instanceof Error ? err.message : '登入失敗，請確認帳號密碼')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'linear-gradient(135deg, #001529 0%, #003a70 100%)',
      }}
    >
      <Card style={{ width: 380, boxShadow: '0 8px 32px rgba(0,0,0,0.3)' }}>
        <div style={{ textAlign: 'center', marginBottom: 32 }}>
          <Title level={3} style={{ marginBottom: 4 }}>
            LINE Chatbot 管理後台
          </Title>
        </div>

        {errorMsg && (
          <Alert
            message={errorMsg}
            type="error"
            showIcon
            style={{ marginBottom: 16 }}
          />
        )}

        <form onSubmit={handleSubmit(onSubmit)}>
          <Form.Item
            validateStatus={errors.username ? 'error' : ''}
            help={errors.username?.message}
          >
            <Controller
              name="username"
              control={control}
              render={({ field }) => (
                <Input
                  {...field}
                  prefix={<UserOutlined />}
                  placeholder="帳號"
                  size="large"
                />
              )}
            />
          </Form.Item>

          <Form.Item
            validateStatus={errors.password ? 'error' : ''}
            help={errors.password?.message}
          >
            <Controller
              name="password"
              control={control}
              render={({ field }) => (
                <Input.Password
                  {...field}
                  prefix={<LockOutlined />}
                  placeholder="密碼"
                  size="large"
                />
              )}
            />
          </Form.Item>

          <Button
            type="primary"
            htmlType="submit"
            block
            size="large"
            loading={loading}
          >
            登入
          </Button>
        </form>
      </Card>
    </div>
  )
}
