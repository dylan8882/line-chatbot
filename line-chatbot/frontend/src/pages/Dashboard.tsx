/**
 * Dashboard 頁面
 * 顯示今日統計摘要（訊息數、QA 命中率、AI 回覆數、平均延遲）
 * 以及近 7 天訊息量趨勢折線圖
 */
import { useEffect, useState } from 'react'
import { Row, Col, Card, Statistic, Alert, Spin, Typography } from 'antd'
import {
  MessageOutlined,
  CheckCircleOutlined,
  RobotOutlined,
  ClockCircleOutlined,
} from '@ant-design/icons'
import { getStats } from '../api/dashboard'
import UsageChart from '../components/Charts/UsageChart'
import type { UsageStats } from '../types'

const { Title } = Typography

export default function Dashboard() {
  const [stats, setStats] = useState<UsageStats | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const fetchStats = async () => {
      try {
        const res = await getStats()
        setStats(res.data.data)
      } catch (err: unknown) {
        setError(err instanceof Error ? err.message : '載入統計資料失敗')
      } finally {
        setLoading(false)
      }
    }
    fetchStats()
  }, [])

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: 80 }}>
        <Spin size="large" />
      </div>
    )
  }

  if (error) {
    return <Alert message={error} type="error" showIcon />
  }

  return (
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>
        今日統計
      </Title>

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}>
          <Card>
            <Statistic
              title="收到訊息數"
              value={stats?.totalMessages ?? 0}
              prefix={<MessageOutlined style={{ color: '#1890ff' }} />}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card>
            <Statistic
              title="QA 命中率"
              value={((stats?.qaHitRate ?? 0) * 100).toFixed(1)}
              suffix="%"
              prefix={<CheckCircleOutlined style={{ color: '#52c41a' }} />}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card>
            <Statistic
              title="AI 回覆數"
              value={stats?.aiReplies ?? 0}
              prefix={<RobotOutlined style={{ color: '#fa8c16' }} />}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card>
            <Statistic
              title="平均回應時間"
              value={stats?.avgLatencyMs ?? 0}
              suffix="ms"
              prefix={<ClockCircleOutlined style={{ color: '#722ed1' }} />}
            />
          </Card>
        </Col>
      </Row>

      <Card style={{ marginTop: 24 }}>
        <Title level={5} style={{ marginBottom: 16 }}>
          近 7 天訊息量趨勢
        </Title>
        <UsageChart data={stats?.dailyStats ?? []} />
      </Card>
    </div>
  )
}
