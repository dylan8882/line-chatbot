/**
 * LINE Chatbot 前端共用型別定義
 */

/** 問答對比對方式 */
export type MatchType = 'EXACT' | 'CONTAINS' | 'REGEX'

/** 訊息回應類型 */
export type ResponseType = 'QA' | 'AI' | 'NONE'

/** 問答對實體 */
export interface QAPair {
  id: number
  keyword: string
  answer: string
  isActive: boolean
  priority: number
  matchType: MatchType
  createdAt: string
  updatedAt: string
}

/** 登入請求 */
export interface LoginRequest {
  username: string
  password: string
}

/** 登入回應 */
export interface LoginResponse {
  token: string
  tokenType: string
  expiresIn: number
  username: string
  role: string
}

/** 統一 API 回應格式 */
export interface ApiResponse<T> {
  success: boolean
  data: T
  message: string
  error?: string
}

/** 分頁回應格式 */
export interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

/** 每日統計資料 */
export interface DailyStats {
  date: string
  messageCount: number
  qaCount: number
  aiCount: number
}

/** 用量統計摘要 */
export interface UsageStats {
  totalMessages: number
  qaHits: number
  aiReplies: number
  noReply: number
  qaHitRate: number
  avgLatencyMs: number
  dailyStats: DailyStats[]
}

/** 訊息紀錄 */
export interface MessageLog {
  id: number
  lineUserId: string
  messageText: string
  responseText: string
  responseType: ResponseType
  qaPairId: number | null
  latencyMs: number
  createdAt: string
}
