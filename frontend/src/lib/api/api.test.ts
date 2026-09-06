import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CreateRoomInput } from '../../domain/types'
import { ApiError } from './client'
import { roomApi } from './rooms'
import { ttfGameApi, ttfRealtimeUrl } from './ttf'

const fetchMock = vi.fn()

function response(body?: unknown, status = body === undefined ? 204 : 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(body),
  }
}

function success<T>(data: T, status = 200) {
  return response({
    success: true,
    data,
    response_time: '2026-09-06T12:34:56.789Z',
  }, status)
}

function requestedPath(callNumber: number) {
  const url = new URL(String(fetchMock.mock.calls[callNumber - 1][0]), window.location.origin)
  return `${url.pathname}${url.search}`
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
  fetchMock.mockReset()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('roomApi', () => {
  it('creates a room with a discriminated game configuration', async () => {
    const input: CreateRoomInput = {
      name: '워크숍',
      settings: { max_participants: 30 },
      game: {
        type: 'TTF',
        settings: {
          statement_max_length: 100,
          voting_duration_seconds: 60,
          speaker_order: 'RANDOM',
          anonymous_voting: true,
        },
      },
    }
    const created = {
      room: { id: 'room-1', code: 'A7K2Q9', join_url: '/join/A7K2Q9' },
      game: { id: 'game-1', type: 'TTF' },
    }
    fetchMock.mockResolvedValue(success(created, 201))

    await expect(roomApi.create(input)).resolves.toEqual(created)
    expect(requestedPath(1)).toBe('/api/v1/rooms')
    expect(fetchMock.mock.calls[0][1]).toEqual(expect.objectContaining({
      method: 'POST',
      credentials: 'include',
      body: JSON.stringify(input),
    }))
  })

  it('keeps room membership operations under the room API', async () => {
    fetchMock.mockResolvedValue(success({
      id: 'room-1',
      code: 'A7K2Q9',
      name: '워크숍',
      status: 'OPEN',
      joinable: true,
      participant_count: 1,
      settings: { max_participants: 30 },
      active_game: { id: 'game-1', type: 'TTF' },
    }))

    const room = await roomApi.getByCode('A7 K2')
    fetchMock.mockResolvedValueOnce(success({ room_id: 'room-1', participant_id: 'participant-1', game: { id: 'game-1', type: 'TTF' } }, 201))
    await roomApi.join('room/1', '  민준  ')
    await roomApi.removeParticipant('room/1', 'participant/2')
    await roomApi.cancel('room/1')

    expect(room).toEqual(expect.objectContaining({ id: 'room-1', active_game: { id: 'game-1', type: 'TTF' } }))
    expect(requestedPath(1)).toBe('/api/v1/rooms/by-code/A7%20K2')
    expect(fetchMock.mock.calls[0][1]).toEqual(expect.objectContaining({ credentials: 'include' }))
    expect(requestedPath(2)).toBe('/api/v1/rooms/room%2F1/participants')
    expect(fetchMock.mock.calls[1][1]).toEqual(expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ nickname: '민준' }),
    }))
    expect(requestedPath(3)).toBe('/api/v1/rooms/room%2F1/participants/participant%2F2')
    expect(fetchMock.mock.calls[2][1]).toEqual(expect.objectContaining({ method: 'DELETE' }))
    expect(requestedPath(4)).toBe('/api/v1/rooms/room%2F1/commands/cancel')
    expect(fetchMock.mock.calls[3][1]).toEqual(expect.objectContaining({ method: 'POST' }))

    for (const index of [2, 3, 4]) {
      expect(fetchMock.mock.calls[index - 1][1]?.headers).toEqual(expect.objectContaining({
        'Idempotency-Key': expect.any(String),
      }))
    }
  })

  it('maps the documented top-level error response', async () => {
    fetchMock.mockResolvedValue(response({
      success: false,
      error_code: 'NICKNAME_TAKEN',
      message: '이미 사용 중인 닉네임이에요. 다른 이름을 입력해 주세요.',
      response_time: '2026-09-06T12:34:56.789Z',
    }, 409))

    const promise = roomApi.join('room-1', '민준')

    await expect(promise).rejects.toBeInstanceOf(ApiError)
    await expect(promise).rejects.toMatchObject({
      status: 409,
      code: 'NICKNAME_TAKEN',
      message: '이미 사용 중인 닉네임이에요. 다른 이름을 입력해 주세요.',
    })
  })
})

