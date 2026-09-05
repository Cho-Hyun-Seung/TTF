import { useEffect, useMemo, useState } from 'react'

function secondsUntil(endsAt: string | undefined, now: number, serverOffset: number) {
  if (!endsAt) return 0
  return Math.max(0, Math.ceil((new Date(endsAt).getTime() - (now + serverOffset)) / 1_000))
}

export function useCountdown(endsAt?: string, serverTime?: string, clientReceivedAt?: number) {
  const [now, setNow] = useState(() => Date.now())
  const serverOffset = useMemo(
    () => serverTime && clientReceivedAt ? new Date(serverTime).getTime() - clientReceivedAt : 0,
    [clientReceivedAt, serverTime],
  )

  useEffect(() => {
    if (!endsAt) return
    const intervalId = window.setInterval(() => setNow(Date.now()), 250)
    return () => window.clearInterval(intervalId)
  }, [endsAt])

  const seconds = secondsUntil(endsAt, now, serverOffset)
  return useMemo(
    () => ({
      seconds,
      label: `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`,
      urgent: seconds <= 10,
    }),
    [seconds],
  )
}
