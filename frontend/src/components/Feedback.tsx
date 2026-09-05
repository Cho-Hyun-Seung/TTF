import type { ReactNode } from 'react'
import type { ConnectionState } from '../hooks/useRoomSnapshot'
import { RefreshIcon, WifiOffIcon } from './Icons'

export function ConnectionBanner({ state }: { state: ConnectionState }) {
  if (state === 'connected') return null
  return (
    <div className="connection-banner" role="status">
      <WifiOffIcon />
      <span>{state === 'connecting' ? '게임에 연결하고 있어요…' : '연결이 끊겨 다시 연결하고 있어요…'}</span>
    </div>
  )
}

export function LoadingView({ message = '게임을 불러오고 있어요' }: { message?: string }) {
  return (
    <div className="center-state" role="status">
      <span className="loader" aria-hidden="true" />
      <h1>{message}</h1>
      <p>잠시만 기다려 주세요.</p>
    </div>
  )
}

interface ErrorViewProps {
  title?: string
  message: string
  action?: ReactNode
  onRetry?: () => void
}

export function ErrorView({ title = '문제가 생겼어요', message, action, onRetry }: ErrorViewProps) {
  return (
    <div className="center-state center-state--error" role="alert">
      <span className="error-symbol" aria-hidden="true">!</span>
      <h1>{title}</h1>
      <p>{message}</p>
      {onRetry ? (
        <button className="button button--secondary" onClick={onRetry} type="button">
          <RefreshIcon /> 다시 시도
        </button>
      ) : action}
    </div>
  )
}

export function InlineError({ children }: { children: ReactNode }) {
  return (
    <div className="inline-error" role="alert">
      <span aria-hidden="true">!</span>
      <p>{children}</p>
    </div>
  )
}

