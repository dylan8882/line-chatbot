/**
 * 「從 Flex Simulator 貼上」助手按鈕。
 *
 * <p>解決使用者常踩的雷：LINE 官方 Flex Simulator 給的 JSON 只到 container 層
 * （type=bubble 或 type=carousel），但 LINE Push API 要的是 messages 陣列
 * （每個元素 type 必須是 text/flex/image/...）。直接複製貼上會 LINE 回 400。
 *
 * <p>這顆按鈕讓使用者明確走「我有一段 Simulator 的 bubble/carousel + altText」的流程，
 * 包成正確的 messages 陣列後塞回主編輯區。
 */
import { useState } from 'react'
import { Alert, Button, Form, Input, Modal, Typography } from 'antd'
import { ImportOutlined } from '@ant-design/icons'

const { TextArea } = Input
const { Text } = Typography

interface Props {
  /**
   * Modal 確認後呼叫，帶入包好的 messages 陣列 JSON 字串。
   * 呼叫方自行決定要「覆蓋」還是「附加」到主編輯區。
   */
  onImport: (wrappedJson: string) => void
  /** 按鈕文字，預設「從 Flex Simulator 貼上」 */
  buttonText?: string
}

interface FormValues {
  containerJson: string
  altText: string
}

export default function ImportFromSimulator({
  onImport,
  buttonText = '從 Flex Simulator 貼上',
}: Props) {
  const [open, setOpen] = useState(false)
  const [form] = Form.useForm<FormValues>()
  const [error, setError] = useState<string | null>(null)

  const handleConfirm = async () => {
    setError(null)
    try {
      const values = await form.validateFields()
      const parsed = JSON.parse(values.containerJson)

      // 驗證頂層必須是物件、且 type 必須是 bubble 或 carousel
      if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
        setError('內容必須是單一物件（{ ... }），不是陣列或其他類型')
        return
      }
      if (parsed.type !== 'bubble' && parsed.type !== 'carousel') {
        setError(
          `頂層 type 必須是 "bubble" 或 "carousel"（目前是 "${parsed.type ?? '無'}"）。` +
            '如果你貼的已經是完整 messages 陣列，請直接貼進主編輯區。',
        )
        return
      }

      // 包成正確的 messages 陣列
      const wrapped = [
        {
          type: 'flex',
          altText: values.altText.trim(),
          contents: parsed,
        },
      ]
      onImport(JSON.stringify(wrapped, null, 2))
      form.resetFields()
      setOpen(false)
    } catch (e) {
      // form.validateFields rejection 已由 antd 處理（會 highlight 欄位）
      if (e instanceof SyntaxError) {
        setError('JSON 格式錯誤：' + e.message)
      }
    }
  }

  return (
    <>
      <Button icon={<ImportOutlined />} onClick={() => setOpen(true)}>
        {buttonText}
      </Button>
      <Modal
        title="從 LINE Flex Simulator 匯入"
        open={open}
        onCancel={() => {
          setOpen(false)
          setError(null)
        }}
        onOk={handleConfirm}
        okText="包好放進編輯區"
        cancelText="取消"
        width={680}
        destroyOnClose
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="什麼時候用這個按鈕？"
          description={
            <span style={{ fontSize: 13 }}>
              LINE 官方 Flex Simulator 複製的 JSON 通常是 <Text code>type: "bubble"</Text>
              {' '}或 <Text code>type: "carousel"</Text> 開頭，要包進
              <Text code>{`[{ type: "flex", altText: "...", contents: { ... } }]`}</Text>
              才能送。把 Simulator 的 JSON 與適合的 altText 填入下方，幫你包好放進編輯區。
            </span>
          }
        />
        <Form form={form} layout="vertical">
          <Form.Item
            name="containerJson"
            label="Flex Simulator 貼上的 JSON（bubble 或 carousel）"
            rules={[{ required: true, message: '請貼上 JSON' }]}
          >
            <TextArea
              rows={12}
              style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: 12 }}
              placeholder={'{\n  "type": "bubble",\n  "body": { ... }\n}'}
            />
          </Form.Item>
          <Form.Item
            name="altText"
            label="altText（聊天列表預覽與通知顯示用，必填）"
            rules={[
              { required: true, message: '請輸入 altText' },
              { max: 400, message: 'altText 最多 400 字' },
            ]}
            extra="LINE 規範必填；聊天列表跟手機推播通知會顯示這段。"
          >
            <Input placeholder="例：新品介紹、優惠通知" maxLength={400} showCount />
          </Form.Item>
          {error && (
            <Alert type="error" showIcon message={error} style={{ marginTop: 8 }} />
          )}
        </Form>
      </Modal>
    </>
  )
}
