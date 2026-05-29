/**
 * 問答管理相關 API
 */
import client from './client'
import type { ApiResponse, PageResponse, QAPair } from '../types'

/**
 * 分頁取得所有 QA 問答對
 */
export const getQAList = (page = 0, size = 20) =>
  client.get<ApiResponse<PageResponse<QAPair>>>(`/qa?page=${page}&size=${size}`)

/**
 * 新增一筆 QA 問答對
 */
export const createQA = (data: Partial<QAPair>) =>
  client.post<ApiResponse<QAPair>>('/qa', data)

/**
 * 修改指定 QA 問答對
 */
export const updateQA = (id: number, data: Partial<QAPair>) =>
  client.put<ApiResponse<QAPair>>(`/qa/${id}`, data)

/**
 * 刪除指定 QA 問答對
 */
export const deleteQA = (id: number) =>
  client.delete<ApiResponse<null>>(`/qa/${id}`)

/**
 * 切換指定 QA 問答對的啟用/停用狀態
 */
export const toggleQA = (id: number) =>
  client.patch<ApiResponse<QAPair>>(`/qa/${id}/toggle`)
