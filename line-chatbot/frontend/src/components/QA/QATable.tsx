/**
 * 問答對資料表格元件
 * 顯示 QA 列表，支援啟用/停用切換、編輯、刪除操作
 */
import { Table, Tag, Switch, Button, Space, Popconfirm, Tooltip } from 'antd'
import { EditOutlined, DeleteOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import type { QAPair } from '../../types'

interface QATableProps {
  data: QAPair[]
  total: number
  page: number
  pageSize: number
  loading: boolean
  onPageChange: (page: number) => void
  onEdit: (item: QAPair) => void
  onDelete: (id: number) => void
  onToggle: (id: number) => void
}

const matchTypeColors: Record<string, string> = {
  EXACT: 'blue',
  CONTAINS: 'green',
  REGEX: 'orange',
}

export default function QATable({
  data,
  total,
  page,
  pageSize,
  loading,
  onPageChange,
  onEdit,
  onDelete,
  onToggle,
}: QATableProps) {
  const columns: ColumnsType<QAPair> = [
    {
      title: '關鍵字',
      dataIndex: 'keyword',
      key: 'keyword',
      ellipsis: true,
      width: 200,
    },
    {
      title: '回答內容',
      dataIndex: 'answer',
      key: 'answer',
      ellipsis: true,
    },
    {
      title: '比對方式',
      dataIndex: 'matchType',
      key: 'matchType',
      width: 140,
      render: (matchType: string) => (
        <Tag color={matchTypeColors[matchType] ?? 'default'}>{matchType}</Tag>
      ),
    },
    {
      title: '優先順序',
      dataIndex: 'priority',
      key: 'priority',
      width: 90,
      sorter: (a, b) => a.priority - b.priority,
    },
    {
      title: '啟用',
      dataIndex: 'isActive',
      key: 'isActive',
      width: 80,
      render: (isActive: boolean, record: QAPair) => (
        <Switch
          checked={isActive}
          onChange={() => onToggle(record.id)}
          size="small"
        />
      ),
    },
    {
      title: '建立時間',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 160,
      render: (v: string) => new Date(v).toLocaleString('zh-TW'),
    },
    {
      title: '操作',
      key: 'actions',
      width: 100,
      fixed: 'right',
      render: (_: unknown, record: QAPair) => (
        <Space>
          <Tooltip title="編輯">
            <Button
              type="text"
              icon={<EditOutlined />}
              onClick={() => onEdit(record)}
            />
          </Tooltip>
          <Popconfirm
            title="確定要刪除這筆問答嗎？"
            onConfirm={() => onDelete(record.id)}
            okText="刪除"
            cancelText="取消"
            okType="danger"
          >
            <Tooltip title="刪除">
              <Button type="text" danger icon={<DeleteOutlined />} />
            </Tooltip>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <Table
      rowKey="id"
      columns={columns}
      dataSource={data}
      loading={loading}
      scroll={{ x: 900 }}
      pagination={{
        current: page,
        pageSize,
        total,
        showSizeChanger: false,
        showTotal: (t) => `共 ${t} 筆`,
        onChange: onPageChange,
      }}
    />
  )
}
