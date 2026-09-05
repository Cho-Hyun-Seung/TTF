import { useCallback, useEffect, useRef, useState } from 'react'
import type { Audience, RoomSnapshot } from '../domain/types'
import { ApiError, api, realtimeUrl } from '../lib/api'

export type ConnectionState = 'connecting' | 'connected' | 'reconnecting'

const ROOM_EVENTS = [
  'participant.joined',
  'participant.left',
  'participant.ready_changed',
  'room.status_changed',
  'round.started',
  'voting.started',
  'vote.progress_changed',
  'voting.closed',
  'round.result_revealed',
  'score.updated',
  'game.finished',
  'room.sync_required',
] as const

export function useRoomSnapshot(roomId: string | undefined, audience: Audience) {
  const [snapshot, setSnapshot] = useState<RoomSnapshot | null>(null)
  const [error, setError] = useState<ApiError | Error | null>(null)
  const [loading, setLoading] = useState(true)
  const [connection, setConnection] = useState<ConnectionState>('connecting')
  const requestRef = useRef<AbortController | null>(null)
  const versionRef = useRef(0)

  const refresh = useCallback(async () => {
    if (!roomId) return
    requestRef.current?.abort()
    const controller = new AbortController()
    requestRef.current = controller
    try {
      const next = await api.getSnapshot(roomId, audience, controller.signal)
      versionRef.current = next.version
      setSnapshot(next)
      setError(null)
    } catch (caught) {
      if (caught instanceof DOMException && caught.name === 'AbortError') return
      setError(caught instanceof Error ? caught : new Error('게임 상태를 불러오지 못했어요.'))
    } finally {
      if (requestRef.current === controller) setLoading(false)
    }
  }, [audience, roomId])

  useEffect(() => {
    versionRef.current = 0
    const timeoutId = window.setTimeout(() => void refresh(), 0)
    return () => {
      window.clearTimeout(timeoutId)
      requestRef.current?.abort()
    }
  }, [refresh])

  useEffect(() => {
    if (!roomId) return
    const source = new EventSource(realtimeUrl(roomId, audience), { withCredentials: true })

    source.onopen = () => {
      setConnection('connected')
      void refresh()
    }
    source.onerror = () => setConnection('reconnecting')

    const onRoomEvent = (event: MessageEvent<string>) => {
      try {
        const payload = JSON.parse(event.data) as { version?: number }
        if (payload.version !== undefined && payload.version <= versionRef.current) return
      } catch {
        // A malformed notification is harmless; a snapshot refresh remains authoritative.
      }
      void refresh()
    }

    ROOM_EVENTS.forEach((name) => source.addEventListener(name, onRoomEvent as EventListener))

    return () => {
      ROOM_EVENTS.forEach((name) => source.removeEventListener(name, onRoomEvent as EventListener))
      source.close()
    }
  }, [audience, refresh, roomId])

  useEffect(() => {
    if (connection !== 'reconnecting') return
    const intervalId = window.setInterval(() => void refresh(), 5_000)
    return () => window.clearInterval(intervalId)
  }, [connection, refresh])

  return { snapshot, error, loading, connection, refresh }
}
