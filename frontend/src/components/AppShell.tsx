import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'

interface AppShellProps {
  children: ReactNode
  compact?: boolean
  headerAside?: ReactNode
}

export function Brand({ light = false }: { light?: boolean }) {
  return (
    <Link aria-label="TTF 홈" className={`brand${light ? ' brand--light' : ''}`} to="/">
      <span className="brand__mark" aria-hidden="true">
        <span>T</span>
        <span>T</span>
        <span>F</span>
      </span>
      <span className="brand__name">진짜 둘, 가짜 하나</span>
    </Link>
  )
}

export function AppShell({ children, compact = false, headerAside }: AppShellProps) {
  return (
    <div className={`app-shell${compact ? ' app-shell--compact' : ''}`}>
      <header className="topbar">
        <Brand />
        {headerAside ? <div className="topbar__aside">{headerAside}</div> : null}
      </header>
      <main className="page">{children}</main>
      {/* <footer className="footer">서로를 조금 더 알아가는 가장 가벼운 방법</footer> */}
    </div>
  )
}

export function StepLabel({ children }: { children: ReactNode }) {
  return <p className="step-label">{children}</p>
}

