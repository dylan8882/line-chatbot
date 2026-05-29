/**
 * 帶顏色的標籤 Chip，用於用戶清單與篩選顯示
 */
import { Tag as AntTag } from 'antd'
import type { Tag } from '../../types'

interface TagChipProps {
  tag: Tag
  closable?: boolean
  onClose?: () => void
}

export default function TagChip({ tag, closable, onClose }: TagChipProps) {
  return (
    <AntTag color={tag.color} closable={closable} onClose={onClose}>
      {tag.name}
    </AntTag>
  )
}
