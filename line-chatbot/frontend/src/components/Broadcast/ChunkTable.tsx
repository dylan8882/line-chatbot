/**
 * 推播批次清單表格
 *
 * 把舊版「失敗清單」與「批次」兩塊合併成單一可篩選的清單。
 * 支援狀態 Segmented 篩選（ALL / SUCCESS / PARTIAL / FAILED）+ 分頁（5 筆/頁）。
 */
import { useMemo, useState } from 'react'
import { Card, Segmented, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { BroadcastChunkStatus, BroadcastChunkSummary } from '../../types'

interface Props {
  chunks: BroadcastChunkSummary[]
  loading?: boolean
}

const STATUS_COLOR: Record<BroadcastChunkStatus, string> = {
  PENDING: 'default',
  SENDING: 'processing',
  SUCCESS: 'success',
  PARTIAL: 'warning',
  FAILED: 'error',
  RETRYING: 'warning',
  CANCELLED: 'default',
}

type FilterValue = 'ALL' | 'SUCCESS' | 'PARTIAL' | 'FAILED'

/** Segmented 篩選選項，文字配色對齊清單裡 Tag 的色票 */
const FILTER_OPTIONS = [
  { label: <span>全部</span>, value: 'ALL' as const },
  {
    label: <span style={{ color: '#52c41a', fontWeight: 600 }}>SUCCESS</span>,
    value: 'SUCCESS' as const,
  },
  {
    label: <span style={{ color: '#faad14', fontWeight: 600 }}>PARTIAL</span>,
    value: 'PARTIAL' as const,
  },
  {
    label: <span style={{ color: '#ff4d4f', fontWeight: 600 }}>FAILED</span>,
    value: 'FAILED' as const,
  },
]

export default function ChunkTable({ chunks, loading }: Props) {
  const [filter, setFilter] = useState<FilterValue>('ALL')

  const filtered = useMemo(() => {
    if (filter === 'ALL') return chunks
    return chunks.filter((c) => c.status === filter)
  }, [chunks, filter])

  const columns: ColumnsType<BroadcastChunkSummary> = [
    { title: '批次 #', dataIndex: 'chunkIndex', width: 80 },
    { title: '收件人數', dataIndex: 'recipientCount', width: 100, align: 'right' },
    {
      title: '狀態',
      dataIndex: 'status',
      width: 110,
      render: (s: BroadcastChunkStatus) => <Tag color={STATUS_COLOR[s]}>{s}</Tag>,
    },
    { title: '嘗試次數', dataIndex: 'attempts', width: 100, align: 'right' },
    { title: '錯誤碼', dataIndex: 'errorCode', width: 160 },
    { title: '錯誤訊息', dataIndex: 'errorMessage', ellipsis: true },
    {
      title: '發送時間',
      dataIndex: 'sentAt',
      width: 180,
      render: (v) => (v ? new Date(v).toLocaleString('zh-TW') : '—'),
    },
  ]

  return (
    <Card
      size="small"
      title={`批次清單（${filtered.length}${filter === 'ALL' ? '' : ` / 共 ${chunks.length}`}）`}
      extra={
        <Segmented
          size="small"
          options={FILTER_OPTIONS}
          value={filter}
          onChange={(v) => setFilter(v as FilterValue)}
        />
      }
    >
      <Table
        rowKey="id"
        columns={columns}
        dataSource={filtered}
        loading={loading}
        size="small"
        pagination={{ pageSize: 5, showSizeChanger: false }}
      />
    </Card>
  )
}
