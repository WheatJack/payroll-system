import { api } from './client'
import type { CurrentUser, LoginRequest, LoginResponse } from './models'

export function login(payload: LoginRequest): Promise<LoginResponse> {
  return api.post<LoginResponse>('/auth/login', payload)
}

export function fetchCurrentUser(): Promise<CurrentUser> {
  return api.get<CurrentUser>('/auth/me')
}