describe('ttfGameApi', () => {
  it('uses the TTF game id for snapshot and realtime endpoints', async () => {
    fetchMock.mockResolvedValue(success({ version: 3, server_time: '2026-09-05T00:00:00.000Z' }))

    const snapshot = await ttfGameApi.getSnapshot('game/1', 'host')
    const realtime = new URL(ttfRealtimeUrl('game/1', 'display'))

    expect(snapshot).toEqual(expect.objectContaining({ version: 3, client_received_at_ms: expect.any(Number) }))
    expect(requestedPath(1)).toBe('/api/v1/games/ttf/game%2F1/snapshot?audience=host')
    expect(fetchMock.mock.calls[0][1]).toEqual(expect.objectContaining({ credentials: 'include' }))
    expect(realtime.pathname).toBe('/api/v1/games/ttf/game%2F1/events')
    expect(realtime.searchParams.get('audience')).toBe('display')
  })

  it('keeps TTF inputs and round commands under the game API', async () => {
    fetchMock.mockResolvedValue(response())

    await ttfGameApi.saveStatements('game-1', [
      { content: ' 진짜 문장 하나 ', truth: 'TRUE' },
      { content: '진짜 문장 둘', truth: 'TRUE' },
      { content: '가짜 문장 하나', truth: 'FAKE' },
    ])
    await ttfGameApi.submitVote('game-1', 'round-1', 'statement-1')
    await ttfGameApi.startVoting('game-1', 'round-1')

    expect(requestedPath(1)).toBe('/api/v1/games/ttf/game-1/participants/me/statements')
    expect(fetchMock.mock.calls[0][1]).toEqual(expect.objectContaining({
      method: 'PUT',
      body: JSON.stringify({
        statements: [
          { content: '진짜 문장 하나', is_fake: false },
          { content: '진짜 문장 둘', is_fake: false },
          { content: '가짜 문장 하나', is_fake: true },
        ],
      }),
    }))
    expect(requestedPath(2)).toBe('/api/v1/games/ttf/game-1/rounds/round-1/vote')
    expect(fetchMock.mock.calls[1][1]).toEqual(expect.objectContaining({
      method: 'PUT',
      body: JSON.stringify({ statement_id: 'statement-1' }),
    }))
    expect(requestedPath(3)).toBe('/api/v1/games/ttf/game-1/rounds/round-1/commands/start-voting')
    expect(fetchMock.mock.calls[2][1]).toEqual(expect.objectContaining({ method: 'POST' }))
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty('Idempotency-Key')
    expect(fetchMock.mock.calls[1][1]?.headers).not.toHaveProperty('Idempotency-Key')
    expect(fetchMock.mock.calls[2][1]?.headers).toEqual(expect.objectContaining({
      'Idempotency-Key': expect.any(String),
    }))
  })

  it('maps every host action to an explicit TTF command endpoint', async () => {
    fetchMock.mockResolvedValue(response())

    await ttfGameApi.start('game-1')
    await ttfGameApi.extendVoting('game-1', 'round-1', 30)
    await ttfGameApi.closeVoting('game-1', 'round-1')
    await ttfGameApi.revealResult('game-1', 'round-1')
    await ttfGameApi.skipRound('game-1', 'round-1')
    await ttfGameApi.nextRound('game-1')
    await ttfGameApi.pause('game-1')
    await ttfGameApi.resume('game-1')
    await ttfGameApi.finish('game-1')

    expect(fetchMock.mock.calls.map((_, index) => requestedPath(index + 1))).toEqual([
      '/api/v1/games/ttf/game-1/commands/start',
      '/api/v1/games/ttf/game-1/rounds/round-1/commands/extend-voting',
      '/api/v1/games/ttf/game-1/rounds/round-1/commands/close-voting',
      '/api/v1/games/ttf/game-1/rounds/round-1/commands/reveal-result',
      '/api/v1/games/ttf/game-1/rounds/round-1/commands/skip',
      '/api/v1/games/ttf/game-1/commands/next-round',
      '/api/v1/games/ttf/game-1/commands/pause',
      '/api/v1/games/ttf/game-1/commands/resume',
      '/api/v1/games/ttf/game-1/commands/finish',
    ])
  })
})
