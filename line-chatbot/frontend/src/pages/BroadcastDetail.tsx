/**
 * 推播任務詳情
 * Phase 4 升級：
 *  - SSE 即時進度推送（取代 3 秒輪詢），event = "progress"
 *  - 新增 StatisticsPanel（成功率、發送速率、錯誤分布）
 *  - 新增 FailureTable（失敗 / 重試中 chunk 清單）
 *  - 進度欄位優先採用 SSE 事件即時值，DB 詳情仍用初始/手動 refresh
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Alert,
  Badge,
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
  getBroadcastClicks,
  getBroadcastFailures,
  getBroadcastProgress,
  getBroadcastStatistics,
  openProgressStream,
  submitBroadcast,
} from '../api/broadcasts'
import StatisticsPanel from '../components/Broadcast/StatisticsPanel'
import FailureTable from '../components/Broadcast/FailureTable'
import ClickStatsPanel from '../components/Broadcast/ClickStatsPanel'
import type {
  BroadcastChunkStatus,
  BroadcastFailure,
  BroadcastProgressEvent,
  BroadcastStatistics,
  BroadcastStatus,
  BroadcastTask,
  ClickStatistics,
} from '../types'
import { API_MODE_LABEL, TARGET_TYPE_LABEL } from '../types'

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

const FINAL_STATUSES: BroadcastStatus[] = ['COMPLETED', 'FAILED', 'CANCELLED']

export default function BroadcastDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [task, setTask] = useState<BroadcastTask | null>(null)
  const [loading, setLoading] = useState(false)
  const [stats, setStats] = useState<BroadcastStatistics | null>(null)
  const [failures, setFailures] = useState<BroadcastFailure[]>([])
  const [clickStats, setClickStats] = useState<ClickStatistics | null>(null)
  const [sseConnected, setSseConnected] = useState(false)
  const eventSourceRef = useRef<EventSource | null>(null)

  const fetchAll = useCallback(async () => {
    if (!id) return
    setLoading(true)
    try {
      const [detailRes, statsRes, failsRes, clicksRes] = await Promise.all([
        getBroadcastProgress(Number(id)),
        getBroadcastStatistics(Number(id)),
        getBroadcastFailures(Number(id)),
        getBroadcastClicks(Number(id)),
      ])
      setTask(detailRes.data.data)
      setStats(statsRes.data.data)
      setFailures(failsRes.data.data)
      setClickStats(clicksRes.data.data)
    } catch (err: unknown) {
      message.error(err instanceof Error ? err.message : '載入失敗')
    } finally {
      setLoading(false)
    }
  }, [id])

  useEffect(() => {
    fetchAll()
  }, [fetchAll])

  /**
   * SSE 連線：執行中或排隊中時建立 EventSource，事件達終態時自動斷線並 refetch。
   */
  useEffect(() => {
    if (!task || !id) return
    if (FINAL_STATUSES.includes(task.status)) {
      eventSourceRef.current?.close()
      eventSourceRef.current = null
      setSseConnected(false)
      return
    }
    if (eventSourceRef.current) return

    const es = openProgressStream(Number(id))
    eventSourceRef.current = es

    es.addEventListener('connected', () => setSseConnected(true))
    es.addEventListener('progress', (ev) => {
      try {
        const data = JSON.parse((ev as MessageEvent).data) as BroadcastProgressEvent
        setTask((prev) =>
          prev
            ? {
                ...prev,
                status: data.status ?? prev.status,
                sentCount: data.sentCount ?? prev.sentCount,
                successCount: data.successCount ?? prev.successCount,
                failedCount: data.failedCount ?? prev.failedCount,
                totalRecipients: data.totalRecipients ?? prev.totalRecipients,
              }
            : prev,
        )
        // 終態事件 → refetch 取最新 chunk 與 stats
        if (data.type !== 'PROGRESS') {
          setTimeout(fetchAll, 500)
        }
      } catch {
        // 忽略解析錯誤
      }
    })
    es.onerror = () => setSseConnected(false)

    return () => {
      es.close()
      eventSourceRef.current = null
      setSseConnected(false)
    }
  }, [task?.status, id, fetchAll, task])

  const handleSubmit = async () => {
    if (!task) return
    try {
      await submitBroadcast(task.id)
      message.success('任務已提交執行')
      fetchAll()
    } catch (err: unknown) {
      message.error(err instanceof Error ? err.message : '操作失敗')
    }
  }

  const handleCancel = async () => {
    if (!task) return
    try {
      await cancelBroadcast(task.id)
      message.success('任務已取消')
      fetchAll()
    } catch (err: unknown) {
      message.error(err instanceof Error ? err.message : '操作失敗')
    }
  }

  if (!task) {
    return <div>載入中…</div>
  }

  const percent =
    task.totalRecipients > 0 ? Math.round((task.sentCount / task.totalRecipients) * 100) : 0

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

  const showLiveBadge = !FINAL_STATUSES.includes(task.status)

  return (
    <div>
      <Space style={{ marginBottom: 16 }} align="center">
        <Button onClick={() => navigate('/broadcasts')}>← 回列表</Button>
        <Title level={4} style={{ margin: 0 }}>
          {task.name}
        </Title>
        <Tag color={STATUS_COLOR[task.status]}>{STATUS_LABEL[task.status]}</Tag>
        {showLiveBadge && (
          <Badge
            status={sseConnected ? 'processing' : 'default'}
            text={sseConnected ? '即時連線中 (SSE)' : '即時連線中斷'}
          />
        )}
      </Space>

      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ReloadOutlined />} onClick={fetchAll} loading={loading}>
          重新整理
        </Button>
        {task.status === 'DRAFT' && (
          <Button type="primary" icon={<PlayCircleOutlined />} onClick={handleSubmit}>
            送出執行
          </Button>
        )}
        {(task.status === 'RUNNING' || task.status === 'QUEUED' || task.status === 'PAUSED') && (
          <Popconfirm
            title="確定取消此任務？"
            okText="取消任務"
            cancelText="返回"
            onConfirm={handleCancel}
          >
            <Button danger icon={<StopOutlined />}>
              取消任務
            </Button>
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
          status={
            task.status === 'FAILED'
              ? 'exception'
              : task.status === 'COMPLETED'
              ? 'success'
              : 'active'
          }
        />
        {task.targetType === 'NARROWCAST' ? (
          <div style={{ marginTop: 12, color: '#888' }}>
            由 LINE Narrowcast 平台自管分發（X-Line-Request-Id 追蹤）
          </div>
        ) : task.apiMode === 'PUSH' ? (
          <Space size="large" style={{ marginTop: 12 }}>
            <span>收件人：{task.totalRecipients}</span>
            <span>已送：{task.sentCount}</span>
            <span style={{ color: '#52c41a' }}>成功：{task.successCount}</span>
            <span style={{ color: '#ff4d4f' }}>失敗：{task.failedCount}</span>
          </Space>
        ) : (
          <div style={{ marginTop: 12, color: '#666' }}>
            Multicast：{STATUS_LABEL[task.status]}
            <span style={{ marginLeft: 12, fontSize: 12, color: '#999' }}>
              （LINE 不提供 per-user 結果，實際送達請查看 Dashboard「今日 LINE multicast 累計送達」）
            </span>
          </div>
        )}
      </Card>

      <div style={{ marginBottom: 16 }}>
        <StatisticsPanel stats={stats} task={task} loading={loading} />
      </div>

      <div style={{ marginBottom: 16 }}>
        <ClickStatsPanel stats={clickStats} loading={loading} />
      </div>

      <div style={{ marginBottom: 16 }}>
        <FailureTable failures={failures} loading={loading} />
      </div>

      <Card title="任務資訊" size="small" style={{ marginBottom: 16 }}>
        <Descriptions size="small" column={2}>
          <Descriptions.Item label="任務 ID">{task.id}</Descriptions.Item>
          <Descriptions.Item label="目標類型">{TARGET_TYPE_LABEL[task.targetType]}</Descriptions.Item>
          {task.targetType !== 'NARROWCAST' && (
            <Descriptions.Item label="API 模式">{API_MODE_LABEL[task.apiMode]}</Descriptions.Item>
          )}
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

      <Card title={`批次 (${task.chunks?.length ?? 0})`} size="small">
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
