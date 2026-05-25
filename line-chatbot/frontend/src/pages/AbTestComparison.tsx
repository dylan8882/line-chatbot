/**
 * A/B 測試比較頁：列出該組所有 variant 的成效並排比較
 */
import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Empty, Progress, Space, Statistic, Table, Tag, Typography, message } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import type { ColumnsType } from 'antd/es/table'
import { getAbTestComparison } from '../api/broadcasts'
import type { AbTestComparison, AbTestVariantStat, BroadcastStatus } from '../types'

const { Title } = Typography

const STATUS_COLOR: Record<BroadcastStatus, string> = {
  DRAFT: 'default',
  QUEUED: 'cyan',
  SCHEDULED: 'blue',
  RUNNING: 'processing',
  PAUSED: 'orange',
  COMPLETED: 'success',
  FAILED: 'error',
  CANCELLED: 'default',
}

export default function AbTestComparisonPage() {
  const { abTestId } = useParams<{ abTestId: string }>()
  const navigate = useNavigate()
  const [data, setData] = useState<AbTestComparison | null>(null)
  const [loading, setLoading] = useState(false)

  const fetchData = useCallback(async () => {
    if (!abTestId) return
    setLoading(true)
    try {
      const res = await getAbTestComparison(abTestId)
      setData(res.data.data)
    } catch (err: unknown) {
      message.error(err instanceof Error ? err.message : '載入失敗')
    } finally {
      setLoading(false)
    }
  }, [abTestId])

  useEffect(() => {
    fetchData()
  }, [fetchData])

  if (!data) {
    return <Empty description={loading ? '載入中…' : '沒有資料'} />
  }

  const best = data.variants.reduce(
    (acc, v) => (v.successRate > acc.successRate ? v : acc),
    data.variants[0],
  )

  const columns: ColumnsType<AbTestVariantStat> = [
    {
      title: 'Variant',
      dataIndex: 'label',
      width: 100,
      render: (v: string, record) =>
        record.taskId === best.taskId ? (
          <span>
            <Tag color="gold">{v} 👑</Tag>
          </span>
        ) : (
          <Tag>{v}</Tag>
        ),
    },
    {
      title: '狀態',
      dataIndex: 'status',
      width: 110,
      render: (s: BroadcastStatus) => <Tag color={STATUS_COLOR[s]}>{s}</Tag>,
    },
    {
      title: '收件人',
      dataIndex: 'totalRecipients',
      width: 100,
      align: 'right',
    },
    {
      title: '進度',
      width: 220,
      render: (_, r) => {
        if (r.totalRecipients === 0) return '—'
        const percent = Math.round((r.sentCount / r.totalRecipients) * 100)
        return <Progress percent={percent} size="small" />
      },
    },
    { title: '成功', dataIndex: 'successCount', width: 100, align: 'right' },
    { title: '失敗', dataIndex: 'failedCount', width: 100, align: 'right' },
    {
      title: '成功率',
      dataIndex: 'successRate',
      width: 100,
      align: 'right',
      render: (r: number) => `${(r * 100).toFixed(1)}%`,
    },
    {
      title: '操作',
      render: (_, r) => (
        <Button size="small" onClick={() => navigate(`/broadcasts/${r.taskId}`)}>
          檢視
        </Button>
      ),
    },
  ]

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button onClick={() => navigate('/broadcasts')}>← 回列表</Button>
        <Title level={4} style={{ margin: 0 }}>
          A/B 測試：{data.taskName}
        </Title>
        <Button icon={<ReloadOutlined />} onClick={fetchData} loading={loading}>
          重新整理
        </Button>
      </Space>

      <Card title="勝出變體" size="small" style={{ marginBottom: 16 }}>
        <Space size="large">
          <Statistic title="Variant" value={best?.label ?? '—'} />
          <Statistic
            title="成功率"
            value={best ? best.successRate * 100 : 0}
            precision={1}
            suffix="%"
            valueStyle={{ color: '#52c41a' }}
          />
          <Statistic title="送達人數" value={best?.successCount ?? 0} />
        </Space>
      </Card>

      <Card title="各 Variant 詳細" size="small">
        <Table
          rowKey="taskId"
          columns={columns}
          dataSource={data.variants}
          pagination={false}
          loading={loading}
        />
      </Card>
    </div>
  )
}
