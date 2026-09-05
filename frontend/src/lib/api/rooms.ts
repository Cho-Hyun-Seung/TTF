import type {
  CreateRoomInput,
  CreateRoomResponse,
  JoinRoomResponse,
  RoomSummary,
} from '../../domain/types'
import { idempotencyHeaders, request } from './client'

const ROOMS_PATH = '/api/v1/rooms'

export const roomApi = {
  create(input: CreateRoomInput) {
    return request<CreateRoomResponse>(ROOMS_PATH, {
      method: 'POST',
      headers: idempotencyHeaders(),
      body: JSON.stringify(input),
    })
  },

  getByCode(code: string, signal?: AbortSignal) {
    return request<RoomSummary>(`${ROOMS_PATH}/by-code/${encodeURIComponent(code)}`, { signal })
  },

  join(roomId: string, nickname: string) {
    return request<JoinRoomResponse>(`${ROOMS_PATH}/${encodeURIComponent(roomId)}/participants`, {
      method: 'POST',
      headers: idempotencyHeaders(),
      body: JSON.stringify({ nickname: nickname.trim() }),
    })
  },

  removeParticipant(roomId: string, participantId: string) {
    return request<void>(
      `${ROOMS_PATH}/${encodeURIComponent(roomId)}/participants/${encodeURIComponent(participantId)}`,
      {
        method: 'DELETE',
        headers: idempotencyHeaders(),
      },
    )
  },

  cancel(roomId: string) {
    return request<void>(`${ROOMS_PATH}/${encodeURIComponent(roomId)}/commands/cancel`, {
      method: 'POST',
      headers: idempotencyHeaders(),
    })
  },
}
