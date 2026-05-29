/**
 * LINE Messaging API 頻道設定相關 API
 */
import client from './client'
import type { ApiResponse, LineChannelConfig, LineChannelConfigUpdate } from '../types'

/** 取得目前頻道設定（敏感欄位遮罩） */
export function getLineSettings() {
  return client.get<ApiResponse<LineChannelConfig>>('/line-settings')
}

/** 儲存頻道設定。channelSecret / channelAccessToken 傳 null 代表不更新 */
export function saveLineSettings(data: LineChannelConfigUpdate) {
  return client.put<ApiResponse<LineChannelConfig>>('/line-settings', data)
}

/** 驗證 Channel Access Token 是否有效 */
export function verifyAccessToken() {
  return client.post<{ success: boolean; message: string }>('/line-settings/verify')
}
