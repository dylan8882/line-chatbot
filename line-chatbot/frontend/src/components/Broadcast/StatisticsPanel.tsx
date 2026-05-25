/**
 * 推播成效統計面板：成功率、平均嘗試次數、發送速率、錯誤分布
 */
import { Card, Col, Empty, Row, Statistic } from 'antd'
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { BroadcastStatistics } from '../../types'

interface Props {
  stats: BroadcastStatistics | null
  loading?: boolean
}

export default function StatisticsPanel({ stats, loading }: Props) {
  if (!stats) {
    return (
      <Card size="small" loading={loading}>
        <Empty description="尚無統計資料" />
      </Card>
    )
  }

  return (
    <Card title="成效統計" size="small" loading={loading}>
      <Row gutter={[16, 16]}>
        <Col span={6}>
          <Statistic
            title="成功率"
            value={stats.successRate * 100}
            precision={1}
            suffix="%"
            valueStyle={{ color: stats.successRate >= 0.95 ? '#52c41a' : '#fa8c16' }}
          />
        </Col>
        <Col span={6}>
          <Statistic title="平均嘗試次數" value={stats.avgAttempts} precision={2} />
        </Col>
        <Col span={6}>
          <Statistic
            title="發送速率"
            value={stats.sendRatePerSecond}
            precision={1}
            suffix="人/秒"
          />
        </Col>
        <Col span={6}>
          <Statistic
            title="耗時"
            value={stats.durationMs == null ? '—' : formatDuration(stats.durationMs)}
          />
        </Col>

        <Col span={6}>
          <Statistic title="送達人數" value={stats.deliveredRecipients} />
        </Col>
        <Col span={6}>
          <Statistic title="成功 chunk" value={stats.successChunks} valueStyle={{ color: '#52c41a' }} />
        </Col>
        <Col span={6}>
          <Statistic title="失敗 chunk" value={stats.failedChunks} valueStyle={{ color: '#ff4d4f' }} />
        </Col>
        <Col span={6}>
          <Statistic title="重試 / 進行中" value={stats.retryingChunks + stats.pendingChunks} />
        </Col>
      </Row>

      {stats.errorBreakdown.length > 0 && (
        <div style={{ marginTop: 24 }}>
          <h4>錯誤分布</h4>
          <ResponsiveContainer width="100%" height={200}>
            <BarChart data={stats.errorBreakdown}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="errorCode" />
              <YAxis allowDecimals={false} />
              <Tooltip />
              <Bar dataKey="count" fill="#ff4d4f" />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
    </Card>
  )
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms} ms`
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s} 秒`
  const m = Math.floor(s / 60)
  const ss = s % 60
  return `${m} 分 ${ss} 秒`
}
