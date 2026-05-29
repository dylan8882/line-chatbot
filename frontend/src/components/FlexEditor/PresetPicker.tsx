/**
 * 預設模板選擇器：列出 4 種 starter Flex / Text 模板，點選後填回編輯器。
 */
import { Card, Col, Modal, Row, Tag, Typography } from 'antd'
import { PRESETS, type Preset } from './presets'
import FlexPreview from './FlexPreview'

const { Title, Paragraph } = Typography

const TYPE_COLOR: Record<string, string> = {
  TEXT: 'blue',
  FLEX: 'purple',
  IMAGE: 'gold',
  TEMPLATE: 'green',
}

interface Props {
  open: boolean
  onPick: (preset: Preset) => void
  onCancel: () => void
}

export default function PresetPicker({ open, onPick, onCancel }: Props) {
  return (
    <Modal
      title="選擇預設模板"
      open={open}
      onCancel={onCancel}
      footer={null}
      width={920}
      destroyOnClose
    >
      <Paragraph type="secondary">
        點選任一模板載入後，可在編輯器繼續修改。圖片連結為示範用，請自行替換為自有 CDN。
      </Paragraph>
      <Row gutter={[16, 16]}>
        {PRESETS.map((p) => (
          <Col key={p.key} span={12}>
            <Card
              hoverable
              size="small"
              onClick={() => onPick(p)}
              title={
                <span>
                  {p.name} <Tag color={TYPE_COLOR[p.messageType]}>{p.messageType}</Tag>
                </span>
              }
            >
              <div style={{ minHeight: 240, marginBottom: 8 }}>
                <FlexPreview content={p.content} width={260} />
              </div>
              <Title level={5} style={{ margin: 0 }}>
                {p.name}
              </Title>
              <Paragraph type="secondary" style={{ fontSize: 12, margin: 0 }}>
                {p.description}
              </Paragraph>
            </Card>
          </Col>
        ))}
      </Row>
    </Modal>
  )
}
