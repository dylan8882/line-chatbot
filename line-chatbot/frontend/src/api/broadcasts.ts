/**
 * 推播任務 API
 */
import client from './client'
import type {
  ApiResponse,
  BroadcastCreateRequest,
  BroadcastEstimate,
  BroadcastStatus,
  BroadcastTask,
  PageResponse,
} from '../types'

export interface BroadcastListQuery {
  status?: BroadcastStatus
  page?: number
  size?: number
}

export const getBroadcasts = (q: BroadcastListQuery = {}) => {
  const params = new URLSearchParams()
  if (q.status) params.set('status', q.status)
  params.set('page', String(q.page ?? 0))
  params.set('size', String(q.size ?? 20))
  return client.get<ApiResponse<PageResponse<BroadcastTask>>>(`/broadcasts?${params}`)
}

export const getBroadcast = (id: number) =>
  client.get<ApiResponse<BroadcastTask>>(`/broadcasts/${id}`)

export const createBroadcast = (data: BroadcastCreateRequest) =>
  client.post<ApiResponse<BroadcastTask>>('/broadcasts', data)

export const estimateBroadcast = (data: BroadcastCreateRequest) =>
  client.post<ApiResponse<BroadcastEstimate>>('/broadcasts/estimate', data)

export const submitBroadcast = (id: number) =>
  client.post<ApiResponse<BroadcastTask>>(`/broadcasts/${id}/submit`)

export const cancelBroadcast = (id: number) =>
  client.post<ApiResponse<BroadcastTask>>(`/broadcasts/${id}/cancel`)

export const testSendBroadcast = (id: number, lineUserId: string) =>
  client.post<ApiResponse<{ requestId: string }>>(`/broadcasts/${id}/test`, { lineUserId })

export const getBroadcastProgress = (id: number) =>
  client.get<ApiResponse<BroadcastTask>>(`/broadcasts/${id}/progress`)
