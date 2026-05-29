/**
 * LINE 用戶管理 API
 */
import client from './client'
import type {
  ApiResponse,
  BulkTagRequest,
  LineUser,
  LineUserStatus,
  PageResponse,
} from '../types'

export interface LineUserQuery {
  keyword?: string
  status?: LineUserStatus
  tagIds?: number[]
  page?: number
  size?: number
}

export const getLineUsers = (q: LineUserQuery = {}) => {
  const params = new URLSearchParams()
  if (q.keyword) params.set('keyword', q.keyword)
  if (q.status) params.set('status', q.status)
  if (q.tagIds && q.tagIds.length) params.set('tagIds', q.tagIds.join(','))
  params.set('page', String(q.page ?? 0))
  params.set('size', String(q.size ?? 20))
  return client.get<ApiResponse<PageResponse<LineUser>>>(`/line-users?${params}`)
}

export const getLineUser = (id: number) =>
  client.get<ApiResponse<LineUser>>(`/line-users/${id}`)

/** 指派標籤到單一用戶（覆寫式） */
export const assignTags = (id: number, tagIds: number[]) =>
  client.post<ApiResponse<LineUser>>(`/line-users/${id}/tags`, { tagIds })

/** 批量貼 / 移除標籤 */
export const bulkTag = (req: BulkTagRequest) =>
  client.post<ApiResponse<{ affected: number }>>('/line-users/bulk-tag', req)
