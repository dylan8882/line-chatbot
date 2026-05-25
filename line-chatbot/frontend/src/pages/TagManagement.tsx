/**
 * 標籤管理頁面
 * - 新增 / 編輯 / 刪除標籤
 * - 顯示每個標籤的用戶數
 */
import { useEffect, useState } from 'react'
import {
  Button,
  Form,
  Input,
  Modal,
  Popconfirm,
  Space,
  Table,
  Typography,
  message,
  ColorPicker,
} from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { createTag, deleteTag, getTags, updateTag } from '../api/tags'
import TagChip from '../components/Tags/TagChip'
import type { Tag, TagInput } from '../types'

const { Title } = Typography

export default function TagManagement() {
  const [data, setData] = useState<Tag[]>([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<Tag | null>(null)
  const [form] = Form.useForm<TagInput>()

  const fetchData = async () => {
    setLoading(true)
    try {
      const res = await getTags()
      setData(res.data.data)
    } catch (err: unknown) {
      message.error(err instanceof Error ? err.message : '載入標籤失敗')
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
    form.setFieldsValue({ color: '#1677ff' })
    setModalOpen(true)
  }

  const handleOpenEdit = (tag: Tag) => {
    setEditing(tag)
    form.setFieldsValue({
      name: tag.name,
      color: tag.color,
      description: tag.description ?? undefined,
    })
    setModalOpen(true)
  }

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields()
      if (editing) {
        await updateTag(editing.id, values)
        message.success('修改成功')
      } else {
        await createTag(values)
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
      await deleteTag(id)
      message.success('刪除成功')
      fetchData()
    } catch (err: unknown) {
      message.error(err instanceof Error ? err.message : '刪除失敗')
    }
  }

  const columns: ColumnsType<Tag> = [
    {
      title: '標籤',
      dataIndex: 'name',
      render: (_, record) => <TagChip tag={record} />,
    },
    { title: '說明', dataIndex: 'description', render: (v) => v || '—' },
    { title: '用戶數', dataIndex: 'userCount', width: 100, align: 'right' },
    {
      title: '操作',
      width: 180,
      render: (_, record) => (
        <Space>
          <Button size="small" onClick={() => handleOpenEdit(record)}>編輯</Button>
          <Popconfirm
            title="確定刪除此標籤？"
            description="刪除後，所有用戶的此標籤也會一併移除。"
            okText="刪除"
            cancelText="取消"
            onConfirm={() => handleDelete(record.id)}
          >
            <Button size="small" danger>刪除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>標籤管理</Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={handleOpenCreate}>
          新增標籤
        </Button>
      </div>

      <Table
        rowKey="id"
        columns={columns}
        dataSource={data}
        loading={loading}
        pagination={false}
      />

      <Modal
        title={editing ? '編輯標籤' : '新增標籤'}
        open={modalOpen}
        onOk={handleSubmit}
        onCancel={() => setModalOpen(false)}
        okText="儲存"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="name"
            label="標籤名稱"
            rules={[{ required: true, message: '請輸入標籤名稱' }, { max: 50 }]}
          >
            <Input placeholder="例：VIP、新客、北部" />
          </Form.Item>

          <Form.Item
            name="color"
            label="顏色"
            getValueFromEvent={(c) => (typeof c === 'string' ? c : c.toHexString())}
          >
            <ColorPicker format="hex" />
          </Form.Item>

          <Form.Item name="description" label="說明" rules={[{ max: 200 }]}>
            <Input.TextArea rows={3} placeholder="此標籤代表的客群描述（選填）" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
