/**
 * Dashboard 頁面
 * 顯示今日統計摘要（訊息數、QA 命中率、AI 回覆數、平均延遲）
 * 以及近 7 天訊息量趨勢折線圖
 */
import { useEffect, useState } from 'react'
import { Row, Col, Card, Statistic, Alert, Spin, Tag, Tooltip, Typography } from 'antd'
import {
  MessageOutlined,
  CheckCircleOutlined,
  RobotOutlined,
  ClockCircleOutlined,
  SendOutlined,
} from '@ant-design/icons'
import { getStats } from '../api/dashboard'
import { getMulticastDailyDelivery, type MulticastDailyDelivery } from '../api/broadcasts'
import UsageChart from '../components/Charts/UsageChart'
import type { UsageStats } from '../types'

const { Title } = Typography

const MULTICAST_STATUS_TAG: Record<string, { color: string; label: string }> = {
  READY: { color: 'success', label: '已 READY' },
  UNREADY: { color: 'warning', label: '尚未 READY' },
  UNAVAILABLE_FOR_PRIVACY: { color: 'default', label: '隱私限制' },
  OUT_OF_SERVICE: { color: 'error', label: '服務未開通' },
  UNDEFINED: { color: 'default', label: '未定義' },
  ERROR: { color: 'error', label: 'API 失敗' },
}

export default function Dashboard() {
  const [stats, setStats] = useState<UsageStats | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [multicast, setMulticast] = useState<MulticastDailyDelivery | null>(null)

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

  useEffect(() => {
    // multicast 區塊獨立載入：失敗也不影響主要 stats 顯示
    getMulticastDailyDelivery()
      .then((res) => setMulticast(res.data.data))
      .catch(() => setMulticast(null))
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

      <Card style={{ marginTop: 24 }}>
        <Row align="middle" gutter={16}>
          <Col xs={24} md={12}>
            <Tooltip
              title={
                <div style={{ fontSize: 12, lineHeight: 1.6 }}>
                  LINE 平台對 multicast 發送的「當日累計送達數」統計：
                  <ul style={{ paddingLeft: 16, margin: 4 }}>
                    <li>通常隔天才會 READY（最久 ~24h 延遲）</li>
                    <li>同日所有 multicast 任務共用此數字</li>
                    <li>含本系統外的其他 multicast 呼叫</li>
                    <li>push 模式發送不會計入此數</li>
                  </ul>
                </div>
              }
            >
              <span style={{ cursor: 'help' }}>
                <Statistic
                  title="今日 LINE multicast 累計送達"
                  value={multicast?.total ?? '—'}
                  prefix={<SendOutlined style={{ color: '#13c2c2' }} />}
                />
              </span>
            </Tooltip>
          </Col>
          <Col xs={24} md={12}>
            <div style={{ fontSize: 13, lineHeight: 1.8, color: '#666' }}>
              <div>
                狀態：
                {multicast ? (
                  <Tag color={MULTICAST_STATUS_TAG[multicast.status]?.color ?? 'default'}>
                    {MULTICAST_STATUS_TAG[multicast.status]?.label ?? multicast.status}
                  </Tag>
                ) : (
                  '載入中…'
                )}
              </div>
              {multicast?.asOf && (
                <div>
                  資料時間：{new Date(multicast.asOf).toLocaleString('zh-TW')}
                  {multicast.fromCache && (
                    <Tag color="blue" style={{ marginLeft: 8 }}>
                      cache
                    </Tag>
                  )}
                </div>
              )}
              {multicast?.status === 'UNREADY' && (
                <div style={{ marginTop: 4 }}>
                  LINE 統計需到次日才會 READY，今日資料明天才能看到。
                </div>
              )}
            </div>
          </Col>
        </Row>
      </Card>
    </div>
  )
}
