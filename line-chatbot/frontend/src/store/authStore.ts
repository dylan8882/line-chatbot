/**
 * Zustand 認證狀態管理 Store
 * 負責維護登入狀態、token 與用戶資訊，並同步至 localStorage
 */
import { create } from 'zustand'

interface AuthState {
  /** JWT Bearer token */
  token: string | null
  /** 登入用戶名稱 */
  username: string | null
  /** 用戶角色 */
  role: string | null
  /** 是否已通過認證 */
  isAuthenticated: boolean
  /**
   * 設定認證資訊並持久化至 localStorage
   */
  setAuth: (token: string, username: string, role: string) => void
  /**
   * 清除認證資訊並移除 localStorage 紀錄
   */
  clearAuth: () => void
}

const useAuthStore = create<AuthState>((set) => ({
  // 初始化時從 localStorage 讀取既有狀態
  token: localStorage.getItem('token'),
  username: localStorage.getItem('username'),
  role: localStorage.getItem('role'),
  isAuthenticated: !!localStorage.getItem('token'),

  setAuth: (token: string, username: string, role: string) => {
    localStorage.setItem('token', token)
    localStorage.setItem('username', username)
    localStorage.setItem('role', role)
    set({ token, username, role, isAuthenticated: true })
  },

  clearAuth: () => {
    localStorage.removeItem('token')
    localStorage.removeItem('username')
    localStorage.removeItem('role')
    set({ token: null, username: null, role: null, isAuthenticated: false })
  },
}))

export default useAuthStore
