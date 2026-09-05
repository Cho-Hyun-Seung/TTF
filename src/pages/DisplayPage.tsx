import { Link, useParams } from 'react-router-dom'
import { Brand } from '../components/AppShell'
import { ErrorView, LoadingView } from '../components/Feedback'
import {
  Countdown,
  Leaderboard,
  PausedNotice,
  ResultPanel,
  RoundHeading,
  StatementCards,
  VoteProgress,
} from '../components/GameViews'
import { QrCode } from '../components/QrCode'
import { RoomHeader } from '../components/RoomHeader'
import { useRoomSnapshot } from '../hooks/useRoomSnapshot'

export function DisplayPage() {
  const { roomId } = useParams()
  const { snapshot, error, loading, connection, refresh } = useRoomSnapshot(roomId, 'display')

  if (loading) return <div className="display-screen"><LoadingView /></div>
  if (error || !snapshot || !roomId) {
    return <div className="display-screen"><ErrorView message={error?.message ?? '공용 화면을 불러오지 못했어요.'} onRetry={() => void refresh()} /></div>
  }

  const round = snapshot.current_round
  const joinUrl = `${window.location.origin}/join/${snapshot.room.code}`

  return (
    <div className="display-screen">
      <header className="display-topbar"><Brand light /><span>함께하는 사람 {snapshot.room.participant_count}명</span></header>
      <main>
        <RoomHeader connection={connection} display snapshot={snapshot} />

        {['LOBBY', 'SUBMISSION', 'READY'].includes(snapshot.room.status) ? (
          <section className="display-lobby">
            <div className="display-lobby__copy">
              <p className="eyebrow">게임 참여하기</p>
              <h2>카메라를 켜고<br />QR을 스캔하세요</h2>
              <p>앱 설치 없이 바로 참여할 수 있어요.</p>
              <div className="display-ready-count"><strong>{snapshot.room.ready_count}</strong><span>/ {snapshot.room.participant_count}명 준비 완료</span></div>
            </div>
            <QrCode code={snapshot.room.code} value={joinUrl} />
          </section>
        ) : null}

        {snapshot.room.status === 'PAUSED' ? <PausedNotice snapshot={snapshot} /> : null}

        {round && ['ROUND_INTRO', 'VOTING', 'VOTE_CLOSED'].includes(snapshot.room.status) ? (
          <section className="display-round">
            <RoundHeading round={round} />
            <div className="display-round__body">
              <StatementCards statements={round.statements} />
              <aside>
                {snapshot.room.status === 'ROUND_INTRO' ? <><p className="eyebrow">이야기를 들어보세요</p><h2>어느 문장이<br />가짜일까요?</h2></> : null}
                {snapshot.room.status === 'VOTING' ? <><Countdown clientReceivedAt={snapshot.client_received_at_ms} endsAt={round.voting_ends_at} large serverTime={snapshot.server_time} /><VoteProgress {...round.vote_progress} /></> : null}
                {snapshot.room.status === 'VOTE_CLOSED' ? <><span className="closed-symbol">✓</span><p className="eyebrow">투표 마감</p><h2>곧 정답을<br />공개합니다</h2></> : null}
              </aside>
            </div>
          </section>
        ) : null}

        {round && snapshot.room.status === 'RESULT' && round.result ? (
          <section className="display-result"><RoundHeading round={round} /><ResultPanel result={round.result} /></section>
        ) : null}

        {snapshot.room.status === 'FINISHED' ? <div className="display-leaderboard"><Leaderboard entries={snapshot.leaderboard ?? []} /></div> : null}

        {['CANCELLED', 'EXPIRED'].includes(snapshot.room.status) ? (
          <ErrorView message="진행자에게 새 게임방을 요청해 주세요." title="게임이 종료되었어요" action={<Link className="button button--secondary" to="/">홈으로</Link>} />
        ) : null}
      </main>
    </div>
  )
}
