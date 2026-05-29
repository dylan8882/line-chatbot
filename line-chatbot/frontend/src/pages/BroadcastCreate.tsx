/** 建立推播：選訊息來源、目標、API 模式 → 預估 → 草稿 / 直接送出。 */
import { useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  DatePicker,
  Divider,
  Form,
  Input,
  Modal,
  Radio,
  Select,
  Space,
  Statistic,
  Typography,
  message,
} from 'antd'
import dayjs, { Dayjs } from 'dayjs'
import { useLocation, useNavigate } from 'react-router-dom'
import { createBroadcast, estimateBroadcast, submitBroadcast, testSendBroadcast } from '../api/broadcasts'
import { getMessageTemplates } from '../api/messageTemplates'
import { getTags } from '../api/tags'
import TagPicker from '../components/Tags/TagPicker'
import FlexPreview from '../components/FlexEditor/FlexPreview'
import ImportFromSimulator from '../components/FlexEditor/ImportFromSimulator'
import LineUserPicker from '../components/Broadcast/LineUserPicker'
import type {
  BroadcastCreateRequest,
  BroadcastTargetType,
  LineUser,
  MessageTemplate,
  Tag,
  TagMatch,
} from '../types'

/** route state：從 LineUsers 頁帶過來的預選 */
interface LocationState {
  userIds?: number[]
  users?: LineUser[]
}

const { Title, Text } = Typography
const { TextArea } = Input

type MessageSource = 'TEMPLATE' | 'CUSTOM'

interface FormValues {
  name: string
  messageSource: MessageSource
  templateId?: number
  messageContent?: string
  targetType: BroadcastTargetType
  tagIds?: number[]
  tagMatch?: TagMatch
  userIds?: number[]
  apiMode: 'PUSH' | 'MULTICAST'
  scheduledAt?: Dayjs | null
}

