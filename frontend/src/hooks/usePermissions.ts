/**
 * 角色權限 hook：根據目前登入用戶的 role 判斷可否執行特定操作。
 *
 * 角色階層（高 → 低）：
 *   ADMIN > MANAGER > MARKETER > CS_AGENT > VIEWER
 *
 * 對應後端 @PreAuthorize 規則，前端依此隱藏對應按鈕 / 選單。
 */
import useAuthStore from '../store/authStore'

export type Role = 'ADMIN' | 'MANAGER' | 'MARKETER' | 'CS_AGENT' | 'VIEWER'

const HIERARCHY: Role[] = ['VIEWER', 'CS_AGENT', 'MARKETER', 'MANAGER', 'ADMIN']

function levelOf(role: string | null): number {
  if (!role) return -1
  const idx = HIERARCHY.indexOf(role as Role)
  return idx
}

export function usePermissions() {
  const role = useAuthStore((s) => s.role)
  const lv = levelOf(role)

  return {
    role,

    /** 至少 CS_AGENT：可貼標籤 */
    canTagUsers: lv >= HIERARCHY.indexOf('CS_AGENT'),

    /** 至少 MARKETER：可管模板、建推播草稿 */
    canManageTemplates: lv >= HIERARCHY.indexOf('MARKETER'),
    canCreateBroadcast: lv >= HIERARCHY.indexOf('MARKETER'),

    /** 至少 MANAGER：可送出 / 取消推播、管標籤 */
    canSubmitBroadcast: lv >= HIERARCHY.indexOf('MANAGER'),
    canCancelBroadcast: lv >= HIERARCHY.indexOf('MANAGER'),
    canManageTags: lv >= HIERARCHY.indexOf('MANAGER'),

    /** 僅 ADMIN：管 LINE 設定、用戶帳號 */
    canManageLineSettings: lv >= HIERARCHY.indexOf('ADMIN'),
  }
}

export default usePermissions
