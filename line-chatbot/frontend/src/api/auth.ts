/**
 * 認證相關 API
 */
import client from './client'
import type { ApiResponse, LoginRequest, LoginResponse } from '../types'

/**
 * 使用帳號密碼登入，回傳 JWT token
 */
export const login = (data: LoginRequest) =>
  client.post<ApiResponse<LoginResponse>>('/auth/login', data)

/**
 * 登出，將目前 token 加入伺服器端黑名單
 */
export const logout = () => client.post<ApiResponse<null>>('/auth/logout')

/**
 * 刷新 JWT token
 */
export const refreshToken = () =>
  client.post<ApiResponse<LoginResponse>>('/auth/refresh')
