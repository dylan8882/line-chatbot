/**
 * 訊息模板 API
 */
import client from './client'
import type { ApiResponse, MessageTemplate, MessageTemplateInput } from '../types'

export const getMessageTemplates = () =>
  client.get<ApiResponse<MessageTemplate[]>>('/message-templates')

export const getMessageTemplate = (id: number) =>
  client.get<ApiResponse<MessageTemplate>>(`/message-templates/${id}`)

export const createMessageTemplate = (data: MessageTemplateInput) =>
  client.post<ApiResponse<MessageTemplate>>('/message-templates', data)

export const updateMessageTemplate = (id: number, data: MessageTemplateInput) =>
  client.put<ApiResponse<MessageTemplate>>(`/message-templates/${id}`, data)

export const deleteMessageTemplate = (id: number) =>
  client.delete<ApiResponse<null>>(`/message-templates/${id}`)
