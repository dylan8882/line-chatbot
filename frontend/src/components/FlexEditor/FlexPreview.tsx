/**
 * Flex Message 預覽元件：將 LINE messages 陣列 JSON 渲染成近似 LINE App 的氣泡
 *
 * 支援的訊息類型：
 *  - text：純文字（聊天泡泡）
 *  - image：圖片
 *  - flex：bubble / carousel + box / text / image / button / separator / spacer / icon
 *
 * 不追求 100% 還原（LINE 客戶端有細微 padding / line-height 差異），
 * 目標是讓後台使用者「大致看到內容長相」。
 */
import { useMemo } from 'react'
import { Empty } from 'antd'

interface FlexPreviewProps {
  /** LINE messages 陣列 JSON 字串 */
  content: string
  /** 容器寬度（預設 320，符合 LINE Flex bubble kilo 預設） */
  width?: number
}

/* eslint-disable @typescript-eslint/no-explicit-any */
type AnyFlex = any

// ── 尺寸與顏色對照表 ──────────────────────────────────────────

const TEXT_SIZE: Record<string, string> = {
  nano: '11px',
  xxs: '12px',
  xs: '13px',
  sm: '14px',
  md: '16px',
  lg: '18px',
  xl: '20px',
  xxl: '22px',
  '3xl': '28px',
  '4xl': '32px',
  '5xl': '40px',
}

const SPACING_PX: Record<string, number> = {
  none: 0,
  xs: 2,
  sm: 4,
  md: 8,
  lg: 12,
  xl: 16,
  xxl: 24,
}

const PADDING_PX: Record<string, number> = {
  none: 0,
  xs: 2,
  sm: 4,
  md: 8,
  lg: 12,
  xl: 16,
  xxl: 24,
}

function spacingToPx(v: string | undefined): number {
  if (!v) return 0
  if (v in SPACING_PX) return SPACING_PX[v]
  // 數字 + px 也直接 parse
  const n = parseFloat(v)
  return Number.isFinite(n) ? n : 0
}

function paddingToPx(v: string | undefined): number {
  if (!v) return 0
  if (v in PADDING_PX) return PADDING_PX[v]
  const n = parseFloat(v)
  return Number.isFinite(n) ? n : 0
}

// ── 元件渲染 ─────────────────────────────────────────────────

function FlexText({ node }: { node: AnyFlex }) {
  const style: React.CSSProperties = {
    fontSize: TEXT_SIZE[node.size] ?? '16px',
    fontWeight: node.weight === 'bold' ? 'bold' : 'normal',
    color: node.color ?? '#000',
    textAlign: (node.align as React.CSSProperties['textAlign']) ?? 'start',
    whiteSpace: node.wrap ? 'normal' : 'nowrap',
    wordBreak: 'break-word',
    flex: node.flex !== undefined ? node.flex : undefined,
    lineHeight: 1.4,
  }
  return <div style={style}>{node.text ?? ''}</div>
}

function FlexImage({ node }: { node: AnyFlex }) {
  const aspect = node.aspectRatio ?? '1:1'
  const [w, h] = aspect.split(':').map((s: string) => parseFloat(s))
  const paddingTop = w && h ? `${(h / w) * 100}%` : '100%'

  return (
    <div style={{ width: '100%', position: 'relative', paddingTop }}>
      {node.url ? (
        <img
          src={node.url}
          alt=""
          style={{
            position: 'absolute',
            top: 0,
            left: 0,
            width: '100%',
            height: '100%',
            objectFit: node.aspectMode === 'fit' ? 'contain' : 'cover',
          }}
          onError={(e) => {
            ;(e.currentTarget as HTMLImageElement).style.display = 'none'
          }}
        />
      ) : (
        <div
          style={{
            position: 'absolute',
            inset: 0,
            background: '#eee',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: '#aaa',
            fontSize: 12,
          }}
        >
          圖片
        </div>
      )}
    </div>
  )
}

function FlexButton({ node }: { node: AnyFlex }) {
  const isPrimary = node.style === 'primary'
  const isSecondary = node.style === 'secondary'
  const color = node.color ?? (isPrimary ? '#17c950' : isSecondary ? '#dcdfe5' : 'transparent')
  const textColor = isPrimary ? '#fff' : isSecondary ? '#000' : color === 'transparent' ? '#42659a' : '#000'

  return (
    <div
      style={{
        background: color,
        color: textColor,
        padding: '8px 12px',
        borderRadius: 8,
        textAlign: 'center',
        fontWeight: 500,
        fontSize: 14,
        cursor: 'pointer',
        userSelect: 'none',
      }}
    >
      {node.action?.label ?? 'Button'}
    </div>
  )
}

function FlexSeparator({ node }: { node: AnyFlex }) {
  return <div style={{ height: 1, background: node.color ?? '#e5e5e5', margin: '4px 0' }} />
}

function FlexSpacer({ node }: { node: AnyFlex }) {
  return <div style={{ height: spacingToPx(node.size) || 8 }} />
}

function FlexIcon({ node }: { node: AnyFlex }) {
  return (
    <img
      src={node.url}
      alt=""
      style={{ height: TEXT_SIZE[node.size] ?? '16px', verticalAlign: 'middle' }}
    />
  )
}

