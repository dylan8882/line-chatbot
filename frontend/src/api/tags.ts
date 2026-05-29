/**
 * 標籤管理 API
 */
import client from './client'
import type { ApiResponse, Tag, TagInput } from '../types'

export const getTags = () =>
  client.get<ApiResponse<Tag[]>>('/tags')

export const getTag = (id: number) =>
  client.get<ApiResponse<Tag>>(`/tags/${id}`)

export const createTag = (data: TagInput) =>
  client.post<ApiResponse<Tag>>('/tags', data)

export const updateTag = (id: number, data: TagInput) =>
  client.put<ApiResponse<Tag>>(`/tags/${id}`, data)

export const deleteTag = (id: number) =>
  client.delete<ApiResponse<null>>(`/tags/${id}`)
