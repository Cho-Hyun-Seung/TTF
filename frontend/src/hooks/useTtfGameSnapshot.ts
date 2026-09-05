import { useCallback, useEffect, useRef, useState } from 'react'
import type { Audience, TtfGameSnapshot } from '../domain/types'
import { ApiError, ttfGameApi, ttfRealtimeUrl } from '../lib/api'

export type ConnectionState = 'connecting' | 'connected' | 'reconnecting'

const TTF_GAME_EVENTS = [
  'participant.joined',
  'participant.left',
  'participant.ready_changed',
  'game.status_changed',
  'round.started',
  'voting.started',
  'vote.progress_changed',
  'voting.closed',
  'round.result_revealed',
  'score.updated',
  'game.finished',
  'game.sync_required',
] as const

export function useTtfGameSnapshot(gameId: string | undefined, audience: Audience) {
  const [snapshot, setSnapshot] = useState<TtfGameSnapshot | null>(null)
  const [error, setError] = useState<ApiError | Error | null>(null)
  const [loading, setLoading] = useState(true)
  const [connection, setConnection] = useState<ConnectionState>('connecting')
  const requestRef = useRef<AbortController | null>(null)
  const versionRef = useRef(0)

  const refresh = useCallback(async () => {
    if (!gameId) return
    requestRef.current?.abort()
    const controller = new AbortController()
    requestRef.current = controller
    try {
      const next = await ttfGameApi.getSnapshot(gameId, audience, controller.signal)
      versionRef.current = next.version
      setSnapshot(next)
      setError(null)
    } catch (caught) {
      if (caught instanceof DOMException && caught.name === 'AbortError') return
      setError(caught instanceof Error ? caught : new Error('게임 상태를 불러오지 못했어요.'))
    } finally {
      if (requestRef.current === controller) setLoading(false)
    }
  }, [audience, gameId])

  useEffect(() => {
    versionRef.current = 0
    const timeoutId = window.setTimeout(() => void refresh(), 0)
    return () => {
      window.clearTimeout(timeoutId)
      requestRef.current?.abort()
    }
  }, [refresh])

  useEffect(() => {
    if (!gameId) return
    const source = new EventSource(ttfRealtimeUrl(gameId, audience), { withCredentials: true })

    source.onopen = () => {
      setConnection('connected')
      void refresh()
    }
    source.onerror = () => setConnection('reconnecting')

    const onGameEvent = (event: MessageEvent<string>) => {
      try {
        const payload = JSON.parse(event.data) as { version?: number }
        if (payload.version !== undefined && payload.version <= versionRef.current) return
      } catch {
        // A malformed notification is harmless; a snapshot refresh remains authoritative.
      }
      void refresh()
    }

    TTF_GAME_EVENTS.forEach((name) => source.addEventListener(name, onGameEvent as EventListener))

    return () => {
      TTF_GAME_EVENTS.forEach((name) => source.removeEventListener(name, onGameEvent as EventListener))
      source.close()
    }
  }, [audience, refresh, gameId])

  useEffect(() => {
    if (connection !== 'reconnecting') return
    const intervalId = window.setInterval(() => void refresh(), 5_000)
    return () => window.clearInterval(intervalId)
  }, [connection, refresh])

  return { snapshot, error, loading, connection, refresh }
}
