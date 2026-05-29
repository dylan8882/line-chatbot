/**
 * Dashboard 統計與用量監控相關 API
 */
import client from './client'
import type { ApiResponse, DailyStats, MessageLog, PageResponse, UsageStats } from '../types'

/**
 * 取得今日統計摘要（訊息數、QA 命中率、AI 回覆數、平均延遲）
 */
export const getStats = () =>
  client.get<ApiResponse<UsageStats>>('/dashboard/stats')

/**
 * 取得近 N 天每日用量趨勢資料
 */
export const getUsage = (days = 7) =>
  client.get<ApiResponse<DailyStats[]>>(`/dashboard/usage?days=${days}`)

/**
 * 分頁取得訊息紀錄
 */
export const getLogs = (page = 0, size = 20) =>
  client.get<ApiResponse<PageResponse<MessageLog>>>(
    `/dashboard/logs?page=${page}&size=${size}`
  )
