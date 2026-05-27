/**
 * 推播成效統計面板：成功率、平均嘗試次數、發送速率、錯誤分布
 *
 * <p>顯示策略依 apiMode：
 * <ul>
 *   <li>PUSH：用 per-user 計數（送達 / 失敗 / 處理中人數），後端 BroadcastStatisticsService
 *       已切換為以 task.successCount / failedCount 為主</li>
 *   <li>MULTICAST：LINE 不提供 per-user 結果，整個面板隱藏改顯示 Alert，
 *       引導去 Dashboard 看當日累計</li>
 * </ul>
 */
import { Alert, Card, Col, Empty, Row, Statistic, Tooltip as AntTooltip } from 'antd'
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { BroadcastStatistics, BroadcastTask } from '../../types'

interface Props {
  stats: BroadcastStatistics | null
  task?: BroadcastTask | null
  loading?: boolean
}

export default function StatisticsPanel({ stats, task, loading }: Props) {
  if (task?.apiMode === 'MULTICAST') {
    return (
      <Card title="成效統計" size="small" loading={loading}>
        <Alert
          type="info"
          showIcon
          message="Multicast 模式無 per-user 統計"
          description={
            <span>
              LINE 對 multicast 不提供 per-user delivery 結果，因此送達人數、成功率、發送速率等
              以人為單位的指標都無法精準呈現。
              <br />
              實際送達請參考 <b>Dashboard「今日 LINE multicast 累計送達」</b>
              （LINE 統計通常隔天才會 READY，含同日所有 multicast 任務）。
            </span>
          }
        />
      </Card>
    )
  }

  if (!stats) {
    return (
      <Card size="small" loading={loading}>
        <Empty description="尚無統計資料" />
      </Card>
    )
  }

  const inFlightUsers = task
    ? Math.max(0, (task.totalRecipients ?? 0) - (task.sentCount ?? 0))
    : 0

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
          <Statistic
            title="送達人數"
            value={stats.deliveredRecipients}
            valueStyle={{ color: '#52c41a' }}
          />
        </Col>
        <Col span={6}>
          <Statistic
            title="失敗人數"
            value={task?.failedCount ?? 0}
            valueStyle={{ color: '#ff4d4f' }}
          />
        </Col>
        <Col span={6}>
          <Statistic title="處理中人數" value={inFlightUsers} />
        </Col>
        <Col span={6}>
          <AntTooltip
            title={
              <div style={{ fontSize: 12, lineHeight: 1.6 }}>
                後端把任務內所有收件人按 <b>500 人/批</b> 切分（考慮 LINE multicast 上限 +
                worker 平行化），每批由獨立 worker 處理。本欄為「尚未到終態」的批次數，
                包含：
                <ul style={{ paddingLeft: 16, margin: 4 }}>
                  <li><b>PENDING</b>：已建立、等 worker 撈</li>
                  <li><b>SENDING</b>：worker 正在打 LINE API</li>
                  <li><b>RETRYING</b>：上次失敗（非 4xx），等下次 backoff 重試</li>
                </ul>
                小推播（&lt; 500 人）非 0 即 1；&gt; 500 人才會看到較多批次。
                任務完成後應為 0。
              </div>
            }
          >
            <span style={{ cursor: 'help' }}>
              <Statistic
                title="進行中批次"
                value={stats.retryingChunks + stats.pendingChunks}
              />
            </span>
          </AntTooltip>
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
