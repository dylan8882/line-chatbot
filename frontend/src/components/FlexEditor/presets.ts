/**
 * Phase 5 — 預設訊息模板庫（4 種起跳）
 *
 * 每個 preset 是一個 LINE messages 陣列，可直接放進 message-template.content。
 * 圖片 URL 用 placeholder.com / picsum.photos，正式使用時請替換成自有 CDN。
 */

import type { MessageType } from '../../types'

export interface Preset {
  key: string
  name: string
  description: string
  messageType: MessageType
  /** LINE messages 陣列 JSON 字串 */
  content: string
}

const greeting = [
  {
    type: 'flex',
    altText: '新春問候',
    contents: {
      type: 'bubble',
      hero: {
        type: 'image',
        // 紅包 + 金幣，主題明確的新春元素（Unsplash 公共圖庫）
        url: 'https://images.unsplash.com/photo-1642860993104-1ffed34d4170?w=1024&q=80',
        size: 'full',
        aspectRatio: '20:13',
        aspectMode: 'cover',
      },
      body: {
        type: 'box',
        layout: 'vertical',
        spacing: 'md',
        contents: [
          { type: 'text', text: '新春快樂', weight: 'bold', size: 'xl', color: '#c0392b' },
          {
            type: 'text',
            text: '感謝您一路以來的支持，祝您新的一年身體健康、闔家平安！',
            wrap: true,
            size: 'sm',
            color: '#555555',
          },
        ],
      },
    },
  },
]

const product = [
  {
    type: 'flex',
    altText: '商品介紹',
    contents: {
      type: 'bubble',
      hero: {
        type: 'image',
        // 美式咖啡杯，符合預設模板「經典美式咖啡」主題（Unsplash 公共圖庫）
        url: 'https://images.unsplash.com/photo-1497515114629-f71d768fd07c?w=1024&q=80',
        size: 'full',
        aspectRatio: '3:2',
        aspectMode: 'cover',
      },
      body: {
        type: 'box',
        layout: 'vertical',
        spacing: 'sm',
        contents: [
          { type: 'text', text: '經典美式咖啡', weight: 'bold', size: 'xl' },
          {
            type: 'box',
            layout: 'baseline',
            spacing: 'sm',
            contents: [
              { type: 'text', text: 'NT$', size: 'sm', color: '#999999' },
              { type: 'text', text: '120', size: 'xxl', weight: 'bold', color: '#000000' },
              { type: 'text', text: '/ 杯', size: 'sm', color: '#999999' },
            ],
          },
          { type: 'separator' },
          {
            type: 'text',
            text: '精選阿拉比卡豆，深烘焙帶有焦糖香氣。',
            wrap: true,
            size: 'sm',
            color: '#666666',
          },
        ],
      },
      footer: {
        type: 'box',
        layout: 'vertical',
        spacing: 'sm',
        contents: [
          {
            type: 'button',
            style: 'primary',
            color: '#5B4636',
            action: { type: 'uri', label: '立即購買', uri: 'https://example.com/product' },
          },
        ],
      },
    },
  },
]

const coupon = [
  {
    type: 'flex',
    altText: '優惠券',
    contents: {
      type: 'bubble',
      styles: {
        body: { backgroundColor: '#fff3e0' },
        footer: { backgroundColor: '#fff3e0' },
      },
      body: {
        type: 'box',
        layout: 'vertical',
        spacing: 'md',
        contents: [
          {
            type: 'text',
            text: '🎉 限時優惠券',
            weight: 'bold',
            size: 'xl',
            color: '#d35400',
            align: 'center',
          },
          {
            type: 'text',
            text: '全館 9 折',
            weight: 'bold',
            size: '3xl',
            color: '#e67e22',
            align: 'center',
          },
          { type: 'separator', color: '#e67e22' },
          {
            type: 'box',
            layout: 'vertical',
            spacing: 'xs',
            contents: [
              { type: 'text', text: '使用代碼', size: 'sm', color: '#7f8c8d', align: 'center' },
              {
                type: 'text',
                text: 'SAVE10',
                weight: 'bold',
                size: 'xxl',
                color: '#2c3e50',
                align: 'center',
              },
              {
                type: 'text',
                text: '有效期限：2026/12/31',
                size: 'xs',
                color: '#95a5a6',
                align: 'center',
              },
            ],
          },
        ],
      },
      footer: {
        type: 'box',
        layout: 'vertical',
        contents: [
          {
            type: 'button',
            style: 'primary',
            color: '#e67e22',
            action: { type: 'uri', label: '立即使用', uri: 'https://example.com/coupon' },
          },
        ],
      },
    },
  },
]