function FlexBox({ node }: { node: AnyFlex }) {
  const layout = node.layout ?? 'vertical'
  const isHorizontal = layout === 'horizontal' || layout === 'baseline'
  const contents = (node.contents ?? []) as AnyFlex[]
  const spacing = spacingToPx(node.spacing)

  const containerStyle: React.CSSProperties = {
    display: 'flex',
    flexDirection: isHorizontal ? 'row' : 'column',
    gap: spacing,
    background: node.backgroundColor,
    paddingTop: paddingToPx(node.paddingTop ?? node.paddingAll),
    paddingBottom: paddingToPx(node.paddingBottom ?? node.paddingAll),
    paddingLeft: paddingToPx(node.paddingStart ?? node.paddingAll),
    paddingRight: paddingToPx(node.paddingEnd ?? node.paddingAll),
    alignItems: layout === 'baseline' ? 'baseline' : undefined,
    flex: node.flex !== undefined ? node.flex : undefined,
    width: '100%',
  }

  return (
    <div style={containerStyle}>
      {contents.map((c, i) => (
        <FlexNode key={i} node={c} />
      ))}
    </div>
  )
}

function FlexNode({ node }: { node: AnyFlex }) {
  if (!node || typeof node !== 'object') return null
  switch (node.type) {
    case 'text': return <FlexText node={node} />
    case 'image': return <FlexImage node={node} />
    case 'button': return <FlexButton node={node} />
    case 'separator': return <FlexSeparator node={node} />
    case 'spacer': return <FlexSpacer node={node} />
    case 'icon': return <FlexIcon node={node} />
    case 'box': return <FlexBox node={node} />
    case 'filler': return <div style={{ flex: 1 }} />
    default: return null
  }
}

function FlexBubble({ bubble, width }: { bubble: AnyFlex; width: number }) {
  const styles = bubble.styles ?? {}
  const sectionBg = (s: AnyFlex) => s?.backgroundColor

  return (
    <div
      style={{
        width,
        background: '#fff',
        borderRadius: 12,
        overflow: 'hidden',
        boxShadow: '0 2px 8px rgba(0,0,0,0.08)',
        border: '1px solid #f0f0f0',
        fontFamily: '-apple-system, BlinkMacSystemFont, "PingFang TC", sans-serif',
      }}
    >
      {bubble.header && (
        <div style={{ padding: 12, background: sectionBg(styles.header) }}>
          <FlexNode node={bubble.header} />
        </div>
      )}
      {bubble.hero && (
        <div style={{ background: sectionBg(styles.hero) }}>
          <FlexNode node={bubble.hero} />
        </div>
      )}
      {bubble.body && (
        <div style={{ padding: 16, background: sectionBg(styles.body) }}>
          <FlexNode node={bubble.body} />
        </div>
      )}
      {bubble.footer && (
        <div style={{ padding: 12, background: sectionBg(styles.footer) }}>
          <FlexNode node={bubble.footer} />
        </div>
      )}
    </div>
  )
}

function FlexContainer({ container, width }: { container: AnyFlex; width: number }) {
  if (container?.type === 'carousel') {
    const items = (container.contents ?? []) as AnyFlex[]
    return (
      <div style={{ display: 'flex', gap: 8, overflowX: 'auto', paddingBottom: 8 }}>
        {items.map((b, i) => (
          <FlexBubble key={i} bubble={b} width={width} />
        ))}
      </div>
    )
  }
  return <FlexBubble bubble={container} width={width} />
}

function TextBubble({ text }: { text: string }) {
  return (
    <div
      style={{
        background: '#fff',
        padding: '10px 14px',
        borderRadius: 16,
        maxWidth: 280,
        boxShadow: '0 1px 2px rgba(0,0,0,0.08)',
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-word',
        fontSize: 15,
        lineHeight: 1.5,
      }}
    >
      {text}
    </div>
  )
}

function ImageBubble({ url }: { url: string }) {
  return (
    <div style={{ maxWidth: 280, borderRadius: 12, overflow: 'hidden' }}>
      <img src={url} alt="" style={{ display: 'block', width: '100%' }} />
    </div>
  )
}

export default function FlexPreview({ content, width = 320 }: FlexPreviewProps) {
  const parsed = useMemo(() => {
    try {
      const v = JSON.parse(content)
      return Array.isArray(v) ? v : null
    } catch {
      return null
    }
  }, [content])

  if (!parsed) {
    return <Empty description="無有效 JSON" />
  }

  return (
    <div
      style={{
        background: '#8eb1d1',
        padding: 16,
        borderRadius: 8,
        minHeight: 160,
        display: 'flex',
        flexDirection: 'column',
        gap: 12,
        maxHeight: 600,
        overflowY: 'auto',
      }}
    >
      {parsed.map((msg: AnyFlex, i: number) => {
        if (msg?.type === 'text') return <TextBubble key={i} text={msg.text ?? ''} />
        if (msg?.type === 'image') return <ImageBubble key={i} url={msg.originalContentUrl ?? msg.url} />
        if (msg?.type === 'flex') {
          return <FlexContainer key={i} container={msg.contents} width={width} />
        }
        return (
          <div
            key={i}
            style={{ background: '#fff', padding: 8, borderRadius: 8, fontSize: 12, color: '#999' }}
          >
            （不支援的訊息類型：{msg?.type ?? '未知'}）
          </div>
        )
      })}
    </div>
  )
}
