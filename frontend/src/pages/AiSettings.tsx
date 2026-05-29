/**
 * AI 串接設定頁（Phase 8）
 *
 * - 主開關：關閉後 AI 不會被呼叫，OA 只走 QA 規則
 * - apiKey / baseUrl / model 採「DB 為主，.env 為 fallback」
 *   後台填寫 → 存 DB；清空 → 退回 .env 預設值
 * - apiKey 顯示時遮罩，編輯時留空 = 不更新
 */
import { useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  Space,
  Switch,
  Tag,
  Typography,
  message,
} from 'antd'
import {
  CheckCircleOutlined,
  ExclamationCircleOutlined,
  SaveOutlined,
  SyncOutlined,
} from '@ant-design/icons'
import { getAiSettings, saveAiSettings } from '../api/aiSettings'
import type { AiConfig, AiConfigUpdate } from '../types'

const { Title, Paragraph } = Typography

interface FormValues {
  enabled: boolean
  apiKey: string
  baseUrl: string
  model: string
}

const DEFAULT_BASE_URL = 'https://api.openai.com/v1'
const DEFAULT_MODEL = 'gpt-4o-mini'

export default function AiSettings() {
  const [form] = Form.useForm<FormValues>()
  const [config, setConfig] = useState<AiConfig | null>(null)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchConfig()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function fetchConfig() {
    setLoading(true)
    setError(null)
    try {
      const res = await getAiSettings()
      const data = res.data.data
      setConfig(data)
      form.setFieldsValue({
        enabled: data.enabled,
        apiKey: '', // 不預填敏感欄位
        baseUrl: data.baseUrl ?? '',
        model: data.model ?? '',
      })
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '載入失敗')
    } finally {
      setLoading(false)
    }
  }

  async function handleSave(values: FormValues) {
    setSaving(true)
    try {
      // null = 不更新；"" = 清除（後端 service 邏輯）
      const payload: AiConfigUpdate = {
        enabled: values.enabled,
        // apiKey 空字串 → 「保留不變」UX 比較直覺：使用者要清除可特地按下方「清除 key」按鈕
        // 此處只在有輸入時送 server。
        apiKey: values.apiKey ? values.apiKey : null,
        baseUrl: values.baseUrl,
        model: values.model,
      }
      await saveAiSettings(payload)
      message.success('AI 設定已儲存')
      await fetchConfig()
    } catch (err: unknown) {
      message.error(err instanceof Error ? err.message : '儲存失敗')
    } finally {
      setSaving(false)
    }
  }

  async function handleClearApiKey() {
    try {
      await saveAiSettings({ apiKey: '' })
      message.success('已清除 API key，將 fallback 至 .env')
      await fetchConfig()
    } catch (err: unknown) {
      message.error(err instanceof Error ? err.message : '清除失敗')
    }
  }

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>AI 串接設定</Title>
        <Button icon={<SyncOutlined />} onClick={fetchConfig} loading={loading}>
          重新整理
        </Button>
      </div>

      {error && <Alert type="error" message={error} showIcon style={{ marginBottom: 16 }} />}

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="設定來源優先順序"
        description={
          <Paragraph style={{ marginBottom: 0 }}>
            DB 欄位有值時以 DB 為主；清空後 fallback 至 <code>.env</code> 環境變數。
            關閉「啟用 AI」後，OA 將只走 QA 規則回覆。
          </Paragraph>
        }
      />

      <Card title="目前狀態" size="small" style={{ marginBottom: 16 }} loading={loading}>
        <Descriptions size="small" column={2}>
          <Descriptions.Item label="主開關">
            {config?.enabled ? (
              <Tag color="success" icon={<CheckCircleOutlined />}>已啟用</Tag>
            ) : (
              <Tag color="default" icon={<ExclamationCircleOutlined />}>已停用</Tag>
            )}
          </Descriptions.Item>
          <Descriptions.Item label="廠商">{config?.provider ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="API Key">
            {config?.apiKeyMasked ? (
              <span>
                <Tag color="blue">{config.apiKeyMasked}</Tag>
                <Tag color={config.effectiveSource === 'DB' ? 'green' : 'orange'}>
                  來源：{config.effectiveSource}
                </Tag>
              </span>
            ) : (
              <Tag color="red">未設定</Tag>
            )}
          </Descriptions.Item>
          <Descriptions.Item label="是否就緒">
            {config?.isConfigured ? (
              <Tag color="success">是</Tag>
            ) : (
              <Tag color="warning">否（缺 API key）</Tag>
            )}
          </Descriptions.Item>
          <Descriptions.Item label="Base URL" span={2}>
            <code>{config?.baseUrl || DEFAULT_BASE_URL + '（env 預設）'}</code>
          </Descriptions.Item>
          <Descriptions.Item label="Model">
            <code>{config?.model || DEFAULT_MODEL + '（env 預設）'}</code>
          </Descriptions.Item>
          <Descriptions.Item label="更新時間">
            {config?.updatedAt ? new Date(config.updatedAt).toLocaleString('zh-TW') : '—'}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Card title="修改設定" size="small" loading={loading}>
        <Form<FormValues>
          form={form}
          layout="vertical"
          onFinish={handleSave}
          initialValues={{ enabled: true }}
        >
          <Form.Item
            name="enabled"
            label="啟用 AI 回覆"
            valuePropName="checked"
            tooltip="關閉後 OA 將不會呼叫 AI；用戶問題若無 QA 規則命中，將不會收到 AI 回覆"
          >
            <Switch checkedChildren="開" unCheckedChildren="關" />
          </Form.Item>

          <Form.Item
            name="apiKey"
            label="API Key"
            tooltip="留空 = 不更新；按「清除 API Key」按鈕可清除（將 fallback 至 .env）"
          >
            <Input.Password
              placeholder={config?.apiKeyMasked ? `目前：${config.apiKeyMasked}（留空不更新）` : 'sk-...'}
              autoComplete="off"
            />
          </Form.Item>

          <Form.Item
            name="baseUrl"
            label="Base URL"
            tooltip="空字串 = 清除，將 fallback 至 .env 的 https://api.openai.com/v1"
          >
            <Input placeholder={DEFAULT_BASE_URL} />
          </Form.Item>

          <Form.Item
            name="model"
            label="Model"
            tooltip="空字串 = 清除，將 fallback 至 .env 的 gpt-4o-mini"
          >
            <Input placeholder={DEFAULT_MODEL} />
          </Form.Item>

          <Space>
            <Button type="primary" htmlType="submit" loading={saving} icon={<SaveOutlined />}>
              儲存
            </Button>
            <Button danger onClick={handleClearApiKey} disabled={!config?.apiKeyMasked}>
              清除 API Key
            </Button>
          </Space>
        </Form>
      </Card>
    </div>
  )
}
