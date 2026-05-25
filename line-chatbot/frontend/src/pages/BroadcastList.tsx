/**
 * 推播任務列表
 * - 顯示任務狀態、進度、收件人數
 * - 點選列檢視詳情；新增按鈕跳到建立頁
 */
import { useCallback, useEffect, useState } from 'react'
import { Button, Progress, Select, Space, Table, Tag, Typography, message } from 'antd'
import { ExperimentOutlined, PlusOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import type { ColumnsType, TablePaginationConfig } from 'antd/es/table'
import { getBroadcasts } from '../api/broadcasts'
import usePermissions from '../hooks/usePermissions'
import type { BroadcastStatus, BroadcastTask } from '../types'

const { Title } = Typography

const PAGE_SIZE = 20

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

const STATUS_LABEL: Record<BroadcastStatus, string> = {
  DRAFT: '草稿',
  QUEUED: '排隊中',
  SCHEDULED: '已排程',
  RUNNING: '執行中',
  PAUSED: '已暫停',
  COMPLETED: '已完成',
  FAILED: '失敗',
  CANCELLED: '已取消',
}

export default function BroadcastList() {
  const navigate = useNavigate()
  const { canCreateBroadcast } = usePermissions()
  const [data, setData] = useState<BroadcastTask[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [statusFilter, setStatusFilter] = useState<BroadcastStatus | undefined>()

  const fetchData = useCallback(async () => {
    setLoading(true)
    try {
      const res = await getBroadcasts({
        status: statusFilter,
        page: page - 1,
        size: PAGE_SIZE,
      })
      const p = res.data.data
      setData(p.content)
      setTotal(p.totalElements)
    } catch (err: unknown) {
      message.error(err instanceof Error ? err.message : '載入任務失敗')
    } finally {
      setLoading(false)
    }
  }, [statusFilter, page])

  useEffect(() => {
    fetchData()
  }, [fetchData])

  const columns: ColumnsType<BroadcastTask> = [
    {
      title: '任務名稱',
      dataIndex: 'name',
      render: (v, record) => (
        <a onClick={() => navigate(`/broadcasts/${record.id}`)}>{v}</a>
      ),
    },
    {
      title: '狀態',
      dataIndex: 'status',
      width: 110,
      render: (s: BroadcastStatus) => <Tag color={STATUS_COLOR[s]}>{STATUS_LABEL[s]}</Tag>,
    },
    {
      title: '目標',
      dataIndex: 'targetType',
      width: 100,
    },
    {
      title: '進度',
      width: 220,
      render: (_, r) => {
        if (r.totalRecipients === 0) return '—'
        const percent = Math.round((r.sentCount / r.totalRecipients) * 100)
        return (
          <Progress
            percent={percent}
            size="small"
            status={r.status === 'FAILED' ? 'exception' : r.status === 'COMPLETED' ? 'success' : 'active'}
          />
        )
      },
    },
    {
      title: '收件人',
      dataIndex: 'totalRecipients',
      width: 100,
      align: 'right',
    },
    {
      title: '成功 / 失敗',
      width: 120,
      align: 'right',
      render: (_, r) => `${r.successCount} / ${r.failedCount}`,
    },
    {
      title: '建立時間',
      dataIndex: 'createdAt',
      width: 180,
      render: (v) => new Date(v).toLocaleString('zh-TW'),
    },
  ]

  const pagination: TablePaginationConfig = {
    current: page,
    pageSize: PAGE_SIZE,
    total,
    showSizeChanger: false,
    onChange: setPage,
  }

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>推播管理</Title>
        {canCreateBroadcast && (
          <Space>
            <Button icon={<ExperimentOutlined />} onClick={() => navigate('/broadcasts/ab-test/new')}>
              新增 A/B 測試
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/broadcasts/new')}>
              新增推播
            </Button>
          </Space>
        )}
      </div>

      <Space style={{ marginBottom: 16 }}>
        <Select
          placeholder="依狀態篩選"
          allowClear
          style={{ width: 180 }}
          value={statusFilter}
          onChange={(v) => { setStatusFilter(v); setPage(1) }}
          options={Object.entries(STATUS_LABEL).map(([k, v]) => ({ value: k, label: v }))}
        />
        <Button onClick={fetchData}>重新整理</Button>
      </Space>

      <Table
        rowKey="id"
        columns={columns}
        dataSource={data}
        loading={loading}
        pagination={pagination}
      />
    </div>
  )
}
