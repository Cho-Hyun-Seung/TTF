import type {
  Audience,
  CreateRoomInput,
  CreateRoomResponse,
  JoinRoomResponse,
  RoomSnapshot,
  RoomSummary,
  StatementDraft,
} from '../domain/types'

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

interface ErrorBody {
  error?: {
    code?: string
    message?: string
    field_errors?: Record<string, string>
    request_id?: string
  }
}

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

async function request<T>(path: string, init?: RequestInit): Promise<T> {
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

function command(path: string, body?: unknown) {
  return request<void>(path, {
    method: 'POST',
    headers: { 'Idempotency-Key': crypto.randomUUID() },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
}

export const api = {
  createRoom(input: CreateRoomInput) {
    return request<CreateRoomResponse>('/api/v1/rooms', {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(input),
    })
  },

  getRoomByCode(code: string, signal?: AbortSignal) {
    return request<RoomSummary>(`/api/v1/rooms/by-code/${encodeURIComponent(code)}`, { signal })
  },

  joinRoom(roomId: string, nickname: string) {
    return request<JoinRoomResponse>(`/api/v1/rooms/${roomId}/participants`, {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ nickname: nickname.trim() }),
    })
  },

  async getSnapshot(roomId: string, audience: Audience, signal?: AbortSignal) {
    const query = new URLSearchParams({ audience })
    const snapshot = await request<Omit<RoomSnapshot, 'client_received_at_ms'>>(
      `/api/v1/rooms/${roomId}/snapshot?${query}`,
      { signal },
    )
    return { ...snapshot, client_received_at_ms: Date.now() }
  },

  saveStatements(roomId: string, statements: StatementDraft[]) {
    return request<void>(`/api/v1/rooms/${roomId}/participants/me/statements`, {
      method: 'PUT',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({
        statements: statements.map((statement) => ({
          content: statement.content.trim(),
          is_fake: statement.truth === 'FAKE',
        })),
      }),
    })
  },

  submitVote(roomId: string, roundId: string, statementId: string) {
    return request<void>(`/api/v1/rooms/${roomId}/rounds/${roundId}/vote`, {
      method: 'PUT',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ statement_id: statementId }),
    })
  },

  startGame(roomId: string) {
    return command(`/api/v1/rooms/${roomId}/host/start`)
  },

  startVoting(roomId: string, roundId: string) {
    return command(`/api/v1/rooms/${roomId}/host/rounds/${roundId}/voting/start`)
  },

  closeVoting(roomId: string, roundId: string) {
    return command(`/api/v1/rooms/${roomId}/host/rounds/${roundId}/voting/close`)
  },

  extendVoting(roomId: string, roundId: string, seconds = 15) {
    return command(`/api/v1/rooms/${roomId}/host/rounds/${roundId}/voting/extend`, { seconds })
  },

  revealResult(roomId: string, roundId: string) {
    return command(`/api/v1/rooms/${roomId}/host/rounds/${roundId}/reveal`)
  },

  nextRound(roomId: string) {
    return command(`/api/v1/rooms/${roomId}/host/rounds/next`)
  },

  skipRound(roomId: string, roundId: string) {
    return command(`/api/v1/rooms/${roomId}/host/rounds/${roundId}/skip`)
  },

  pauseGame(roomId: string) {
    return command(`/api/v1/rooms/${roomId}/host/pause`)
  },

  resumeGame(roomId: string) {
    return command(`/api/v1/rooms/${roomId}/host/resume`)
  },

  cancelGame(roomId: string) {
    return command(`/api/v1/rooms/${roomId}/host/cancel`)
  },

  finishGame(roomId: string) {
    return command(`/api/v1/rooms/${roomId}/host/finish`)
  },

  kickParticipant(roomId: string, participantId: string) {
    return command(`/api/v1/rooms/${roomId}/host/participants/${participantId}/kick`)
  },
}

export function realtimeUrl(roomId: string, audience: Audience) {
  const path = `${API_BASE_URL}/api/v1/rooms/${roomId}/events`
  const url = new URL(path, window.location.origin)
  url.searchParams.set('audience', audience)
  return url.toString()
}
