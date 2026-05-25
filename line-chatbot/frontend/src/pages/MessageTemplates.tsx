/**
 * 訊息模板管理頁面
 * - 顯示模板列表（名稱、類型、更新時間）
 * - 新增 / 編輯 Modal：name、messageType、content（JSON 編輯）
 * - 模板內容驗證：JSON 格式 + 必為非空陣列（呼叫後端時 service 端會再驗一次）
 */
import { useEffect, useMemo, useState } from 'react'
import {
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
import FlexEditor from '../components/FlexEditor/FlexEditor'
import type { MessageTemplate, MessageTemplateInput, MessageType } from '../types'

const { Title } = Typography

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
    setModalOpen(true)
  }

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields()
      if (!isJsonValid(values.content)) {
        message.error('訊息內容 JSON 格式不正確')
        return
      }
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

  const isJsonValid = (content: string): boolean => {
    try {
      const parsed = JSON.parse(content)
      if (!Array.isArray(parsed) || parsed.length === 0) return false
      if (parsed.length > 5) return false
      return true
    } catch {
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
        width={1000}
        okText="儲存"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <div style={{ display: 'flex', gap: 12 }}>
            <Form.Item
              name="name"
              label="模板名稱"
              rules={[{ required: true, max: 100 }]}
              style={{ flex: 2 }}
            >
              <Input placeholder="例：新春問候、優惠通知" />
            </Form.Item>
            <Form.Item
              name="messageType"
              label="訊息類型"
              rules={[{ required: true }]}
              style={{ flex: 1 }}
            >
              <Select options={TYPE_OPTIONS} />
            </Form.Item>
          </div>

          <Form.Item
            name="content"
            label="訊息內容（左：JSON 編輯　右：即時預覽）"
            rules={[{ required: true }]}
          >
            <FlexEditorField />
          </Form.Item>
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

/**
 * Form.Item 受控 wrapper：將 value/onChange 轉接給 FlexEditor，
 * 並在載入預設時順便更新表單的 messageType 欄位。
 */
function FlexEditorField({
  value,
  onChange,
}: {
  value?: string
  onChange?: (v: string) => void
}) {
  const form = Form.useFormInstance<MessageTemplateInput>()
  return (
    <FlexEditor
      value={value ?? ''}
      onChange={(v) => onChange?.(v)}
      onPresetTypeChange={(type) => form.setFieldValue('messageType', type)}
      height={420}
    />
  )
}
