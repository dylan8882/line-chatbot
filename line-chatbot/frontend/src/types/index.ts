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

// ── 推播功能：模板與任務 ────────────────────────────────────────

/** 訊息類型 */
export type MessageType = 'TEXT' | 'FLEX' | 'IMAGE' | 'TEMPLATE'

/** 訊息模板 */
export interface MessageTemplate {
  id: number
  name: string
  messageType: MessageType
  /** LINE messages 物件陣列 JSON 字串 */
  content: string
  thumbnail: string | null
  createdAt: string
  updatedAt: string
}

export interface MessageTemplateInput {
  name: string
  messageType: MessageType
  content: string
  thumbnail?: string | null
}

/** 推播目標類型 */
export type BroadcastTargetType = 'ALL' | 'TAGS' | 'USER_LIST' | 'NARROWCAST'

/** 多標籤匹配方式 */
export type TagMatch = 'ANY' | 'ALL'

/** 推播任務狀態 */
export type BroadcastStatus =
  | 'DRAFT'
  | 'QUEUED'
  | 'SCHEDULED'
  | 'RUNNING'
  | 'PAUSED'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'

/** 推播分片狀態 */
export type BroadcastChunkStatus =
  | 'PENDING'
  | 'SENDING'
  | 'SUCCESS'
  | 'FAILED'
  | 'RETRYING'
  | 'CANCELLED'

/** 推播分片摘要 */
export interface BroadcastChunkSummary {
  id: number
  chunkIndex: number
  recipientCount: number
  status: BroadcastChunkStatus
  attempts: number
  errorCode: string | null
  errorMessage: string | null
  sentAt: string | null
}

/** 推播任務 */
export interface BroadcastTask {
  id: number
  name: string
  messageContent: string
  targetType: BroadcastTargetType
  targetFilter: string | null
  status: BroadcastStatus
  totalRecipients: number
  sentCount: number
  successCount: number
  failedCount: number
  scheduledAt: string | null
  startedAt: string | null
  finishedAt: string | null
  errorMessage: string | null
  createdAt: string
  updatedAt: string
  chunks?: BroadcastChunkSummary[]
  abTestId?: string | null
  variantLabel?: string | null
  narrowcastRequestId?: string | null
}

/** 建立推播任務請求 */
export interface BroadcastCreateRequest {
  name: string
  templateId?: number
  messageContent?: string
  targetType: BroadcastTargetType
  tagIds?: number[]
  tagMatch?: TagMatch
  userIds?: number[]
  scheduledAt?: string | null
  idempotencyKey?: string
}

/** 預估回應 */
export interface BroadcastEstimate {
  totalRecipients: number
  totalChunks: number
}

/** SSE 進度事件（與後端 BroadcastProgressEvent 對應） */
export interface BroadcastProgressEvent {
  type: 'PROGRESS' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
  taskId: number
  status: BroadcastStatus | null
  sentCount: number | null
  successCount: number | null
  failedCount: number | null
  totalRecipients: number | null
  chunkId?: number | null
  timestamp: number
}

/** 推播成效統計 */
export interface BroadcastStatistics {
  taskId: number
  status: BroadcastStatus
  totalRecipients: number
  totalChunks: number
  successChunks: number
  failedChunks: number
  retryingChunks: number
  pendingChunks: number
  deliveredRecipients: number
  successRate: number
  avgAttempts: number
  durationMs: number | null
  sendRatePerSecond: number
  errorBreakdown: { errorCode: string; count: number }[]
}

/** A/B 測試 variant 設定 */
export interface AbTestVariant {
  label: string
  templateId?: number
  messageContent?: string
  trafficPercent: number
}

/** A/B 測試建立請求 */
export interface AbTestCreateRequest {
  name: string
  variants: AbTestVariant[]
  targetType: BroadcastTargetType
  tagIds?: number[]
  tagMatch?: TagMatch
  userIds?: number[]
  scheduledAt?: string | null
  idempotencyKey?: string
}

/** A/B 測試 variant 統計 */
export interface AbTestVariantStat {
  taskId: number
  label: string
  status: BroadcastStatus
  totalRecipients: number
  sentCount: number
  successCount: number
  failedCount: number
  successRate: number
}

/** A/B 測試比較結果 */
export interface AbTestComparison {
  abTestId: string
  taskName: string
  variants: AbTestVariantStat[]
}

/** 失敗 chunk 詳情 */
export interface BroadcastFailure {
  chunkId: number
  chunkIndex: number
  recipientCount: number
  attempts: number
  status: BroadcastChunkStatus
  errorCode: string | null
  errorMessage: string | null
  lastAttemptAt: string | null
  nextRetryAt: string | null
  lineRequestId: string | null
}