export default function BroadcastCreate() {
  const navigate = useNavigate()
  const location = useLocation()
  const initialState = (location.state as LocationState | null) ?? null
  const [form] = Form.useForm<FormValues>()
  const [templates, setTemplates] = useState<MessageTemplate[]>([])
  const [tags, setTags] = useState<Tag[]>([])
  const [estimate, setEstimate] = useState<{ total: number; chunks: number } | null>(null)
  const [estimating, setEstimating] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [testOpen, setTestOpen] = useState(false)
  const [testUserId, setTestUserId] = useState('')
  const [draftId, setDraftId] = useState<number | null>(null)
  const [initialUsers] = useState<LineUser[]>(initialState?.users ?? [])

  useEffect(() => {
    getMessageTemplates().then((r) => setTemplates(r.data.data)).catch(() => {})
    getTags().then((r) => setTags(r.data.data)).catch(() => {})
    const stateUserIds = initialState?.userIds ?? []
    const hasUserList = stateUserIds.length > 0
    form.setFieldsValue({
      messageSource: 'TEMPLATE',
      targetType: hasUserList ? 'USER_LIST' : 'ALL',
      tagMatch: 'ANY',
      userIds: hasUserList ? stateUserIds : undefined,
      apiMode: 'PUSH', // USER_LIST 與一般情境都預設 PUSH
    })
  }, [form, initialState])

  const buildRequest = (values: FormValues): BroadcastCreateRequest => ({
    name: values.name,
    templateId: values.messageSource === 'TEMPLATE' ? values.templateId : undefined,
    messageContent: values.messageSource === 'CUSTOM' ? values.messageContent : undefined,
    targetType: values.targetType,
    tagIds: values.targetType === 'TAGS' ? values.tagIds : undefined,
    tagMatch: values.targetType === 'TAGS' ? values.tagMatch ?? 'ANY' : undefined,
    userIds: values.targetType === 'USER_LIST' ? values.userIds : undefined,
    // NARROWCAST 不用 apiMode（後端強制忽略）
    apiMode: values.targetType === 'NARROWCAST' ? undefined : (values.apiMode ?? 'PUSH'),
    scheduledAt: values.scheduledAt ? values.scheduledAt.toISOString() : undefined,
  })

  const handleEstimate = async () => {
    try {
      const values = await form.validateFields()
      setEstimating(true)
      const res = await estimateBroadcast(buildRequest(values))
      setEstimate({ total: res.data.data.totalRecipients, chunks: res.data.data.totalChunks })
    } catch (err: unknown) {
      if (err instanceof Error) message.error(err.message)
    } finally {
      setEstimating(false)
    }
  }

  /** 建立任務（DRAFT 狀態）並回傳 ID */
  const handleSaveDraft = async (): Promise<number | null> => {
    try {
      const values = await form.validateFields()
      setSubmitting(true)
      const res = await createBroadcast({
        ...buildRequest(values),
        idempotencyKey: crypto.randomUUID(),
      })
      message.success('草稿已建立')
      setDraftId(res.data.data.id)
      return res.data.data.id
    } catch (err: unknown) {
      if (err instanceof Error) message.error(err.message)
      return null
    } finally {
      setSubmitting(false)
    }
  }

  const handleSubmit = async () => {
    let id = draftId
    if (!id) {
      id = await handleSaveDraft()
      if (!id) return
    }
    try {
      setSubmitting(true)
      await submitBroadcast(id)
      message.success('任務已提交執行')
      navigate(`/broadcasts/${id}`)
    } catch (err: unknown) {
      if (err instanceof Error) message.error(err.message)
    } finally {
      setSubmitting(false)
    }
  }

  const handleTestSend = async () => {
    if (!testUserId.trim()) {
      message.warning('請輸入 LINE userId')
      return
    }
    let id = draftId
    if (!id) {
      id = await handleSaveDraft()
      if (!id) return
    }
    try {
      const res = await testSendBroadcast(id, testUserId.trim())
      message.success(`已發送（requestId=${res.data.data.requestId}）`)
      setTestOpen(false)
      setTestUserId('')
    } catch (err: unknown) {
      if (err instanceof Error) message.error(err.message)
    }
  }

  const messageSource = Form.useWatch('messageSource', form)
  const templateId = Form.useWatch('templateId', form)
  const customContent = Form.useWatch('messageContent', form)
  const targetType = Form.useWatch('targetType', form)

  const templateOptions = useMemo(
    () => templates.map((t) => ({ value: t.id, label: `${t.name}（${t.messageType}）` })),
    [templates],
  )

  const previewContent = useMemo(() => {
    if (messageSource === 'TEMPLATE' && templateId) {
      return templates.find((t) => t.id === templateId)?.content ?? ''
    }
    return customContent ?? ''
  }, [messageSource, templateId, customContent, templates])

  return (
    <div>
      <Title level={4}>新增推播</Title>

      <Form form={form} layout="vertical" style={{ maxWidth: 1000 }}>
        <Card title="基本資訊" size="small" style={{ marginBottom: 16 }}>
          <Form.Item name="name" label="任務名稱" rules={[{ required: true, max: 200 }]}>
            <Input placeholder="例：2026 新春問候推播" />
          </Form.Item>
        </Card>

        <Card title="訊息內容" size="small" style={{ marginBottom: 16 }}>
          <Form.Item name="messageSource" label="來源">
            <Radio.Group>
              <Radio.Button value="TEMPLATE">使用模板</Radio.Button>
              <Radio.Button value="CUSTOM">自訂 JSON</Radio.Button>
            </Radio.Group>
          </Form.Item>

          <div style={{ display: 'flex', gap: 16 }}>
            <div style={{ flex: 1, minWidth: 0 }}>
              {messageSource === 'TEMPLATE' ? (
                <Form.Item
                  name="templateId"
                  label="模板"
                  rules={[{ required: true, message: '請選擇模板' }]}
                >
                  <Select options={templateOptions} placeholder="選擇模板" />
                </Form.Item>
              ) : (
                <>
                  <div style={{ marginBottom: 8 }}>
                    <ImportFromSimulator
                      onImport={(wrapped) => form.setFieldValue('messageContent', wrapped)}
                    />
                  </div>
                  <Form.Item
                    name="messageContent"
                    label="messages JSON 陣列"
                    rules={[{ required: true, message: '請輸入訊息內容' }]}
                  >
                    <TextArea
                      rows={14}
                      style={{ fontFamily: 'monospace', fontSize: 12 }}
                      placeholder='[{"type":"text","text":"..."}]'
                    />
                  </Form.Item>
                </>
              )}
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ marginBottom: 8, fontSize: 14 }}>即時預覽</div>
              <div
                style={{
                  height: 380,
                  overflowY: 'auto',
                  border: '1px solid #f0f0f0',
                  borderRadius: 4,
                }}
              >
                {previewContent ? (
                  <FlexPreview content={previewContent} width={280} />
                ) : (
                  <div style={{ padding: 16, color: '#999', textAlign: 'center' }}>
                    （尚無訊息內容）
                  </div>
                )}
              </div>
            </div>
          </div>
        </Card>

        <Card title="目標" size="small" style={{ marginBottom: 16 }}>
          <Form.Item name="targetType" label="目標類型">
            <Radio.Group>
              <Radio.Button value="ALL">全部已加好友</Radio.Button>
              <Radio.Button value="TAGS">依標籤</Radio.Button>
              <Radio.Button value="USER_LIST">指定用戶</Radio.Button>
              <Radio.Button value="NARROWCAST">Narrowcast</Radio.Button>
            </Radio.Group>
          </Form.Item>

          {targetType === 'NARROWCAST' && (
            <Alert
              type="info"
              showIcon
              message="Narrowcast 模式："
              description="走 LINE 官方大規模分發 API，由 LINE 平台自管 audience。適合 >100K 用戶；不需自管批次，但無 per-user 成敗追蹤（僅彙總統計）。Phase 6 預設推送給全部已加好友。"
              style={{ marginBottom: 8 }}
            />
          )}

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

          {targetType === 'USER_LIST' && (
            <>
              <Form.Item
                name="userIds"
                label="指定用戶"
                rules={[
                  {
                    validator: (_, v: number[] | undefined) =>
                      v && v.length > 0
                        ? Promise.resolve()
                        : Promise.reject(new Error('請選擇至少一位用戶')),
                  },
                ]}
                extra={
                  initialUsers.length > 0
                    ? `已從「LINE 用戶」頁帶入 ${initialUsers.length} 位，可繼續搜尋加減。`
                    : '輸入暱稱或 LINE userId 搜尋；可多選。'
                }
              >
                <LineUserPicker initialUsers={initialUsers} />
              </Form.Item>
              <Alert
                type="info"
                showIcon
                message="指定用戶自動套用 Push API"
                description="少量指定收件人通常需要 per-user 結果（誰收到 / 誰退追），故鎖定 Push 模式；如需大量整批送請改用「全部已加好友」或「依標籤」並挑 Multicast。"
                style={{ marginBottom: 8 }}
              />
            </>
          )}

          {targetType !== 'NARROWCAST' && (
            <Form.Item
              name="apiMode"
              label="LINE API 模式"
              tooltip="Push：逐一發送、能取得 per-user 成敗（200/4xx），預設值。Multicast：批量發送（500 人/批）、LINE 僅回整批 200，無 per-user 結果。"
            >
              <Radio.Group disabled={targetType === 'USER_LIST'}>
                <Radio.Button value="PUSH">Push API（精準）</Radio.Button>
                <Radio.Button value="MULTICAST">Multicast API（批量）</Radio.Button>
              </Radio.Group>
            </Form.Item>
          )}
        </Card>

        <Card title="排程" size="small" style={{ marginBottom: 16 }}>
          <Form.Item
            name="scheduledAt"
            label="排程發送時間（留空 = 立即送出）"
            help="到時間時系統會自動觸發送出"
          >
            <DatePicker
              showTime
              format="YYYY-MM-DD HH:mm"
              placeholder="選擇日期與時間"
              disabledDate={(d) => d.isBefore(dayjs().startOf('day'))}
              style={{ width: 280 }}
            />
          </Form.Item>
        </Card>

        <Card title="預估與執行" size="small">
          <Space size="large" align="start" wrap>
            <Button loading={estimating} onClick={handleEstimate}>
              預估收件人數
            </Button>
            {estimate && (
              <>
                <Statistic title="收件人數" value={estimate.total} />
                <Statistic title="批次數（500 人/批）" value={estimate.chunks} />
              </>
            )}
          </Space>

          <Divider />

          <Space>
            <Button onClick={() => setTestOpen(true)}>測試發送</Button>
            <Button onClick={handleSaveDraft} loading={submitting}>儲存草稿</Button>
            <Button type="primary" onClick={handleSubmit} loading={submitting}>
              直接送出
            </Button>
          </Space>

          {draftId && (
            <Text type="secondary" style={{ display: 'block', marginTop: 8 }}>
              草稿 ID：{draftId}
            </Text>
          )}
        </Card>
      </Form>

      <Modal
        title="測試發送"
        open={testOpen}
        onOk={handleTestSend}
        onCancel={() => setTestOpen(false)}
        okText="發送"
        cancelText="取消"
      >
        <p style={{ color: '#888' }}>會用 pushMessage 發給單一 LINE userId，不影響任務統計。</p>
        <Input
          placeholder="LINE userId（U 開頭 33 字）"
          value={testUserId}
          onChange={(e) => setTestUserId(e.target.value)}
        />
      </Modal>
    </div>
  )
}
