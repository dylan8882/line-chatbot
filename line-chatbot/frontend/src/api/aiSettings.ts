/**
 * AI 串接設定 API
 */
import client from './client'
import type { AiConfig, AiConfigUpdate, ApiResponse } from '../types'

/** 取得 AI 設定（apiKey 遮罩） */
export function getAiSettings() {
  return client.get<ApiResponse<AiConfig>>('/ai-settings')
}

/** 儲存 AI 設定。apiKey 傳 null 代表不更新；傳空字串代表清除 */
export function saveAiSettings(data: AiConfigUpdate) {
  return client.put<ApiResponse<AiConfig>>('/ai-settings', data)
}