const event = [
  {
    type: 'flex',
    altText: '活動公告',
    contents: {
      type: 'bubble',
      hero: {
        type: 'image',
        url: 'https://picsum.photos/seed/event/1024/512',
        size: 'full',
        aspectRatio: '20:13',
        aspectMode: 'cover',
      },
      body: {
        type: 'box',
        layout: 'vertical',
        spacing: 'md',
        contents: [
          { type: 'text', text: '春季新品發表會', weight: 'bold', size: 'xl' },
          {
            type: 'box',
            layout: 'vertical',
            spacing: 'sm',
            contents: [
              {
                type: 'box',
                layout: 'baseline',
                spacing: 'sm',
                contents: [
                  { type: 'text', text: '日期', size: 'sm', color: '#aaaaaa', flex: 1 },
                  { type: 'text', text: '2026/06/15 (六)', size: 'sm', color: '#555555', flex: 4 },
                ],
              },
              {
                type: 'box',
                layout: 'baseline',
                spacing: 'sm',
                contents: [
                  { type: 'text', text: '地點', size: 'sm', color: '#aaaaaa', flex: 1 },
                  { type: 'text', text: '台北信義區ATT 4 樓', size: 'sm', color: '#555555', flex: 4 },
                ],
              },
              {
                type: 'box',
                layout: 'baseline',
                spacing: 'sm',
                contents: [
                  { type: 'text', text: '時間', size: 'sm', color: '#aaaaaa', flex: 1 },
                  { type: 'text', text: '14:00 ~ 17:00', size: 'sm', color: '#555555', flex: 4 },
                ],
              },
            ],
          },
        ],
      },
      footer: {
        type: 'box',
        layout: 'vertical',
        spacing: 'sm',
        contents: [
          {
            type: 'button',
            style: 'primary',
            color: '#1abc9c',
            action: { type: 'uri', label: '立即報名', uri: 'https://example.com/event' },
          },
          {
            type: 'button',
            style: 'link',
            action: { type: 'uri', label: '查看更多', uri: 'https://example.com/event/detail' },
          },
        ],
      },
    },
  },
]

const simpleText = [{ type: 'text', text: '哈囉！這是一則 LINE 推播訊息。' }]

export const PRESETS: Preset[] = [
  {
    key: 'simple-text',
    name: '簡單文字',
    description: '單一段文字，最快上手',
    messageType: 'TEXT',
    content: JSON.stringify(simpleText, null, 2),
  },
  {
    key: 'greeting',
    name: '問候',
    description: '節慶 / 新春問候，含 hero 圖片與祝賀文字',
    messageType: 'FLEX',
    content: JSON.stringify(greeting, null, 2),
  },
  {
    key: 'product',
    name: '商品介紹',
    description: '商品卡：圖片、名稱、價格、購買按鈕',
    messageType: 'FLEX',
    content: JSON.stringify(product, null, 2),
  },
  {
    key: 'coupon',
    name: '優惠券',
    description: '帶顏色背景的優惠券，含折扣代碼',
    messageType: 'FLEX',
    content: JSON.stringify(coupon, null, 2),
  },
  {
    key: 'event',
    name: '活動公告',
    description: '活動：圖片、日期/地點/時間欄位、報名按鈕',
    messageType: 'FLEX',
    content: JSON.stringify(event, null, 2),
  },
]

export function findPreset(key: string): Preset | undefined {
  return PRESETS.find((p) => p.key === key)
}
