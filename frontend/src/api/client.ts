/**
 * Axios HTTP 客戶端實例
 * 統一處理認證 header、錯誤攔截與 401 自動登出
 */
import axios, { AxiosError, InternalAxiosRequestConfig } from 'axios'

const client = axios.create({
  baseURL: '/api',
  timeout: 15000,
  headers: {
    'Content-Type': 'application/json',
  },
})

/**
 * Request interceptor：從 localStorage 讀取 JWT token 並加入 Authorization header
 */
client.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = localStorage.getItem('token')
    if (token && config.headers) {
      config.headers['Authorization'] = `Bearer ${token}`
    }
    return config
  },
  (error: AxiosError) => {
    return Promise.reject(error)
  }
)

/**
 * Response interceptor：
 * - 401 時清除 token 並導向登入頁
 * - 統一錯誤處理，拋出可讀錯誤訊息
 */
client.interceptors.response.use(
  (response) => response,
  (error: AxiosError<{ message?: string; error?: string }>) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('username')
      localStorage.removeItem('role')
      // 避免在登入頁產生無限重導
      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
      }
    }
    const message =
      error.response?.data?.message ||
      error.response?.data?.error ||
      error.message ||
      '發生未知錯誤'
    return Promise.reject(new Error(message))
  }
)

export default client
