/**
 * 訊息模板管理頁面
 * - 顯示模板列表（名稱、類型、更新時間）
 * - 新增 / 編輯 Modal：name、messageType、content（JSON 編輯）
 * - 模板內容驗證：JSON 格式 + 必為非空陣列（呼叫後端時 service 端會再驗一次）
 */
import { useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Button,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import {
  createMessageTemplate,
  deleteMessageTemplate,
  getMessageTemplates,
  updateMessageTemplate,
} from '../api/messageTemplates'
import type { MessageTemplate, MessageTemplateInput, MessageType } from '../types'

const { Title } = Typography
const { TextArea } = Input

const TYPE_OPTIONS: { value: MessageType; label: string }[] = [
  { value: 'TEXT', label: '文字' },
  { value: 'FLEX', label: 'Flex' },
  { value: 'IMAGE', label: '圖片' },
  { value: 'TEMPLATE', label: 'Template' },
]

const TYPE_COLOR: Record<MessageType, string> = {
  TEXT: 'blue',
  FLEX: 'purple',
  IMAGE: 'gold',
  TEMPLATE: 'green',
}

const SAMPLE_TEXT = JSON.stringify(
  [{ type: 'text', text: '哈囉！這是一則 LINE 推播訊息。' }],
  null,
  2,
)

export default function MessageTemplates() {
  const [data, setData] = useState<MessageTemplate[]>([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<MessageTemplate | null>(null)
  const [form] = Form.useForm<MessageTemplateInput>()
  const [jsonError, setJsonError] = useState<string | null>(null)

  const fetchData = async () => {
    setLoading(true)
    try {
      const res = await getMessageTemplates()
      setData(res.data.data)
    } catch (err: unknown) {
      message.error(err instanceof Error ? err.message : '載入模板失敗')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchData()
  }, [])

  const handleOpenCreate = () => {
    setEditing(null)
    form.resetFields()
    form.setFieldsValue({ messageType: 'TEXT', content: SAMPLE_TEXT })
    setJsonError(null)
    setModalOpen(true)
  }

  const handleOpenEdit = (t: MessageTemplate) => {
    setEditing(t)
    form.setFieldsValue({
      name: t.name,
      messageType: t.messageType,
      content: prettyJson(t.content),
      thumbnail: t.thumbnail ?? undefined,
    })
    setJsonError(null)
    setModalOpen(true)
  }

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields()
      if (!validateJson(values.content)) return
      if (editing) {
        await updateMessageTemplate(editing.id, values)
        message.success('修改成功')
      } else {
        await createMessageTemplate(values)
        message.success('新增成功')
      }
      setModalOpen(false)
      fetchData()
    } catch (err: unknown) {
      if (err instanceof Error) message.error(err.message)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteMessageTemplate(id)
      message.success('刪除成功')
      fetchData()
    } catch (err: unknown) {
      message.error(err instanceof Error ? err.message : '刪除失敗')
    }
  }

  const validateJson = (content: string): boolean => {
    try {
      const parsed = JSON.parse(content)
      if (!Array.isArray(parsed) || parsed.length === 0) {
        setJsonError('content 必須為非空陣列')
        return false
      }
      if (parsed.length > 5) {
        setJsonError('LINE 單次最多 5 則訊息')
        return false
      }
      setJsonError(null)
      return true
    } catch (e) {
      setJsonError('JSON 格式錯誤：' + (e as Error).message)
      return false
    }
  }

  const columns: ColumnsType<MessageTemplate> = useMemo(
    () => [
      { title: '名稱', dataIndex: 'name' },
      {
        title: '類型',
        dataIndex: 'messageType',
        width: 100,
        render: (t: MessageType) => <Tag color={TYPE_COLOR[t]}>{t}</Tag>,
      },
      {
        title: '更新時間',
        dataIndex: 'updatedAt',
        width: 180,
        render: (v) => new Date(v).toLocaleString('zh-TW'),
      },
      {
        title: '操作',
        width: 160,
        render: (_, record) => (
          <Space>
            <Button size="small" onClick={() => handleOpenEdit(record)}>編輯</Button>
            <Popconfirm
              title="確定刪除此模板？"
              okText="刪除"
              cancelText="取消"
              onConfirm={() => handleDelete(record.id)}
            >
              <Button size="small" danger>刪除</Button>
            </Popconfirm>
          </Space>
        ),
      },
    ],
    [],
  )

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>訊息模板</Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={handleOpenCreate}>
          新增模板
        </Button>
      </div>

      <Table rowKey="id" columns={columns} dataSource={data} loading={loading} pagination={false} />

      <Modal
        title={editing ? '編輯模板' : '新增模板'}
        open={modalOpen}
        onOk={handleSubmit}
        onCancel={() => setModalOpen(false)}
        width={720}
        okText="儲存"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="模板名稱" rules={[{ required: true, max: 100 }]}>
            <Input placeholder="例：新春問候、優惠通知" />
          </Form.Item>

          <Form.Item name="messageType" label="訊息類型" rules={[{ required: true }]}>
            <Select options={TYPE_OPTIONS} />
          </Form.Item>

          <Form.Item
            name="content"
            label={
              <span>
                訊息內容（LINE messages 陣列 JSON）{' '}
                <a
                  href="https://developers.line.biz/flex-simulator/"
                  target="_blank"
                  rel="noreferrer"
                >
                  Flex Simulator
                </a>
              </span>
            }
            rules={[{ required: true }]}
          >
            <TextArea rows={12} style={{ fontFamily: 'monospace' }} />
          </Form.Item>

          {jsonError && <Alert type="error" message={jsonError} showIcon />}
        </Form>
      </Modal>
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
