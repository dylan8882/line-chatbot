/**
 * LINE Messaging API 串接設定頁面
 *
 * 欄位對應 LINE Developers Console：
 *  - Channel ID / Channel Secret → Basic settings 頁籤
 *  - Channel Access Token        → Messaging API 頁籤
 *  - Webhook URL                 → 需填入 LINE Developers Console > Messaging API > Webhook settings
 */
import { useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Divider,
  Form,
  Input,
  Space,
  Switch,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import {
  CheckCircleOutlined,
  CopyOutlined,
  ExclamationCircleOutlined,
  EyeInvisibleOutlined,
  EyeOutlined,
  LinkOutlined,
  SaveOutlined,
  SyncOutlined,
} from '@ant-design/icons'
import { getLineSettings, saveLineSettings, verifyAccessToken } from '../api/lineSettings'
import type { LineChannelConfig, LineChannelConfigUpdate } from '../types'

const { Title, Text, Paragraph } = Typography

interface FormValues {
  channelId: string
  channelSecret: string
  channelAccessToken: string
  serverBaseUrl: string
  webhookEnabled: boolean
  autoReplyEnabled: boolean
  greetingEnabled: boolean
  greetingMessage: string
}

export default function LineSettings() {
  const [form] = Form.useForm<FormValues>()
  const [config, setConfig] = useState<LineChannelConfig | null>(null)
  const [loading, setLoading] = useState(false)
  const [saveLoading, setSaveLoading] = useState(false)
  const [verifyLoading, setVerifyLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [showSecret, setShowSecret] = useState(false)
  const [showToken, setShowToken] = useState(false)

  useEffect(() => {
    fetchConfig()
  }, [])

  async function fetchConfig() {
    setLoading(true)
    setError(null)
    try {
      const res = await getLineSettings()
      const data = res.data.data
      setConfig(data)
      form.setFieldsValue({
        channelId: data.channelId ?? '',
        channelSecret: '',       // 不預填敏感欄位，placeholder 顯示遮罩
        channelAccessToken: '',  // 同上
        serverBaseUrl: data.serverBaseUrl ?? '',
        webhookEnabled: data.webhookEnabled,
        autoReplyEnabled: data.autoReplyEnabled,
        greetingEnabled: data.greetingEnabled,
        greetingMessage: data.greetingMessage ?? '',
      })
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '載入設定失敗')
    } finally {
      setLoading(false)
    }
  }

  async function handleSave(values: FormValues) {
    setSaveLoading(true)
    try {
      const payload: LineChannelConfigUpdate = {
        channelId: values.channelId || null,
        // 空字串 = 不更新（保留原值）；有填值才送出
        channelSecret: values.channelSecret || null,
        channelAccessToken: values.channelAccessToken || null,
        serverBaseUrl: values.serverBaseUrl || null,
        webhookEnabled: values.webhookEnabled,
        autoReplyEnabled: values.autoReplyEnabled,
        greetingEnabled: values.greetingEnabled,
        // 永遠傳值：空字串會被後端視為「清除」，有內容才存
        greetingMessage: values.greetingMessage ?? '',
      }
      const res = await saveLineSettings(payload)
      setConfig(res.data.data)
      // 儲存後清除敏感欄位輸入
      form.setFieldsValue({ channelSecret: '', channelAccessToken: '' })
      message.success('設定已儲存')
    } catch (err: unknown) {
      message.error(err instanceof Error ? err.message : '儲存失敗')
    } finally {
      setSaveLoading(false)
    }
  }

  async function handleVerify() {
    setVerifyLoading(true)
    try {
      const res = await verifyAccessToken()
      if (res.data.success) {
        message.success(res.data.message)
      } else {
        message.error(res.data.message)
      }
    } catch (err: unknown) {
      message.error(err instanceof Error ? err.message : '驗證失敗')
    } finally {
      setVerifyLoading(false)
    }
  }

  function handleCopyWebhook() {
    const url = config?.webhookUrl
    if (!url) {
      message.warning('請先填寫「伺服器 Base URL」再複製 Webhook URL')
      return
    }
    navigator.clipboard.writeText(url).then(() => message.success('Webhook URL 已複製'))
  }

  const configuredTag = config?.isConfigured ? (
    <Tag icon={<CheckCircleOutlined />} color="success">已完成設定</Tag>
  ) : (
    <Tag icon={<ExclamationCircleOutlined />} color="warning">尚未完成設定</Tag>
  )

  return (
    <div style={{ maxWidth: 760 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 8 }}>
        <Title level={4} style={{ margin: 0 }}>LINE Messaging API 串接設定</Title>
        {config && configuredTag}
      </div>
      <Paragraph type="secondary" style={{ marginBottom: 24 }}>
        請至{' '}
        <a href="https://developers.line.biz/console/" target="_blank" rel="noreferrer">
          LINE Developers Console <LinkOutlined />
        </a>{' '}
        取得以下資訊並填入。
      </Paragraph>

      {error && (
        <Alert message={error} type="error" showIcon style={{ marginBottom: 16 }} />
      )}

      {/* ── Webhook URL 提示區塊 ─────────────────────────────────────── */}
      {config?.webhookUrl && (
        <Card
          size="small"
          style={{ marginBottom: 24, background: '#f6ffed', borderColor: '#b7eb8f' }}
        >
          <Space direction="vertical" size={4} style={{ width: '100%' }}>
            <Text strong>Webhook URL（請填入 LINE Developers Console）</Text>
            <Space>
              <Text code copyable={{ text: config.webhookUrl }}>
                {config.webhookUrl}
              </Text>
              <Button
                size="small"
                icon={<CopyOutlined />}
                onClick={handleCopyWebhook}
              >
                複製
              </Button>
            </Space>
            <Text type="secondary" style={{ fontSize: 12 }}>
              LINE Developers Console → 選擇 Channel → Messaging API →
              Webhook settings → Webhook URL
            </Text>
          </Space>
        </Card>
      )}

      <Form
        form={form}
        layout="vertical"
        onFinish={handleSave}
        initialValues={{
          webhookEnabled: true,
          autoReplyEnabled: false,
          greetingEnabled: true,
          greetingMessage: '',
        }}
      >
        {/* ── Basic settings ──────────────────────────────────────────── */}
        <Card
          title={
            <Space>
              <span>頻道基本資訊</span>
              <Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}>
                LINE Developers Console → Basic settings
              </Text>
            </Space>
          }
          style={{ marginBottom: 16 }}
          loading={loading}
        >
          <Form.Item
            label="Channel ID"
            name="channelId"
            extra="數字字串，例如：1234567890"
          >
            <Input placeholder="請輸入 Channel ID" maxLength={50} />
          </Form.Item>

          <Form.Item
            label={
              <Space>
                <span>Channel Secret</span>
                {config?.channelSecretMasked && (
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    目前值：{config.channelSecretMasked}
                  </Text>
                )}
              </Space>
            }
            name="channelSecret"
            extra="用於驗證 Webhook 請求的簽章，留空表示不更新。"
          >
            <Input.Password
              placeholder={config?.channelSecretMasked ?? '請輸入 Channel Secret'}
              visibilityToggle={{
                visible: showSecret,
                onVisibleChange: setShowSecret,
              }}
              iconRender={(visible) =>
                visible ? <EyeOutlined /> : <EyeInvisibleOutlined />
              }
            />
          </Form.Item>
        </Card>

        {/* ── Messaging API settings ──────────────────────────────────── */}
        <Card
          title={
            <Space>
              <span>Messaging API 設定</span>
              <Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}>
                LINE Developers Console → Messaging API
              </Text>
            </Space>
          }
          style={{ marginBottom: 16 }}
          loading={loading}
        >
          <Form.Item
            label={
              <Space>
                <span>Channel Access Token</span>
                {config?.channelAccessTokenMasked && (
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    目前值：{config.channelAccessTokenMasked}
                  </Text>
                )}
              </Space>
            }
            name="channelAccessToken"
            extra={
              <span>
                Long-lived token，用於呼叫 Messaging API 傳送訊息。留空表示不更新。
                <br />
                路徑：Messaging API → Channel access token（long-lived）→ Issue
              </span>
            }
          >
            <Input.Password
              placeholder={config?.channelAccessTokenMasked ?? '請輸入 Channel Access Token'}
              visibilityToggle={{
                visible: showToken,
                onVisibleChange: setShowToken,
              }}
              iconRender={(visible) =>
                visible ? <EyeOutlined /> : <EyeInvisibleOutlined />
              }
            />
          </Form.Item>

          <Divider style={{ margin: '8px 0 16px' }} />

          <Form.Item
            label="伺服器 Base URL"
            name="serverBaseUrl"
            extra="您的伺服器對外公開 URL，例如：https://example.com（自動產生 Webhook URL）"
          >
            <Input
              placeholder="https://example.com"
              addonBefore="https://"
              onChange={(e) => {
                // 即時更新預覽
                const raw = e.target.value.trim()
                const base = raw.startsWith('http') ? raw : `https://${raw}`
                setConfig((prev) =>
                  prev ? { ...prev, webhookUrl: base ? base.replace(/\/$/, '') + '/webhook' : null } : prev
                )
              }}
            />
          </Form.Item>

          <Form.Item label="Webhook 啟用" name="webhookEnabled" valuePropName="checked">
            <Switch
              checkedChildren="啟用"
              unCheckedChildren="停用"
            />
          </Form.Item>
        </Card>

        {/* ── LINE OA 行為設定 ─────────────────────────────────────────── */}
        <Card
          title={
            <Space>
              <span>LINE OA 行為設定</span>
              <Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}>
                LINE Official Account Manager → 回應設定
              </Text>
            </Space>
          }
          style={{ marginBottom: 24 }}
          loading={loading}
        >
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message="使用 Webhook 時，建議關閉「自動回覆訊息」，避免與 Chatbot 回覆重複。"
          />

          <Form.Item
            label={
              <Tooltip title="LINE OA Manager → 回應設定 → 自動回覆訊息">
                <span>自動回覆訊息 <ExclamationCircleOutlined style={{ color: '#faad14' }} /></span>
              </Tooltip>
            }
            name="autoReplyEnabled"
            valuePropName="checked"
            extra="使用 Webhook 時建議關閉，避免 LINE OA 內建回覆與 Chatbot 回覆衝突。"
          >
            <Switch checkedChildren="啟用" unCheckedChildren="停用" />
          </Form.Item>

          <Form.Item
            label={
              <Tooltip title="使用者加入好友時，Follow webhook 處理後 push 此訊息">
                <span>加入好友歡迎訊息（Greeting）</span>
              </Tooltip>
            }
            name="greetingEnabled"
            valuePropName="checked"
            extra="開啟後，新加好友時系統會 push 下方訊息（純文字）。"
          >
            <Switch checkedChildren="啟用" unCheckedChildren="停用" />
          </Form.Item>

          <Form.Item
            label="歡迎訊息內容"
            name="greetingMessage"
            extra="純文字，最多 500 字；清空後儲存代表停用文字內容（即使 Greeting 開關仍啟用）。"
            rules={[{ max: 500, message: '訊息最多 500 字' }]}
          >
            <Input.TextArea
              rows={4}
              placeholder="例：嗨～感謝加入我們！輸入「你好」可以開始對話喔！"
              maxLength={500}
              showCount
            />
          </Form.Item>
        </Card>

        {/* ── 操作按鈕 ─────────────────────────────────────────────────── */}
        <Space>
          <Button
            type="primary"
            htmlType="submit"
            icon={<SaveOutlined />}
            loading={saveLoading}
          >
            儲存設定
          </Button>
          <Button
            icon={<SyncOutlined />}
            loading={verifyLoading}
            onClick={handleVerify}
            disabled={!config?.isConfigured}
          >
            驗證連線
          </Button>
        </Space>
      </Form>

      {/* ── 目前設定摘要 ─────────────────────────────────────────────── */}
      {config && (
        <Card title="目前設定摘要" style={{ marginTop: 32 }} size="small">
          <Descriptions column={1} size="small">
            <Descriptions.Item label="Channel ID">
              {config.channelId ?? <Text type="secondary">未設定</Text>}
            </Descriptions.Item>
            <Descriptions.Item label="Channel Secret">
              {config.channelSecretMasked ?? <Text type="secondary">未設定</Text>}
            </Descriptions.Item>
            <Descriptions.Item label="Channel Access Token">
              {config.channelAccessTokenMasked ?? <Text type="secondary">未設定</Text>}
            </Descriptions.Item>
            <Descriptions.Item label="Webhook URL">
              {config.webhookUrl ? (
                <Text code>{config.webhookUrl}</Text>
              ) : (
                <Text type="secondary">未設定（請填寫 Base URL）</Text>
              )}
            </Descriptions.Item>
            <Descriptions.Item label="Webhook">
              {config.webhookEnabled
                ? <Tag color="green">啟用</Tag>
                : <Tag color="default">停用</Tag>}
            </Descriptions.Item>
            <Descriptions.Item label="自動回覆訊息">
              {config.autoReplyEnabled
                ? <Tag color="orange">啟用</Tag>
                : <Tag color="green">停用（建議）</Tag>}
            </Descriptions.Item>
            <Descriptions.Item label="加入好友歡迎訊息">
              {config.greetingEnabled
                ? <Tag color="blue">啟用</Tag>
                : <Tag color="default">停用</Tag>}
              {config.greetingMessage && (
                <Text style={{ marginLeft: 8 }} type="secondary">
                  「{config.greetingMessage.length > 40
                    ? config.greetingMessage.slice(0, 40) + '…'
                    : config.greetingMessage}」
                </Text>
              )}
            </Descriptions.Item>
            {config.updatedAt && (
              <Descriptions.Item label="最後更新">
                {new Date(config.updatedAt).toLocaleString('zh-TW')}
              </Descriptions.Item>
            )}
          </Descriptions>
        </Card>
      )}
    </div>
  )
}
