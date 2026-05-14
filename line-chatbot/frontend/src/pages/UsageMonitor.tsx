/**
 * 用量監控頁面
 * 提供日期範圍選擇、訊息量趨勢圖表，以及訊息紀錄分頁表格
 */
import { useEffect, useState, useCallback } from 'react'
import { Row, Col, Card, Select, Table, Tag, Typography, Alert, Spin } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { getUsage, getLogs } from '../api/dashboard'
import UsageChart from '../components/Charts/UsageChart'
import type { DailyStats, MessageLog, ResponseType } from '../types'

const { Title } = Typography

const responseTypeColors: Record<ResponseType, string> = {
  QA: 'green',
  AI: 'blue',
  NONE: 'default',
}

const DAY_OPTIONS = [
  { label: '近 7 天', value: 7 },
  { label: '近 14 天', value: 14 },
  { label: '近 30 天', value: 30 },
]

const LOG_PAGE_SIZE = 20

export default function UsageMonitor() {
  const [days, setDays] = useState(7)
  const [chartData, setChartData] = useState<DailyStats[]>([])
  const [chartLoading, setChartLoading] = useState(false)
  const [chartError, setChartError] = useState<string | null>(null)

  const [logs, setLogs] = useState<MessageLog[]>([])
  const [logsTotal, setLogsTotal] = useState(0)
  const [logsPage, setLogsPage] = useState(1)
  const [logsLoading, setLogsLoading] = useState(false)

  const fetchChart = useCallback(async (d: number) => {
    setChartLoading(true)
    setChartError(null)
    try {
      const res = await getUsage(d)
      setChartData(res.data.data)
    } catch (err: unknown) {
      setChartError(err instanceof Error ? err.message : '載入趨勢資料失敗')
    } finally {
      setChartLoading(false)
    }
  }, [])

  const fetchLogs = useCallback(async (p: number) => {
    setLogsLoading(true)
    try {
      const res = await getLogs(p - 1, LOG_PAGE_SIZE)
      const pageData = res.data.data
      setLogs(pageData.content)
      setLogsTotal(pageData.totalElements)
    } catch {
      // 紀錄載入失敗不影響圖表
    } finally {
      setLogsLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchChart(days)
  }, [days, fetchChart])

  useEffect(() => {
    fetchLogs(logsPage)
  }, [logsPage, fetchLogs])

  const columns: ColumnsType<MessageLog> = [
    {
      title: 'LINE 用戶 ID',
      dataIndex: 'lineUserId',
      key: 'lineUserId',
      width: 180,
      ellipsis: true,
    },
    {
      title: '訊息內容',
      dataIndex: 'messageText',
      key: 'messageText',
      ellipsis: true,
    },
    {
      title: '回應類型',
      dataIndex: 'responseType',
      key: 'responseType',
      width: 100,
      render: (t: ResponseType) => (
        <Tag color={responseTypeColors[t] ?? 'default'}>{t}</Tag>
      ),
    },
    {
      title: '延遲 (ms)',
      dataIndex: 'latencyMs',
      key: 'latencyMs',
      width: 100,
    },
    {
      title: '時間',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 160,
      render: (v: string) => new Date(v).toLocaleString('zh-TW'),
    },
  ]

  return (
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>
        用量監控
      </Title>

      <Card
        title="訊息量趨勢"
        extra={
          <Select
            value={days}
            options={DAY_OPTIONS}
            onChange={(v) => setDays(v)}
            style={{ width: 120 }}
          />
        }
        style={{ marginBottom: 24 }}
      >
        {chartError && (
          <Alert message={chartError} type="error" showIcon style={{ marginBottom: 16 }} />
        )}
        {chartLoading ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <Spin />
          </div>
        ) : (
          <UsageChart data={chartData} />
        )}
      </Card>

      <Row gutter={[0, 0]}>
        <Col span={24}>
          <Card title="訊息紀錄">
            <Table
              rowKey="id"
              columns={columns}
              dataSource={logs}
              loading={logsLoading}
              scroll={{ x: 800 }}
              pagination={{
                current: logsPage,
                pageSize: LOG_PAGE_SIZE,
                total: logsTotal,
                showSizeChanger: false,
                showTotal: (t) => `共 ${t} 筆`,
                onChange: setLogsPage,
              }}
            />
          </Card>
        </Col>
      </Row>
    </div>
  )
}
