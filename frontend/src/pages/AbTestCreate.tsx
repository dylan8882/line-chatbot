/**
 * 建立 A/B 測試：
 *  - 名稱、目標（ALL / TAGS）、排程
 *  - 2 個 variants（label + 模板 + 流量比例）
 *  - 提交 → 後端切分 audience 建立 N 個獨立任務（target_type=USER_LIST）
 */
import { useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  DatePicker,
  Form,
  Input,
  InputNumber,
  Radio,
  Select,
  Space,
  Typography,
  message,
} from 'antd'
import dayjs, { Dayjs } from 'dayjs'
import { useNavigate } from 'react-router-dom'
import { createAbTest } from '../api/broadcasts'
import { getMessageTemplates } from '../api/messageTemplates'
import { getTags } from '../api/tags'
import TagPicker from '../components/Tags/TagPicker'
import type {
  AbTestCreateRequest,
  BroadcastTargetType,
  MessageTemplate,
  Tag,
  TagMatch,
} from '../types'

const { Title } = Typography

interface FormValues {
  name: string
  targetType: BroadcastTargetType
  tagIds?: number[]
  tagMatch?: TagMatch
  scheduledAt?: Dayjs | null
  variants: { label: string; templateId?: number; trafficPercent: number }[]
}

export default function AbTestCreate() {
  const navigate = useNavigate()
  const [form] = Form.useForm<FormValues>()
  const [templates, setTemplates] = useState<MessageTemplate[]>([])
  const [tags, setTags] = useState<Tag[]>([])
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    getMessageTemplates().then((r) => setTemplates(r.data.data)).catch(() => {})
    getTags().then((r) => setTags(r.data.data)).catch(() => {})
    form.setFieldsValue({
      targetType: 'ALL',
      tagMatch: 'ANY',
      variants: [
        { label: 'A', trafficPercent: 50 },
        { label: 'B', trafficPercent: 50 },
      ],
    })
  }, [form])

  const targetType = Form.useWatch('targetType', form)

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields()
      const totalPct = (values.variants ?? []).reduce(
        (s, v) => s + (v.trafficPercent ?? 0),
        0,
      )
      if (totalPct !== 100) {
        message.error(`流量百分比加總須為 100，目前 = ${totalPct}`)
        return
      }
      setSubmitting(true)
      const req: AbTestCreateRequest = {
        name: values.name,
        variants: values.variants.map((v) => ({
          label: v.label,
          templateId: v.templateId,
          trafficPercent: v.trafficPercent,
        })),
        targetType: values.targetType,
        tagIds: values.targetType === 'TAGS' ? values.tagIds : undefined,
        tagMatch: values.targetType === 'TAGS' ? values.tagMatch ?? 'ANY' : undefined,
        scheduledAt: values.scheduledAt ? values.scheduledAt.toISOString() : undefined,
        idempotencyKey: crypto.randomUUID(),
      }
      const res = await createAbTest(req)
      const tasks = res.data.data
      const abTestId = (tasks[0] as unknown as { abTestId: string }).abTestId
      message.success(`已建立 ${tasks.length} 個 variant 任務`)
      if (abTestId) {
        navigate(`/broadcasts/ab-test/${abTestId}`)
      } else {
        navigate('/broadcasts')
      }
    } catch (err: unknown) {
      if (err instanceof Error) message.error(err.message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div>
      <Title level={4}>新增 A/B 測試推播</Title>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="A/B 測試說明"
        description="建立後會根據流量比例切分 audience，產生多個獨立任務。可分別 submit、各自統計成效。建議搭配 click tracking 才能真正比較轉換率（Phase 7）。"
      />

      <Form form={form} layout="vertical" style={{ maxWidth: 900 }}>
        <Card title="基本資訊" size="small" style={{ marginBottom: 16 }}>
          <Form.Item name="name" label="任務名稱" rules={[{ required: true, max: 180 }]}>
            <Input placeholder="例：母親節優惠 A/B 測試" />
          </Form.Item>
        </Card>

        <Card title="目標" size="small" style={{ marginBottom: 16 }}>
          <Form.Item name="targetType" label="目標類型">
            <Radio.Group>
              <Radio.Button value="ALL">全部已加好友</Radio.Button>
              <Radio.Button value="TAGS">依標籤</Radio.Button>
            </Radio.Group>
          </Form.Item>
          {targetType === 'TAGS' && (
            <>
              <Form.Item
                name="tagIds"
                label="選擇標籤"
                rules={[{ required: true, message: '請選擇至少一個標籤' }]}
              >
                <TagPicker tags={tags} placeholder="選擇標籤" style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="tagMatch" label="多標籤匹配">
                <Radio.Group>
                  <Radio value="ANY">任一（聯集）</Radio>
                  <Radio value="ALL">全部（交集）</Radio>
                </Radio.Group>
              </Form.Item>
            </>
          )}
        </Card>

        <Card title="排程" size="small" style={{ marginBottom: 16 }}>
          <Form.Item name="scheduledAt" label="排程發送時間（留空 = 立即送出）">
            <DatePicker
              showTime
              format="YYYY-MM-DD HH:mm"
              disabledDate={(d) => d.isBefore(dayjs().startOf('day'))}
              style={{ width: 280 }}
            />
          </Form.Item>
        </Card>

        <Card title="變體（Variants）" size="small" style={{ marginBottom: 16 }}>
          <Form.List name="variants">
            {(fields, { add, remove }) => (
              <>
                {fields.map((field) => (
                  <Space
                    key={field.key}
                    align="baseline"
                    style={{ display: 'flex', marginBottom: 8 }}
                  >
                    <Form.Item
                      name={[field.name, 'label']}
                      rules={[{ required: true, message: '輸入標籤' }]}
                      style={{ width: 100 }}
                    >
                      <Input placeholder="A / B" />
                    </Form.Item>
                    <Form.Item
                      name={[field.name, 'templateId']}
                      rules={[{ required: true, message: '選擇模板' }]}
                      style={{ width: 320 }}
                    >
                      <Select
                        placeholder="選擇模板"
                        options={templates.map((t) => ({
                          value: t.id,
                          label: `${t.name}（${t.messageType}）`,
                        }))}
                      />
                    </Form.Item>
                    <Form.Item
                      name={[field.name, 'trafficPercent']}
                      rules={[{ required: true }]}
                      style={{ width: 120 }}
                    >
                      <InputNumber addonAfter="%" min={1} max={100} />
                    </Form.Item>
                    {fields.length > 2 && (
                      <Button danger onClick={() => remove(field.name)}>
                        移除
                      </Button>
                    )}
                  </Space>
                ))}
                {fields.length < 4 && (
                  <Button onClick={() => add({ label: 'C', trafficPercent: 0 })}>
                    新增變體
                  </Button>
                )}
              </>
            )}
          </Form.List>
        </Card>

        <Space>
          <Button onClick={() => navigate('/broadcasts')}>取消</Button>
          <Button type="primary" loading={submitting} onClick={handleSubmit}>
            建立 A/B 測試
          </Button>
        </Space>
      </Form>
    </div>
  )
}
