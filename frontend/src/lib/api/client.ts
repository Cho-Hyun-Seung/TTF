interface ErrorBody {
  error?: {
    code?: string
    message?: string
    field_errors?: Record<string, string>
    request_id?: string
  }
}

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly fieldErrors?: Record<string, string>
  readonly requestId?: string

  constructor(status: number, body: ErrorBody) {
    super(body.error?.message ?? '요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.')
    this.name = 'ApiError'
    this.status = status
    this.code = body.error?.code ?? 'UNKNOWN_ERROR'
    this.fieldErrors = body.error?.field_errors
    this.requestId = body.error?.request_id
  }
}

export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...init?.headers,
    },
  })

  if (!response.ok) {
    let body: ErrorBody = {}
    try {
      body = (await response.json()) as ErrorBody
    } catch {
      // The fallback message intentionally avoids exposing an untrusted server body.
    }
    throw new ApiError(response.status, body)
  }

  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

export function idempotencyHeaders() {
  return { 'Idempotency-Key': crypto.randomUUID() }
}

export function apiUrl(path: string) {
  return new URL(`${API_BASE_URL}${path}`, window.location.origin)
}
