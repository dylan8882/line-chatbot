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

/** LINE Messaging API 頻道設定（GET 回應，敏感欄位已遮罩） */
export interface LineChannelConfig {
  isConfigured: boolean
  channelId: string | null
  /** 遮罩值，例如 "****a1b2" */
  channelSecretMasked: string | null
  /** 遮罩值，例如 "****a1b2" */
  channelAccessTokenMasked: string | null
  serverBaseUrl: string | null
  /** 後端計算的 Webhook URL = serverBaseUrl + "/webhook" */
  webhookUrl: string | null
  webhookEnabled: boolean
  autoReplyEnabled: boolean
  greetingEnabled: boolean
  updatedAt: string | null
}

/** LINE 頻道設定更新請求（PUT） */
export interface LineChannelConfigUpdate {
  channelId?: string | null
  /** null = 不更新；空字串 = 清除 */
  channelSecret?: string | null
  /** null = 不更新；空字串 = 清除 */
  channelAccessToken?: string | null
  serverBaseUrl?: string | null
  webhookEnabled?: boolean
  autoReplyEnabled?: boolean
  greetingEnabled?: boolean
}

// ── 推播功能：標籤與用戶 ────────────────────────────────────────

/** 標籤 */
export interface Tag {
  id: number
  name: string
  color: string
  description: string | null
  userCount: number
  createdAt: string
  updatedAt: string
}

/** 標籤新增 / 修改請求 */
export interface TagInput {
  name: string
  color?: string
  description?: string | null
}

/** LINE 用戶狀態 */
export type LineUserStatus = 'FOLLOWED' | 'BLOCKED'

/** LINE 用戶資料 */
export interface LineUser {
  id: number
  lineUserId: string
  displayName: string | null
  pictureUrl: string | null
  statusMessage: string | null
  language: string | null
  status: LineUserStatus
  followedAt: string | null
  unfollowedAt: string | null
  lastMessageAt: string | null
  createdAt: string
  tags: Tag[]
}

/** 批量貼標籤動作 */
export type BulkTagAction = 'ADD' | 'REMOVE'

/** 批量貼標籤請求 */
export interface BulkTagRequest {
  userIds: number[]
  tagIds: number[]
  action: BulkTagAction
}
