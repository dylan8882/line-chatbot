/**
 * 推播任務詳情
 * - 顯示任務狀態、進度、訊息內容
 * - chunk 分片清單
 * - 手動重新整理進度（Phase 4 會改成 SSE 即時推送）
 */
import { useCallback, useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Popconfirm,
  Progress,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import { ReloadOutlined, StopOutlined, PlayCircleOutlined } from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import type { ColumnsType } from 'antd/es/table'
import {
  cancelBroadcast,
  getBroadcastProgress,
  submitBroadcast,
} from '../api/broadcasts'
import type { BroadcastChunkStatus, BroadcastStatus, BroadcastTask } from '../types'

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

const CHUNK_STATUS_COLOR: Record<BroadcastChunkStatus, string> = {
  PENDING: 'default',
  SENDING: 'processing',
  SUCCESS: 'success',
  FAILED: 'error',
  RETRYING: 'warning',
  CANCELLED: 'default',
}

export default function BroadcastDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [task, setTask] = useState<BroadcastTask | null>(null)
  const [loading, setLoading] = useState(false)

  const fetchData = useCallback(async () => {
    if (!id) return
    setLoading(true)
    try {
      const res = await getBroadcastProgress(Number(id))
      setTask(res.data.data)
    } catch (err: unknown) {
      message.error(err instanceof Error ? err.message : '載入失敗')
    } finally {
      setLoading(false)
    }
  }, [id])

  useEffect(() => {
    fetchData()
  }, [fetchData])

  // 執行中時每 3 秒輪詢一次（Phase 4 會改 SSE）
  useEffect(() => {
    if (!task || (task.status !== 'RUNNING' && task.status !== 'QUEUED')) return
    const timer = setInterval(fetchData, 3000)
    return () => clearInterval(timer)
  }, [task, fetchData])

  const handleSubmit = async () => {
    if (!task) return
    try {
      await submitBroadcast(task.id)
      message.success('任務已提交執行')
      fetchData()
    } catch (err: unknown) {
      message.error(err instanceof Error ? err.message : '操作失敗')
    }
  }

  const handleCancel = async () => {
    if (!task) return
    try {
      await cancelBroadcast(task.id)
      message.success('任務已取消')
      fetchData()
    } catch (err: unknown) {
      message.error(err instanceof Error ? err.message : '操作失敗')
    }
  }

  if (!task) {
    return <div>載入中…</div>
  }

  const percent = task.totalRecipients > 0
    ? Math.round((task.sentCount / task.totalRecipients) * 100)
    : 0

  const chunkColumns: ColumnsType<NonNullable<BroadcastTask['chunks']>[number]> = [
    { title: '#', dataIndex: 'chunkIndex', width: 60 },
    { title: '收件人數', dataIndex: 'recipientCount', width: 100, align: 'right' },
    {
      title: '狀態',
      dataIndex: 'status',
      width: 100,
      render: (s: BroadcastChunkStatus) => <Tag color={CHUNK_STATUS_COLOR[s]}>{s}</Tag>,
    },
    { title: '嘗試次數', dataIndex: 'attempts', width: 90, align: 'right' },
    { title: '錯誤碼', dataIndex: 'errorCode', width: 140 },
    { title: '錯誤訊息', dataIndex: 'errorMessage', ellipsis: true },
    {
      title: '發送時間',
      dataIndex: 'sentAt',
      width: 180,
      render: (v) => (v ? new Date(v).toLocaleString('zh-TW') : '—'),
    },
  ]

  return (
    <div>
      <Space style={{ marginBottom: 16 }} align="center">
        <Button onClick={() => navigate('/broadcasts')}>← 回列表</Button>
        <Title level={4} style={{ margin: 0 }}>{task.name}</Title>
        <Tag color={STATUS_COLOR[task.status]}>{STATUS_LABEL[task.status]}</Tag>
      </Space>

      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ReloadOutlined />} onClick={fetchData} loading={loading}>重新整理</Button>
        {task.status === 'DRAFT' && (
          <Button type="primary" icon={<PlayCircleOutlined />} onClick={handleSubmit}>
            送出執行
          </Button>
        )}
        {(task.status === 'RUNNING' || task.status === 'QUEUED' || task.status === 'PAUSED') && (
          <Popconfirm title="確定取消此任務？" okText="取消任務" cancelText="返回" onConfirm={handleCancel}>
            <Button danger icon={<StopOutlined />}>取消任務</Button>
          </Popconfirm>
        )}
      </Space>

      {task.errorMessage && (
        <Alert
          type={task.status === 'FAILED' ? 'error' : 'warning'}
          message={task.errorMessage}
          showIcon
          style={{ marginBottom: 16 }}
        />
      )}

      <Card title="進度" size="small" style={{ marginBottom: 16 }}>
        <Progress
          percent={percent}
          status={task.status === 'FAILED' ? 'exception' : task.status === 'COMPLETED' ? 'success' : 'active'}
        />
        <Space size="large" style={{ marginTop: 12 }}>
          <span>收件人：{task.totalRecipients}</span>
          <span>已送：{task.sentCount}</span>
          <span style={{ color: '#52c41a' }}>成功：{task.successCount}</span>
          <span style={{ color: '#ff4d4f' }}>失敗：{task.failedCount}</span>
        </Space>
      </Card>

      <Card title="任務資訊" size="small" style={{ marginBottom: 16 }}>
        <Descriptions size="small" column={2}>
          <Descriptions.Item label="任務 ID">{task.id}</Descriptions.Item>
          <Descriptions.Item label="目標類型">{task.targetType}</Descriptions.Item>
          <Descriptions.Item label="目標篩選" span={2}>
            <code style={{ fontSize: 12 }}>{task.targetFilter ?? '—'}</code>
          </Descriptions.Item>
          <Descriptions.Item label="排程">
            {task.scheduledAt ? new Date(task.scheduledAt).toLocaleString('zh-TW') : '立即'}
          </Descriptions.Item>
          <Descriptions.Item label="建立時間">
            {new Date(task.createdAt).toLocaleString('zh-TW')}
          </Descriptions.Item>
          <Descriptions.Item label="開始時間">
            {task.startedAt ? new Date(task.startedAt).toLocaleString('zh-TW') : '—'}
          </Descriptions.Item>
          <Descriptions.Item label="結束時間">
            {task.finishedAt ? new Date(task.finishedAt).toLocaleString('zh-TW') : '—'}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Card title="訊息內容" size="small" style={{ marginBottom: 16 }}>
        <pre style={{ background: '#f5f5f5', padding: 12, borderRadius: 4, overflow: 'auto' }}>
          {prettyJson(task.messageContent)}
        </pre>
      </Card>

      <Card title={`分片 (${task.chunks?.length ?? 0})`} size="small">
        <Table
          rowKey="id"
          columns={chunkColumns}
          dataSource={task.chunks ?? []}
          pagination={false}
          size="small"
        />
      </Card>
    </div>
  )
}

function prettyJson(s: string): string {
  try {
    return JSON.stringify(JSON.parse(s), null, 2)
  } catch {
    return s
  }
}
