import type { Audience, StatementDraft, TtfGameSnapshot } from '../../domain/types'
import { apiUrl, idempotencyHeaders, request } from './client'

const ttfGamePath = (gameId: string) => `/api/v1/games/ttf/${encodeURIComponent(gameId)}`

function command(gameId: string, commandName: string, body?: unknown) {
  return request<void>(`${ttfGamePath(gameId)}/commands/${commandName}`, {
    method: 'POST',
    headers: idempotencyHeaders(),
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
}

function roundCommand(gameId: string, roundId: string, commandName: string, body?: unknown) {
  return request<void>(
    `${ttfGamePath(gameId)}/rounds/${encodeURIComponent(roundId)}/commands/${commandName}`,
    {
      method: 'POST',
      headers: idempotencyHeaders(),
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    },
  )
}

export const ttfGameApi = {
  async getSnapshot(gameId: string, audience: Audience, signal?: AbortSignal) {
    const query = new URLSearchParams({ audience })
    const snapshot = await request<Omit<TtfGameSnapshot, 'client_received_at_ms'>>(
      `${ttfGamePath(gameId)}/snapshot?${query}`,
      { signal },
    )
    return { ...snapshot, client_received_at_ms: Date.now() }
  },

  saveStatements(gameId: string, statements: StatementDraft[]) {
    return request<void>(`${ttfGamePath(gameId)}/participants/me/statements`, {
      method: 'PUT',
      body: JSON.stringify({
        statements: statements.map((statement) => ({
          content: statement.content.trim(),
          is_fake: statement.truth === 'FAKE',
        })),
      }),
    })
  },

  submitVote(gameId: string, roundId: string, statementId: string) {
    return request<void>(
      `${ttfGamePath(gameId)}/rounds/${encodeURIComponent(roundId)}/vote`,
      {
        method: 'PUT',
        body: JSON.stringify({ statement_id: statementId }),
      },
    )
  },

  start(gameId: string) {
    return command(gameId, 'start')
  },

  startVoting(gameId: string, roundId: string) {
    return roundCommand(gameId, roundId, 'start-voting')
  },

  closeVoting(gameId: string, roundId: string) {
    return roundCommand(gameId, roundId, 'close-voting')
  },

  extendVoting(gameId: string, roundId: string, seconds = 15) {
    return roundCommand(gameId, roundId, 'extend-voting', { seconds })
  },

  revealResult(gameId: string, roundId: string) {
    return roundCommand(gameId, roundId, 'reveal-result')
  },

  skipRound(gameId: string, roundId: string) {
    return roundCommand(gameId, roundId, 'skip')
  },

  nextRound(gameId: string) {
    return command(gameId, 'next-round')
  },

  pause(gameId: string) {
    return command(gameId, 'pause')
  },

  resume(gameId: string) {
    return command(gameId, 'resume')
  },

  finish(gameId: string) {
    return command(gameId, 'finish')
  },
}

export function ttfRealtimeUrl(gameId: string, audience: Audience) {
  const url = apiUrl(`${ttfGamePath(gameId)}/events`)
  url.searchParams.set('audience', audience)
  return url.toString()
}
