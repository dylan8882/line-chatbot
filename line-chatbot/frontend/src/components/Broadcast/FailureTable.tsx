/**
 * 推播失敗 / 重試中 chunk 清單
 */
import { Card, Empty, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { BroadcastChunkStatus, BroadcastFailure } from '../../types'

interface Props {
  failures: BroadcastFailure[]
  loading?: boolean
}

const STATUS_COLOR: Record<BroadcastChunkStatus, string> = {
  PENDING: 'default',
  SENDING: 'processing',
  SUCCESS: 'success',
  FAILED: 'error',
  RETRYING: 'warning',
  CANCELLED: 'default',
}

export default function FailureTable({ failures, loading }: Props) {
  if (!loading && failures.length === 0) {
    return (
      <Card title="失敗清單" size="small">
        <Empty description="目前沒有失敗或重試中的 chunk" />
      </Card>
    )
  }

  const columns: ColumnsType<BroadcastFailure> = [
    { title: 'Chunk #', dataIndex: 'chunkIndex', width: 80 },
    { title: '收件人數', dataIndex: 'recipientCount', width: 90, align: 'right' },
    {
      title: '狀態',
      dataIndex: 'status',
      width: 100,
      render: (s: BroadcastChunkStatus) => <Tag color={STATUS_COLOR[s]}>{s}</Tag>,
    },
    { title: '嘗試次數', dataIndex: 'attempts', width: 90, align: 'right' },
    { title: '錯誤碼', dataIndex: 'errorCode', width: 160 },
    { title: '錯誤訊息', dataIndex: 'errorMessage', ellipsis: true },
    {
      title: '上次嘗試',
      dataIndex: 'lastAttemptAt',
      width: 170,
      render: (v) => (v ? new Date(v).toLocaleString('zh-TW') : '—'),
    },
    {
      title: '下次重試',
      dataIndex: 'nextRetryAt',
      width: 170,
      render: (v) => (v ? new Date(v).toLocaleString('zh-TW') : '—'),
    },
  ]

  return (
    <Card title={`失敗清單（${failures.length}）`} size="small">
      <Table
        rowKey="chunkId"
        columns={columns}
        dataSource={failures}
        loading={loading}
        size="small"
        pagination={{ pageSize: 10 }}
      />
    </Card>
  )
}
