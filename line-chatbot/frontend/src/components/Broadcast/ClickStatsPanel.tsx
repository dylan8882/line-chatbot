/**
 * 推播點擊統計面板（Phase 7）
 */
import { Card, Col, Empty, Row, Statistic, Table, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { ClickLinkStat, ClickStatistics } from '../../types'

const { Text } = Typography

interface Props {
  stats: ClickStatistics | null
  loading?: boolean
}

export default function ClickStatsPanel({ stats, loading }: Props) {
  if (!stats || stats.links.length === 0) {
    return (
      <Card title="點擊追蹤" size="small" loading={loading}>
        <Empty description="本任務沒有可追蹤的連結（訊息內無 button URL）" />
      </Card>
    )
  }

  const columns: ColumnsType<ClickLinkStat> = [
    { title: '#', dataIndex: 'linkIndex', width: 60 },
    {
      title: '目標 URL',
      dataIndex: 'targetUrl',
      ellipsis: true,
      render: (v: string) => (
        <a href={v} target="_blank" rel="noreferrer">
          {v}
        </a>
      ),
    },
    {
      title: '點擊次數',
      dataIndex: 'clickCount',
      width: 120,
      align: 'right',
      sorter: (a, b) => a.clickCount - b.clickCount,
      defaultSortOrder: 'descend',
    },
  ]

  return (
    <Card title="點擊追蹤" size="small" loading={loading}>
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Statistic title="總點擊次數" value={stats.totalClicks} />
        </Col>
        <Col span={6}>
          <Statistic title="不重複 IP" value={stats.uniqueIps} />
        </Col>
        <Col span={6}>
          <Statistic title="送達人數" value={stats.deliveredRecipients} />
        </Col>
        <Col span={6}>
          <Statistic
            title="點擊率 (CTR)"
            value={stats.ctr * 100}
            precision={2}
            suffix="%"
            valueStyle={{ color: stats.ctr >= 0.05 ? '#52c41a' : '#fa8c16' }}
          />
        </Col>
      </Row>

      <Text type="secondary" style={{ fontSize: 12 }}>
        點擊追蹤不到字串裡的 URL；只追蹤 LINE 訊息結構化的 button / action.uri。
      </Text>

      <Table
        rowKey="linkId"
        columns={columns}
        dataSource={stats.links}
        pagination={false}
        size="small"
        style={{ marginTop: 12 }}
      />
    </Card>
  )
}
