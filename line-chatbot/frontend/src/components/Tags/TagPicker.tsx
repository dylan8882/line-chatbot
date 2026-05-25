/**
 * 多選標籤元件（用 Ant Design Select tagMode）
 */
import { Select } from 'antd'
import type { Tag } from '../../types'

interface TagPickerProps {
  tags: Tag[]
  value?: number[]
  onChange?: (value: number[]) => void
  placeholder?: string
  style?: React.CSSProperties
}

export default function TagPicker({
  tags,
  value,
  onChange,
  placeholder = '選擇標籤',
  style,
}: TagPickerProps) {
  return (
    <Select
      mode="multiple"
      allowClear
      style={{ minWidth: 200, ...style }}
      placeholder={placeholder}
      value={value}
      onChange={onChange}
      options={tags.map((t) => ({
        value: t.id,
        label: t.name,
      }))}
      optionRender={(opt) => {
        const tag = tags.find((t) => t.id === opt.value)
        return tag ? (
          <span>
            <span
              style={{
                display: 'inline-block',
                width: 10,
                height: 10,
                borderRadius: 2,
                background: tag.color,
                marginRight: 8,
              }}
            />
            {tag.name}
          </span>
        ) : (
          opt.label
        )
      }}
    />
  )
}
