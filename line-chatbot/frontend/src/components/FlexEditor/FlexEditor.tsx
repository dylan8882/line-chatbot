/**
 * Flex / Text / Image 訊息編輯器（雙欄）：
 *  左：JSON 編輯區 + 工具列（載入預設 / 匯入 / 匯出 / Flex Simulator 連結）
 *  右：即時預覽
 *
 * 為了維持 modal 內可滾動且兩邊高度一致，外層元件決定容器尺寸；
 * 本元件只負責內部排版（Row + Col）。
 */
import { useRef, useState } from 'react'
import { Alert, Button, Input, Space, Tooltip, Upload, message } from 'antd'
import {
  DownloadOutlined,
  EyeOutlined,
  FileTextOutlined,
  ImportOutlined,
} from '@ant-design/icons'
import type { UploadProps } from 'antd'
import FlexPreview from './FlexPreview'
import PresetPicker from './PresetPicker'
import type { Preset } from './presets'

const { TextArea } = Input

interface Props {
  value: string
  onChange: (value: string) => void
  /** 載入預設時可同步切換訊息類型 */
  onPresetTypeChange?: (type: Preset['messageType']) => void
  /** 編輯區高度（預設 380） */
  height?: number
}

export default function FlexEditor({ value, onChange, onPresetTypeChange, height = 380 }: Props) {
  const [presetOpen, setPresetOpen] = useState(false)
  const fileRef = useRef<HTMLInputElement>(null)

  const jsonValid = (() => {
    try {
      const parsed = JSON.parse(value)
      if (!Array.isArray(parsed)) return '訊息內容必須為陣列（[ ... ]）'
      if (parsed.length === 0) return '陣列不可為空'
      if (parsed.length > 5) return 'LINE 單次最多 5 則訊息'
      return null
    } catch (e) {
      return 'JSON 格式錯誤：' + (e as Error).message
    }
  })()

  const handlePick = (p: Preset) => {
    onChange(p.content)
    onPresetTypeChange?.(p.messageType)
    setPresetOpen(false)
  }

  const handleExport = () => {
    try {
      const blob = new Blob([value], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `line-message-${Date.now()}.json`
      a.click()
      URL.revokeObjectURL(url)
    } catch {
      message.error('匯出失敗')
    }
  }

  /** 上傳 JSON 檔，讀取後直接套用 */
  const uploadProps: UploadProps = {
    accept: '.json,application/json',
    beforeUpload: (file) => {
      const reader = new FileReader()
      reader.onload = () => {
        const text = String(reader.result ?? '')
        try {
          const parsed = JSON.parse(text)
          if (!Array.isArray(parsed)) {
            message.error('檔案內容必須為 JSON 陣列')
            return
          }
          onChange(JSON.stringify(parsed, null, 2))
          message.success('已匯入')
        } catch {
          message.error('檔案不是有效的 JSON')
        }
      }
      reader.readAsText(file)
      return false // 不真的上傳，只在前端解析
    },
    showUploadList: false,
  }

  return (
    <div>
      <Space wrap style={{ marginBottom: 8 }}>
        <Button icon={<FileTextOutlined />} onClick={() => setPresetOpen(true)}>
          載入預設
        </Button>
        <Upload {...uploadProps}>
          <Button icon={<ImportOutlined />}>匯入 JSON</Button>
        </Upload>
        <Button icon={<DownloadOutlined />} onClick={handleExport} disabled={!!jsonValid}>
          匯出 JSON
        </Button>
        <Tooltip title="在 LINE 官方 Simulator 預覽 / 編輯">
          <a href="https://developers.line.biz/flex-simulator/" target="_blank" rel="noreferrer">
            <Button icon={<EyeOutlined />} type="link">
              Flex Simulator ↗
            </Button>
          </a>
        </Tooltip>
      </Space>

      <div style={{ display: 'flex', gap: 12 }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <TextArea
            value={value}
            onChange={(e) => onChange(e.target.value)}
            style={{
              fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
              fontSize: 12,
              height,
              resize: 'none',
            }}
          />
          {jsonValid && (
            <Alert
              type="error"
              showIcon
              message={jsonValid}
              style={{ marginTop: 8 }}
            />
          )}
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div
            style={{
              height,
              overflowY: 'auto',
              border: '1px solid #f0f0f0',
              borderRadius: 4,
            }}
          >
            <FlexPreview content={value} width={300} />
          </div>
        </div>
      </div>

      <PresetPicker
        open={presetOpen}
        onPick={handlePick}
        onCancel={() => setPresetOpen(false)}
      />
    </div>
  )
}
